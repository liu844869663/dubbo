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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * 注册中心工厂对象的抽象类，提供通用的方法
 * <p>
 * AbstractRegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.RegistryFactory
 */
public abstract class AbstractRegistryFactory implements RegistryFactory {

    // Log output
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    // The lock for the acquisition process of the registry
    protected static final ReentrantLock LOCK = new ReentrantLock();

    /**
     * 缓存注册中心对象
     * key：{@link URL#toServiceStringWithoutResolving}
     * 例如 `zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService`
     *
     * Registry Collection Map<RegistryAddress, Registry>
     */
    protected static final Map<String, Registry> REGISTRIES = new HashMap<>();

    /**
     * 标记是否所有的注册中心对象都被销毁
     */
    private static final AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * Get all registries
     *
     * @return all registries
     */
    public static Collection<Registry> getRegistries() {
        return Collections.unmodifiableCollection(new LinkedList<>(REGISTRIES.values()));
    }

    public static Registry getRegistry(String key) {
        return REGISTRIES.get(key);
    }

    public static List<ServiceDiscovery> getServiceDiscoveries() {
        return AbstractRegistryFactory.getRegistries()
                .stream()
                .filter(registry -> registry instanceof ServiceDiscoveryRegistry)
                .map(registry -> (ServiceDiscoveryRegistry) registry)
                .map(ServiceDiscoveryRegistry::getServiceDiscovery)
                .collect(Collectors.toList());
    }

    /**
     * Close all created registries
     */
    public static void destroyAll() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Close all registries " + getRegistries());
        }
        // Lock up the registry shutdown process
        LOCK.lock();
        try {
            for (Registry registry : getRegistries()) {
                try {
                    registry.destroy();
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            REGISTRIES.clear();
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    private Registry getDefaultNopRegistryIfDestroyed() {
        if (destroyed.get()) {
            LOGGER.warn("All registry instances have been destroyed, failed to fetch any instance. " +
                    "Usually, this means no need to try to do unnecessary redundant resource clearance, all registries has been taken care of.");
            return DEFAULT_NOP_REGISTRY;
        }
        return null;
    }

    public static Registry getDefaultNopRegistryIfNotSupportServiceDiscovery() {
        return DEFAULT_NOP_REGISTRY;
    }

    @Override
    public Registry getRegistry(URL url) {

        // 如果所有的注册中心都被销毁了，这里会返回一个默认的 Registry 空实现对象
        Registry defaultNopRegistry = getDefaultNopRegistryIfDestroyed();
        if (null != defaultNopRegistry) {
            return defaultNopRegistry;
        }

        // 重新创建一个 URL 对象
        url = URLBuilder.from(url)
                // 路径为 RegistryService 类名（原先 url 本身就是）
                .setPath(RegistryService.class.getName())
                // 添加 `interface` 接口参数为 RegistryService 类名
                .addParameter(INTERFACE_KEY, RegistryService.class.getName())
                // 移除 `export` 参数（服务提供者的信息）
                .removeParameters(EXPORT_KEY, REFER_KEY)
                .build();
        // 获取注册中心的缓存键，`{protocol}://{username}:{password}@{host}:{port}/{group}/{interfaceName}:{version}`
        // 例如 `zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService`
        String key = createRegistryCacheKey(url);
        // Lock the registry access process to ensure a single instance of the registry
        // 加锁
        LOCK.lock();
        try {
            // double check
            // fix https://github.com/apache/dubbo/issues/7265.
            // 再次确认注册中心是否都被销毁
            defaultNopRegistry = getDefaultNopRegistryIfDestroyed();
            if (null != defaultNopRegistry) {
                return defaultNopRegistry;
            }

            // 从缓存中获取对应的 Registry 注册中心对象
            Registry registry = REGISTRIES.get(key);
            if (registry != null) {
                return registry;
            }
            /**
             * 创建一个 Registry 注册中心对象，抽象方法
             * 不同协议对应不同的子类，创建不同的 Registry 注册中心对象
             * 例如 `zookeeper` 协议对应 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistryFactory}，
             * 创建 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistry} 注册中心对象
             */
            //create registry by spi/ioc
            registry = createRegistry(url);
            if (registry == null) {
                throw new IllegalStateException("Can not create registry " + url);
            }
            // 缓存起来
            REGISTRIES.put(key, registry);
            return registry;
        } finally {
            // Release the lock
            // 释放锁
            LOCK.unlock();
        }
    }

    /**
     * Create the key for the registries cache.
     * This method may be override by the sub-class.
     *
     * @param url the registration {@link URL url}
     * @return non-null
     */
    protected String createRegistryCacheKey(URL url) {
        return url.toServiceStringWithoutResolving();
    }

    protected abstract Registry createRegistry(URL url);


    private static Registry DEFAULT_NOP_REGISTRY = new Registry() {
        @Override
        public URL getUrl() {
            return null;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void destroy() {

        }

        @Override
        public void register(URL url) {

        }

        @Override
        public void unregister(URL url) {

        }

        @Override
        public void subscribe(URL url, NotifyListener listener) {

        }

        @Override
        public void unsubscribe(URL url, NotifyListener listener) {

        }

        @Override
        public List<URL> lookup(URL url) {
            return null;
        }
    };

    public static void removeDestroyedRegistry(Registry toRm) {
        LOCK.lock();
        try {
            REGISTRIES.entrySet().removeIf(entry -> entry.getValue().equals(toRm));
        } finally {
            LOCK.unlock();
        }
    }

    // for unit test
    @Deprecated
    public static void reset() {
        REGISTRIES.clear();
        destroyed.set(false);
    }

}
