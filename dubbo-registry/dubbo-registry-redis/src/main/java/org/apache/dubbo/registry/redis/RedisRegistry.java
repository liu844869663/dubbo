/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.redis;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.redis.RedisClient;
import org.apache.dubbo.remoting.redis.jedis.ClusterRedisClient;
import org.apache.dubbo.remoting.redis.jedis.MonoRedisClient;
import org.apache.dubbo.remoting.redis.jedis.SentinelRedisClient;
import org.apache.dubbo.rpc.RpcException;

import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.MONO_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.REDIS_CLIENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SENTINEL_REDIS;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD;
import static org.apache.dubbo.registry.Constants.DEFAULT_SESSION_TIMEOUT;
import static org.apache.dubbo.registry.Constants.REGISTER;
import static org.apache.dubbo.registry.Constants.REGISTRY_RECONNECT_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SESSION_TIMEOUT_KEY;
import static org.apache.dubbo.registry.Constants.UNREGISTER;

/**
 * RedisRegistry
 */
public class RedisRegistry extends FailbackRegistry {

    private static final String DEFAULT_ROOT = "dubbo";

    /**
     * 线程池
     */
    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    /**
     * key 过期机制任务的 Future 对象，任务的间隔为 expirePeriod 的 1/2
     * 避免过于频繁，对 Redis 的压力过大；同时，避免过于不频繁，每次执行时，都过期了
     */
    private final ScheduledFuture<?> expireFuture;

    /**
     * 根路径，默认为 `/dubbo`，对应着分组名称
     */
    private final String root;

    /**
     * Redis 客户端
     */
    private RedisClient redisClient;

    /**
     * 缓存每个服务对应的 Notifier 通知器对象
     * Notifier 会一直向 Redis 订阅这个服务，接收到 register 或者 unregister 事件则进行回调处理
     */
    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<>();

    /**
     * 重连策略，默认 3s
     */
    private final int reconnectPeriod;

    /**
     * key 过期策略，默认 60s
     */
    private final int expirePeriod;

    /**
     * 是否为服务监控中心
     */
    private volatile boolean admin = false;

    /**
     * 本地缓存的每个服务 URL 和对应到期时间，会定时更新（每隔 30s，过期时间是 60 s）
     * key：服务 URL
     * value：到期时间
     */
    private final Map<URL, Long> expireCache = new ConcurrentHashMap<>();

    // just for unit test
    /**
     * 是否清理本地已到期服务 URL，然后进行回调处理
     */
    private volatile boolean doExpire = true;

    public RedisRegistry(URL url) {
        // 设置失败重试策略，是否生成本地缓存文件
        super(url);
        // 获取 Redis 客户端类型，默认为 `mono`
        String type = url.getParameter(REDIS_CLIENT_KEY, MONO_REDIS);
        if (SENTINEL_REDIS.equals(type)) {
            // 使用 jedis 创建一个 JedisSentinelPool 连接池
            redisClient = new SentinelRedisClient(url);
        } else if (CLUSTER_REDIS.equals(type)) {
            // 使用 jedis 创建一个 JedisCluster 集群对象
            redisClient = new ClusterRedisClient(url);
        } else {
            // 使用 jedis 创建一个 JedisPool 连接池
            redisClient = new MonoRedisClient(url);
        }

        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }

        // 重连策略，默认 3s
        this.reconnectPeriod = url.getParameter(REGISTRY_RECONNECT_PERIOD_KEY, DEFAULT_REGISTRY_RECONNECT_PERIOD);
        // 设置根路径，默认为 `/dubbo`
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        if (!group.endsWith(PATH_SEPARATOR)) {
            group = group + PATH_SEPARATOR;
        }
        this.root = group;

        // key 过期策略，默认 60s
        this.expirePeriod = url.getParameter(SESSION_TIMEOUT_KEY, DEFAULT_SESSION_TIMEOUT);
        // key 过期机制任务的 Future 对象，任务的间隔为 expirePeriod 的 1/2，避免过于频繁，对 Redis 的压力过大；同时，避免过于不频繁，每次执行时，都过期了
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(() -> {
            try {
                // 延长未过期的 Key，删除过期的 Key
                deferExpired(); // Extend the expiration time
            } catch (Throwable t) { // Defensive fault tolerance
                logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    private void deferExpired() {
        // 获取已注册的所有 URL 们
        for (URL url : new HashSet<>(getRegistered())) {
            // 如果 `dynamic=true`，表示动态注册服务，默认为 `true`
            if (url.getParameter(DYNAMIC_KEY, true)) {
                String key = toCategoryPath(url);
                // 重新设置这个服务的键值对，并发送 register 注册事件
                if (redisClient.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                    redisClient.publish(key, REGISTER);
                }
            }
        }

        if (doExpire) {
            // 从本地缓存中获取未过期的 key，进行回调处理，移除掉已过期的 key，很关键
            for (Map.Entry<URL, Long> expireEntry : expireCache.entrySet()) {
                if (expireEntry.getValue() < System.currentTimeMillis()) {
                    doNotify(toCategoryPath(expireEntry.getKey()));
                }
            }
        }

        // 监控中心负责删除过期脏数据
        if (admin) {
            clean();
        }
    }

    private void clean() {
        // 扫描出 `/dubbo/*` 所有的 key
        Set<String> keys = redisClient.scan(root + ANY_VALUE);
        // 删除所有的键值对，并发送 unregister 事件
        if (CollectionUtils.isNotEmpty(keys)) {
            for (String key : keys) {
                Map<String, String> values = redisClient.hgetAll(key);
                if (CollectionUtils.isNotEmptyMap(values)) {
                    boolean delete = false;
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        URL url = URL.valueOf(entry.getKey());
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            long expire = Long.parseLong(entry.getValue());
                            if (expire < now) {
                                redisClient.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    if (delete) {
                        redisClient.publish(key, UNREGISTER);
                    }
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return redisClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            redisClient.destroy();
        } catch (Throwable t) {
            logger.warn("Failed to destroy the redis registry client. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
        }
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    @Override
    public void doRegister(URL url) {
        // 获取分类的名称，例如 `/dubbo/服务名称/providers`
        String key = toCategoryPath(url);
        // 这个服务的信息
        String value = url.toFullString();
        // 获取这个 key 的到期时间，默认 60s
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        try {
            // 往 Redis 写入这个键值对，并设置到期时间
            redisClient.hset(key, value, expire);
            // 向 Redis 为这个 key 发布 register 注册事件
            redisClient.publish(key, REGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to register service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnregister(URL url) {
        // 获取分类的名称，例如 `/dubbo/服务名称/providers`
        String key = toCategoryPath(url);
        // 这个服务的信息
        String value = url.toFullString();
        try {
            // 删除这个 key
            redisClient.hdel(key, value);
            // 向 Redis 为这个 key 发布 unregister 下线事件
            redisClient.publish(key, UNREGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to unregister service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        // 获取服务路径，例如：`/dubbo/服务名称`
        String service = toServicePath(url);
        // 获得对应的 Notifier 通知器对象
        Notifier notifier = notifiers.get(service);
        // 如果缓存中没有，则新创建一个
        if (notifier == null) {
            // 创建一个 Notifier 通知器，一个守护进程
            Notifier newNotifier = new Notifier(service);
            // 放入缓存中
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            if (notifier == newNotifier) {
                /**
                 * 启动这个守护进程
                 * 向 Redis 订阅这个 key，当接收到 unregister 或者 register 消息时，则调用 {@link this#doNotify(String)} 方法进行处理
                 */
                notifier.start();
            }
        }
        try {
            if (service.endsWith(ANY_VALUE)) {
                admin = true;
                Set<String> keys = redisClient.scan(service);
                if (CollectionUtils.isNotEmpty(keys)) {
                    Map<String, Set<String>> serviceKeys = new HashMap<>();
                    for (String key : keys) {
                        String serviceKey = toServicePath(key);
                        Set<String> sk = serviceKeys.computeIfAbsent(serviceKey, k -> new HashSet<>());
                        sk.add(key);
                    }
                    for (Set<String> sk : serviceKeys.values()) {
                        doNotify(sk, url, Collections.singletonList(listener));
                    }
                }
            } else {
                /**
                 * 先获取这个服务的所有 key，根据订阅的分类，例如 `providers`，则获取对应的键值，也就是提供者们，进行回调处理
                 *
                 * @see ServiceDiscoveryRegistryDirectory#notify(List)
                 */
                doNotify(redisClient.scan(service + PATH_SEPARATOR + ANY_VALUE), url, Collections.singletonList(listener));
            }
        } catch (Throwable t) {
            throw new RpcException("Failed to subscribe service from redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    private void doNotify(String key) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(getSubscribed()).entrySet()) {
            doNotify(Collections.singletonList(key), entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    private void doNotify(Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<>();
        // 获取需要订阅的分类，例如 `providers`
        List<String> categories = Arrays.asList(url.getParameter(CATEGORY_KEY, new String[0]));
        String consumerService = url.getServiceInterface();
        // 循环分类层，例如：`/dubbo/服务名称/providers`
        for (String key : keys) {
            // 若服务不匹配，跳过
            if (!ANY_VALUE.equals(consumerService)) {
                String providerService = toServiceName(key);
                if (!providerService.equals(consumerService)) {
                    continue;
                }
            }
            // 若订阅的不包含该分类，返回
            String category = toCategoryName(key);
            if (!categories.contains(ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            List<URL> urls = new ArrayList<>();
            Set<URL> toDeleteExpireKeys = new HashSet<>(expireCache.keySet());
            // 从 redis 中获取这个键值
            Map<String, String> values = redisClient.hgetAll(key);
            if (CollectionUtils.isNotEmptyMap(values)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    long expire = Long.parseLong(entry.getValue());
                    if (!u.getParameter(DYNAMIC_KEY, true) // 非动态节点，因为动态节点，不受过期的限制
                            || expire >= now) // 未过期
                    {
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                            expireCache.put(u, expire);
                            toDeleteExpireKeys.remove(u);
                        }
                    }
                }
            }

            // 有已过期的 URL，则从本地缓存中移除
            // 这一步很关键，当服务端不往 Redis 重新设置每个服务的过期时间时，这个服务的 key 也就会自动到期
            // 那么在消费端就会从本地缓存中移除掉这些已下线的服务
            if (!toDeleteExpireKeys.isEmpty()) {
                for (URL u : toDeleteExpireKeys) {
                    expireCache.remove(u);
                }
            }
            // 如果没有对应的 URL 对象，例如 `providers` 表示没有提供者
            if (urls.isEmpty()) {
                // 创建一个 `empty` 协议的 URL 对象
                urls.add(URLBuilder.from(url)
                        .setProtocol(EMPTY_PROTOCOL)
                        .setAddress(ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(CATEGORY_KEY, category)
                        .build());
            }
            result.addAll(urls);

            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (CollectionUtils.isEmpty(result)) {
            return;
        }
        /**
         * 主动触发这个 NotifyListener 监听器，例如处理这个服务 `/providers` 节点下面的服务提供者们
         *
         * @see ServiceDiscoveryRegistryDirectory#notify(List)
         */
        for (NotifyListener listener : listeners) {
            notify(url, listener, result);
        }
    }

    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }

    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    private class NotifySub extends JedisPubSub {
        public NotifySub() {}

        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            if (msg.equals(REGISTER)
                    || msg.equals(UNREGISTER)) {
                try {
                    doNotify(key);
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    private class Notifier extends Thread {

        /**
         * 服务名，`/dubbo/服务名称`
         */
        private final String service;
        /**
         * 需要忽略连接的次数
         */
        private final AtomicInteger connectSkip = new AtomicInteger();
        /**
         * 已经忽略连接的次数
         */
        private final AtomicInteger connectSkipped = new AtomicInteger();

        /**
         * 是否首次
         */
        private volatile boolean first = true;
        /**
         * 是否允许中
         */
        private volatile boolean running = true;
        /**
         * 连接次数随机数
         */
        private volatile int connectRandom;

        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        private void resetSkip() {
            connectSkip.set(0);
            connectSkipped.set(0);
            connectRandom = 0;
        }

        private boolean isSkip() {
            // 获得需要忽略连接的总次数。如果超过 10，则加上一个 10 以内的随机数。
            // 思路是，连接失败的次数越多，每一轮加大需要忽略的总次数，并且带有一定的随机性。
            int skip = connectSkip.get(); // Growth of skipping times
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = ThreadLocalRandom.current().nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            // 自增忽略次数。若忽略次数不够，则继续忽略。
            if (connectSkipped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            // 增加需要忽略的次数
            connectSkip.incrementAndGet();
            // 重置已忽略次数和随机数
            connectSkipped.set(0);
            connectRandom = 0;
            return false;
        }

        @Override
        public void run() {
            // 没有被关闭，则一直运行
            while (running) {
                try {
                    if (!isSkip()) {
                        try {
                            if (!redisClient.isConnected()) {
                                continue;
                            }
                            try {
                                if (service.endsWith(ANY_VALUE)) {
                                    if (first) {
                                        first = false;
                                        Set<String> keys = redisClient.scan(service);
                                        if (CollectionUtils.isNotEmpty(keys)) {
                                            for (String s : keys) {
                                                doNotify(s);
                                            }
                                        }
                                        resetSkip();
                                    }
                                    redisClient.psubscribe(new NotifySub(), service);
                                } else {
                                    if (first) {
                                        first = false;
                                        doNotify(service);
                                        resetSkip();
                                    }
                                    // 订阅指定模式相匹配的的所有频道，也就是 `/dubbo/服务名称/*`
                                    // 保证能够读取到消息
                                    redisClient.psubscribe(new NotifySub(), service + PATH_SEPARATOR + ANY_VALUE); // blocking
                                }
                            } catch (Throwable t) { // Retry another server
                                logger.warn("Failed to subscribe service from redis registry. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
                                // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                sleep(reconnectPeriod);
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        public void shutdown() {
            try {
                running = false;
                redisClient.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}
