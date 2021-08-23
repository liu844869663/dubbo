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
package org.apache.dubbo.registry.zookeeper;

import org.apache.curator.framework.imps.CuratorFrameworkImpl;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.URLStrParser;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SEPARATOR_ENCODED;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;

/**
 * ZookeeperRegistry
 */
public class ZookeeperRegistry extends FailbackRegistry {

    private static final String DEFAULT_ROOT = "dubbo";

    /**
     * 注册中心的根路径，也就是服务的分组名称，默认为 `/dubbo`
     */
    private final String root;

    private final Set<String> anyServices = new ConcurrentHashSet<>();

    /**
     * 保存订阅 URL 信息（消费方）和对应的回调监听器、子节点监听器
     * key：服务消费方 URL 信息
     * value：对应的回调监听器和子节点监听器们
     */
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    /**
     * zk 客户端 {@link CuratorZookeeperClient}
     */
    private final ZookeeperClient zkClient;

    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        // 设置连接 zk 失败重试策略，是否生成本地缓存文件
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 获取分组名称，默认为 `dubbo`
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        // 分组名称前面添加 /，例如 `/dubbo`
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        // 设置根路径
        this.root = group;
        /**
         * 入参 `zookeeperTransporter` 是 ZookeeperTransporter 扩展点自适应对象 {@link org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter$Adaptive}
         *
         * 默认使用 curator 客户端，也就是由 {@link CuratorZookeeperTransporter} 对象创建一个 {@link CuratorZookeeperClient} zk 客户端
         * 创建它的时候会通过 curator 构建一个 {@link CuratorFrameworkImpl} 对象，并启动它，直至启动成功，有与 zk 可用的连接（或者 5s 超时），否则抛出异常
         *
         * 同时会设置 zk 连接状态监听器，最终交由 {@link CuratorZookeeperClient#stateChanged(int)} 方法来处理
         * 也就是由它内部的 StateListener 监听器来处理，下面会添加一个
         *
         * 注意：在 2.7.x 的版本中已经移除了 zkclient 的实现，如果要使用 zkclient 客户端，需要自行拓展
         */
        zkClient = zookeeperTransporter.connect(url);
        // 添加 StateListener 状态监听器，处理 zk 连接状态的变更
        zkClient.addStateListener((state) -> {
            if (state == StateListener.RECONNECTED) { // 重连
                logger.warn("Trying to fetch the latest urls, in case there're provider changes during connection loss.\n" +
                        " Since ephemeral ZNode will not get deleted for a connection lose, " +
                        "there's no need to re-register url of this instance.");
                // 重新订阅
                ZookeeperRegistry.this.fetchLatestAddresses();
            } else if (state == StateListener.NEW_SESSION_CREATED) { // 新的 session 被创建
                logger.warn("Trying to re-register urls and re-subscribe listeners of this instance to registry...");
                try {
                    // 重新注册所有服务提供者、重新订阅
                    ZookeeperRegistry.this.recover();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            } else if (state == StateListener.SESSION_LOST) { // session 丢失
                logger.warn("Url of this instance will be deleted from registry soon. " +
                        "Dubbo client will try to re-register once a new session is created.");
            } else if (state == StateListener.SUSPENDED) {

            } else if (state == StateListener.CONNECTED) {

            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doRegister(URL url) {
        try {
            /**
             * 根据 `dynamic` 参数觉得是否创建临时节点，默认为 true，当与 zk 断开连接时，这个节点则会消失
             * 1. 根据 URL 生成一个需要创建的路径，`/分组名称/服务名称/providers/服务提供者的所有信息`，分组名称默认为 `dubbo`
             * 2. 通过 zk 客户端创建这个路径，除了最后的 `服务提供者的所有信息` 节点是临时节点，其他的都是永久节点，不会消失
             */
            zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            // 接口名称为 `*`，表示订阅所有服务，例如监控中心的订阅
            if (ANY_VALUE.equals(url.getServiceInterface())) {
                String root = toRootPath();
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());
                ChildListener zkListener = listeners.computeIfAbsent(listener, k -> (parentPath, currentChilds) -> {
                    for (String child : currentChilds) {
                        child = URL.decode(child);
                        if (!anyServices.contains(child)) {
                            anyServices.add(child);
                            subscribe(url.setPath(child).addParameters(INTERFACE_KEY, child,
                                    Constants.CHECK_KEY, String.valueOf(false)), k);
                        }
                    }
                });
                zkClient.create(root, false);
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (CollectionUtils.isNotEmpty(services)) {
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        subscribe(url.setPath(service).addParameters(INTERFACE_KEY, service,
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else { // 否则，订阅某个服务
                // 创建一个栅栏，数量设置为 1
                CountDownLatch latch = new CountDownLatch(1);
                List<URL> urls = new ArrayList<>();
                // 获取分类路径，循环处理，例如 `/dubbo/服务名称/providers`
                for (String path : toCategoriesPath(url)) {
                    // 从缓存中获取这个订阅的 URL 对应的 NotifyListener 和 ChildListener
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());
                    /**
                     * 从缓存中获取这个 NotifyListener 对应的 ChildListener 子节点监听器
                     * 没有的话创建一个 {@link RegistryChildListenerImpl} 对象作为子节点监听器，并放入缓存中
                     * 实际会调用 {@link this#notify(URL, NotifyListener, List)} 进行处理
                     */
                    ChildListener zkListener = listeners.computeIfAbsent(listener, k -> new RegistryChildListenerImpl(url, k, latch));
                    if (zkListener instanceof RegistryChildListenerImpl) {
                        ((RegistryChildListenerImpl) zkListener).setLatch(latch);
                    }
                    // 创建这个节点，已存在则不会创建
                    zkClient.create(path, false);
                    // 对这个节点路径设置对应的子节点监听器，并返回目前已有的子节点列表
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                        urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                /**
                 * 主动触发这个 NotifyListener 监听器，例如处理这个服务 `/providers` 节点下面的服务提供者们
                 *
                 * @see ServiceDiscoveryRegistryDirectory#notify(List)
                 */
                notify(url, listener, urls);
                // tells the listener to run only after the sync notification of main thread finishes.
                // 将这个栅栏的数量减一
                latch.countDown();
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.remove(listener);
            if (zkListener != null) {
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }

            if(listeners.isEmpty()){
                zkListeners.remove(url);
            }
        }
    }

    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<>();
            // 获取分类路径，循环处理，例如 `/dubbo/服务名称/providers`
            for (String path : toCategoriesPath(url)) {
                // 获取该路径下的所有子节点
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            // 将该路径下的子节点转换成 URL 对象
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    private String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }

    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    private String[] toCategoriesPath(URL url) {
        String[] categories;
        // 获取 URL 中的 `category` 参数，默认为 `providers`
        if (ANY_VALUE.equals(url.getParameter(CATEGORY_KEY))) {
            categories = new String[]{PROVIDERS_CATEGORY, CONSUMERS_CATEGORY, ROUTERS_CATEGORY, CONFIGURATORS_CATEGORY};
        } else {
            categories = url.getParameter(CATEGORY_KEY, new String[]{DEFAULT_CATEGORY});
        }
        // 获取分类路径，例如 `/dubbo/服务名称/providers`
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            paths[i] = toServicePath(url) + PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    private String toUrlPath(URL url) {
        // 将 URL 对象转换成路径，`/分组名称/服务名称/providers/服务提供者的所有信息`
        // 分组名称默认为 `dubbo`
        return toCategoryPath(url) + PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<>();
        // 如果服务提供者不为空，则遍历处理
        if (CollectionUtils.isNotEmpty(providers)) {
            for (String provider : providers) {
                // 有指定的协议
                if (provider.contains(PROTOCOL_SEPARATOR_ENCODED)) {
                    // 将这个服务提供者转换成 URL 对象
                    URL url = URLStrParser.parseEncodedStr(provider);
                    // 判断服务消费者与服务提供者是否匹配
                    // 例如服务提供者要启用，分组、版本都要相同
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        if (CollectionUtils.isEmpty(urls)) {
            int i = path.lastIndexOf(PATH_SEPARATOR);
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = URLBuilder.from(consumer)
                    .setProtocol(EMPTY_PROTOCOL)
                    .addParameter(CATEGORY_KEY, category)
                    .build();
            urls.add(empty);
        }
        return urls;
    }

    /**
     * 当 zk 连接从连接丢失中恢复时，它需要获取最新的提供者列表。这里重新订阅提供者的信息。
     *
     * When zookeeper connection recovered from a connection loss, it need to fetch the latest provider list.
     * re-register watcher is only a side effect and is not mandate.
     */
    private void fetchLatestAddresses() {
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Fetching the latest urls of " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    // 移除并失败时重新订阅（或取消订阅）的任务
                    removeFailedSubscribed(url, listener);
                    // 添加失败时重新订阅
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    private class RegistryChildListenerImpl implements ChildListener {

        /**
         * 消费方 URL 信息
         */
        private URL url;

        /**
         * 回调监听器
         * @see org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory
         */
        private NotifyListener listener;

        /**
         * 栅栏
         */
        private volatile CountDownLatch latch;

        RegistryChildListenerImpl(URL url, NotifyListener listener, CountDownLatch latch) {
            this.url = url;
            this.listener = listener;
            this.latch = latch;
        }

        void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        /**
         * 对子节点的变动进行处理
         *
         * @see CuratorZookeeperClient.CuratorWatcherImpl#process
         * @param path     节点
         * @param children 最新的子节点列表
         */
        @Override
        public void childChanged(String path, List<String> children) {
            try {
                // 等待栅栏的数量为 0，才继续往下执行
                // 目的是让主线程处理完回调后，这里才进行
                latch.await();
            } catch (InterruptedException e) {
                logger.warn("Zookeeper children listener thread was interrupted unexpectedly, may cause race condition with the main thread.");
            }
            /**
             * 先根据 `url`（消费方 URL 信息）和子节点解析出匹配的 URL 对象
             * 例如是 providers 子节点，则解析出和消费方匹配的服务提供者信息，当然，如果 `children` 为空，这里也返回空集合
             * 然后让 `listener` 进行回调处理，进入 {@link ServiceDiscoveryRegistryDirectory#notify(List)} 方法
             */
            ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, path, children));
        }
    }

}
