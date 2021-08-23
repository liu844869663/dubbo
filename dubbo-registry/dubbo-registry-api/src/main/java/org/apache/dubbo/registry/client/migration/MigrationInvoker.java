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
package org.apache.dubbo.registry.client.migration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RegistryConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory;
import org.apache.dubbo.registry.integration.DynamicDirectory;
import org.apache.dubbo.registry.integration.RegistryProtocol;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.interceptor.ConsumerContextClusterInterceptor;
import org.apache.dubbo.rpc.cluster.interceptor.ZoneAwareClusterInterceptor;
import org.apache.dubbo.rpc.cluster.support.FailoverClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.migration.MigrationClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.migration.MigrationRule;
import org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

public class MigrationInvoker<T> implements MigrationClusterInvoker<T> {
    private Logger logger = LoggerFactory.getLogger(MigrationInvoker.class);

    /**
     * 消费方的 URL 对象，包括注册中心和引用服务的信息
     * 例如：zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer
     * &dubbo=2.0.2&pid=8432&release=2.7.8&timestamp=1627287397524
     * &refer=引用服务的信息`
     */
    private URL url;
    /**
     * 消费方引用服务的 URL 对象
     * 例如：consumer://消费者主机IP:0/引用服务的接口名称?各种参数
     */
    private URL consumerUrl;
    /**
     * 集群容错对象
     */
    private Cluster cluster;
    /**
     * 注册中心对象
     */
    private Registry registry;
    /**
     * 引用服务的接口类型
     */
    private Class<T> type;
    /**
     * RegistryProtocol 注册中心协议对象
     */
    private RegistryProtocol registryProtocol;

    /**
     * 服务 ClusterInvoker 对象
     */
    private volatile ClusterInvoker<T> invoker;
    /**
     * 服务发现 ClusterInvoker 对象
     */
    private volatile ClusterInvoker<T> serviceDiscoveryInvoker;
    /**
     * 当前使用的 ClusterInvoker 对象，{@link #serviceDiscoveryInvoker} 或者 {@link #invoker}
     * 默认情况下，如果 {@link this#serviceDiscoveryInvoker} 内部字典维护的 RPC Invoker 数量大于 {@link this#invoker} 内部字典维护的 PRC Invoker 数量的 0.8 倍，则优先使用它
     */
    private volatile ClusterInvoker<T> currentAvailableInvoker;

    private MigrationRule rule;

    private boolean migrationMultiRegistry;

    public MigrationInvoker(RegistryProtocol registryProtocol,
                            Cluster cluster,
                            Registry registry,
                            Class<T> type,
                            URL url,
                            URL consumerUrl) {
        this(null, null, registryProtocol, cluster, registry, type, url, consumerUrl);
    }

    public MigrationInvoker(ClusterInvoker<T> invoker,
                            ClusterInvoker<T> serviceDiscoveryInvoker,
                            RegistryProtocol registryProtocol,
                            Cluster cluster,
                            Registry registry,
                            Class<T> type,
                            URL url,
                            URL consumerUrl) {
        this.invoker = invoker;
        this.serviceDiscoveryInvoker = serviceDiscoveryInvoker;
        this.registryProtocol = registryProtocol;
        this.cluster = cluster;
        this.registry = registry;
        this.type = type;
        this.url = url;
        this.consumerUrl = consumerUrl;
        this.migrationMultiRegistry = url.getParameter(RegistryConstants.MIGRATION_MULTI_REGISTRY, false);
    }

    public ClusterInvoker<T> getInvoker() {
        return invoker;
    }

    public void setInvoker(ClusterInvoker<T> invoker) {
        this.invoker = invoker;
    }

    public ClusterInvoker<T> getServiceDiscoveryInvoker() {
        return serviceDiscoveryInvoker;
    }

    public void setServiceDiscoveryInvoker(ClusterInvoker<T> serviceDiscoveryInvoker) {
        this.serviceDiscoveryInvoker = serviceDiscoveryInvoker;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public synchronized void migrateToServiceDiscoveryInvoker(boolean forceMigrate) {
        if (!forceMigrate) {
            /**
             * 刷新服务发现 ClusterInvoker 对象 {@link this#serviceDiscoveryInvoker}
             *
             * 将一系列服务提供者的 PRC Invoker 动态维护在 {@link ServiceDiscoveryRegistryDirectory} 字典中
             * 将其封装成一个 Invoker 对象
             */
            refreshServiceDiscoveryInvoker();
            /**
             * 刷新服务 ClusterInvoker 对象 {@link this#invoker}
             *
             * 过程和上一步差不多，区别在维护在 {@link org.apache.dubbo.registry.integration.RegistryDirectory} 字典中
             */
            refreshInterfaceInvoker();
            /**
             * 添加两个监听器，目的就是通过 {@link DefaultMigrationAddressComparator} 来决定使用上面那个 ClusterInvoker 对象
             * 默认情况下，如果 {@link this#serviceDiscoveryInvoker} 内部字典维护的 RPC Invoker 数量大于 {@link this#invoker} 内部字典维护的 PRC Invoker 数量的 0.8 倍，则优先使用它
             */
            setListener(invoker, () -> {
                this.compareAddresses(serviceDiscoveryInvoker, invoker);
            });
            setListener(serviceDiscoveryInvoker, () -> {
                this.compareAddresses(serviceDiscoveryInvoker, invoker);
            });
        } else {
            refreshServiceDiscoveryInvoker();
            setListener(serviceDiscoveryInvoker, () -> {
                this.destroyInterfaceInvoker(this.invoker);
            });
        }
    }

    @Override
    public void reRefer(URL newSubscribeUrl) {
        // update url to prepare for migration refresh
        this.url = url.addParameter(REFER_KEY, StringUtils.toQueryString(newSubscribeUrl.getParameters()));

        // re-subscribe immediately
        if (invoker != null && !invoker.isDestroyed()) {
            doReSubscribe(invoker, newSubscribeUrl);
        }
        if (serviceDiscoveryInvoker != null && !serviceDiscoveryInvoker.isDestroyed()) {
            doReSubscribe(serviceDiscoveryInvoker, newSubscribeUrl);
        }
    }

    private void doReSubscribe(ClusterInvoker<T> invoker, URL newSubscribeUrl) {
        DynamicDirectory<T> directory = (DynamicDirectory<T>) invoker.getDirectory();
        URL oldSubscribeUrl = directory.getRegisteredConsumerUrl();
        Registry registry = directory.getRegistry();
        registry.unregister(directory.getRegisteredConsumerUrl());
        directory.unSubscribe(RegistryProtocol.toSubscribeUrl(oldSubscribeUrl));
        if (directory.isShouldRegister()) {
            registry.register(directory.getRegisteredConsumerUrl());
            directory.setRegisteredConsumerUrl(newSubscribeUrl);
        }
        directory.buildRouterChain(newSubscribeUrl);
        directory.subscribe(RegistryProtocol.toSubscribeUrl(newSubscribeUrl));
    }

    @Override
    public synchronized void fallbackToInterfaceInvoker() {
        refreshInterfaceInvoker();
        setListener(invoker, () -> {
            this.destroyServiceDiscoveryInvoker(this.serviceDiscoveryInvoker);
        });
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        if (!checkInvokerAvailable(serviceDiscoveryInvoker)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Using interface addresses to handle invocation, interface " + type.getName() + ", total address size " + (invoker.getDirectory().getAllInvokers() == null ? "is null" : invoker.getDirectory().getAllInvokers().size()));
            }
            return invoker.invoke(invocation);
        }

        if (!checkInvokerAvailable(invoker)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Using instance addresses to handle invocation, interface " + type.getName() + ", total address size " + (serviceDiscoveryInvoker.getDirectory().getAllInvokers() == null ? " is null " : serviceDiscoveryInvoker.getDirectory().getAllInvokers().size()));
            }
            return serviceDiscoveryInvoker.invoke(invocation);
        }

        // 通过 ClusterInvoker 对象执行请求
        return currentAvailableInvoker.invoke(invocation);
    }

    @Override
    public boolean isAvailable() {
        return (invoker != null && invoker.isAvailable())
                || (serviceDiscoveryInvoker != null && serviceDiscoveryInvoker.isAvailable());
    }

    @Override
    public void destroy() {
        if (invoker != null) {
            invoker.destroy();
        }
        if (serviceDiscoveryInvoker != null) {
            serviceDiscoveryInvoker.destroy();
        }
    }

    @Override
    public URL getUrl() {
        if (invoker != null) {
            return invoker.getUrl();
        } else if (serviceDiscoveryInvoker != null) {
            return serviceDiscoveryInvoker.getUrl();
        }

        return consumerUrl;
    }

    @Override
    public URL getRegistryUrl() {
        if (invoker != null) {
            return invoker.getRegistryUrl();
        } else if (serviceDiscoveryInvoker != null) {
            serviceDiscoveryInvoker.getRegistryUrl();
        }
        return url;
    }

    @Override
    public Directory<T> getDirectory() {
        if (invoker != null) {
            return invoker.getDirectory();
        } else if (serviceDiscoveryInvoker != null) {
            return serviceDiscoveryInvoker.getDirectory();
        }
        return null;
    }

    @Override
    public boolean isDestroyed() {
        return (invoker == null || invoker.isDestroyed())
                && (serviceDiscoveryInvoker == null || serviceDiscoveryInvoker.isDestroyed());
    }


    @Override
    public AtomicBoolean invokersChanged() {
        return invokersChanged;
    }

    private volatile AtomicBoolean invokersChanged = new AtomicBoolean(true);

    private synchronized void compareAddresses(ClusterInvoker<T> serviceDiscoveryInvoker, ClusterInvoker<T> invoker) {
        this.invokersChanged.set(true);
        if (logger.isDebugEnabled()) {
            logger.debug(invoker.getDirectory().getAllInvokers() == null ? "null" : invoker.getDirectory().getAllInvokers().size() + "");
        }

        Set<MigrationAddressComparator> detectors = ExtensionLoader.getExtensionLoader(MigrationAddressComparator.class).getSupportedExtensionInstances();
        if (detectors != null && detectors.stream().allMatch(migrationDetector -> migrationDetector.shouldMigrate(serviceDiscoveryInvoker, invoker))) {
            discardInterfaceInvokerAddress(invoker);
        } else {
            discardServiceDiscoveryInvokerAddress(serviceDiscoveryInvoker);
        }
    }

    private synchronized void setAddressChanged() {
        this.invokersChanged.set(true);
    }

    @Override
    public synchronized void destroyServiceDiscoveryInvoker(ClusterInvoker<?> serviceDiscoveryInvoker) {
        if (checkInvokerAvailable(this.invoker)) {
            this.currentAvailableInvoker = this.invoker;
        }
        if (serviceDiscoveryInvoker != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Destroying instance address invokers, will not listen for address changes until re-subscribed, " + type.getName());
            }
            serviceDiscoveryInvoker.destroy();
        }
    }

    @Override
    public synchronized void discardServiceDiscoveryInvokerAddress(ClusterInvoker<?> serviceDiscoveryInvoker) {
        if (checkInvokerAvailable(this.invoker)) {
            this.currentAvailableInvoker = this.invoker;
        }
        if (serviceDiscoveryInvoker != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Discarding instance addresses, total size " + (null == serviceDiscoveryInvoker.getDirectory().getAllInvokers() ? "null" : serviceDiscoveryInvoker.getDirectory().getAllInvokers().size()));
            }
            serviceDiscoveryInvoker.getDirectory().discordAddresses();
        }
    }

    @Override
    public synchronized void refreshServiceDiscoveryInvoker() {
        clearListener(serviceDiscoveryInvoker);
        if (needRefresh(serviceDiscoveryInvoker)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Re-subscribing instance addresses, current interface " + type.getName());
            }
            /**
             * 1. 创建一个 ServiceDiscoveryRegistryDirectory 对象
             * 2. 对这个 `directory` 进行相关配置，向注册中心订阅服务提供者信息，监听着它们的变化，动态维护着一些 PRC Invoker 对象
             * 3. 借助 Cluster 对象将这个 `directory` 封装成一个 Invoker 对象，例如 {@link FailoverClusterInvoker}
             * 4. 构建一条 Invoker 链，在其前面添加一系列 ClusterInterceptor 拦截器
             *    默认会有 {@link ConsumerContextClusterInterceptor} 拦截器
             *    `cluster=zone-aware` 会有 {@link ZoneAwareClusterInterceptor} 拦截器
             * 5. 将这个 Invoker 对象封装成 {@link MockClusterInvoker} 对象
             *
             * 总结：将一系列服务提供者的 PRC Invoker 封装成一个 Invoker 对象
             */
            serviceDiscoveryInvoker = registryProtocol.getServiceDiscoveryInvoker(cluster, registry, type, url);

            if (migrationMultiRegistry) {
                setListener(serviceDiscoveryInvoker, () -> {
                    this.setAddressChanged();
                });
            }
        }
    }

    private void clearListener(ClusterInvoker<T> invoker) {
        if (migrationMultiRegistry) {
            return;
        }

        if (invoker == null) {
            return;
        }
        DynamicDirectory<T> directory = (DynamicDirectory<T>) invoker.getDirectory();
        directory.setInvokersChangedListener(null);
    }

    private void setListener(ClusterInvoker<T> invoker, InvokersChangedListener listener) {
        if (invoker == null) {
            return;
        }
        DynamicDirectory<T> directory = (DynamicDirectory<T>) invoker.getDirectory();
        directory.setInvokersChangedListener(listener);
    }

    @Override
    public synchronized void refreshInterfaceInvoker() {
        clearListener(invoker);
        if (needRefresh(invoker)) {
            // FIXME invoker.destroy();
            if (logger.isDebugEnabled()) {
                logger.debug("Re-subscribing interface addresses for interface " + type.getName());
            }
            invoker = registryProtocol.getInvoker(cluster, registry, type, url);

            if (migrationMultiRegistry) {
                setListener(serviceDiscoveryInvoker, () -> {
                    this.setAddressChanged();
                });
            }
        }
    }

    @Override
    public synchronized void destroyInterfaceInvoker(ClusterInvoker<T> invoker) {
        if (checkInvokerAvailable(this.serviceDiscoveryInvoker)) {
            this.currentAvailableInvoker = this.serviceDiscoveryInvoker;
        }
        if (invoker != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Destroying interface address invokers, will not listen for address changes until re-subscribed, " + type.getName());
            }
            invoker.destroy();
        }
    }

    @Override
    public synchronized void discardInterfaceInvokerAddress(ClusterInvoker<T> invoker) {
        if (this.serviceDiscoveryInvoker != null) {
            this.currentAvailableInvoker = this.serviceDiscoveryInvoker;
        }
        if (invoker != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Discarding interface addresses, total address size " + (null == invoker.getDirectory().getAllInvokers() ? "null" : invoker.getDirectory().getAllInvokers().size()));
            }
            invoker.getDirectory().discordAddresses();
        }
    }

    private boolean needRefresh(ClusterInvoker<T> invoker) {
        return invoker == null || invoker.isDestroyed();
    }

    public boolean checkInvokerAvailable(ClusterInvoker<T> invoker) {
        return invoker != null && !invoker.isDestroyed() && invoker.isAvailable();
    }

    @Override
    public boolean isServiceInvoker() {
        return false;
    }

    @Override
    public MigrationRule getMigrationRule() {
        return rule;
    }

    @Override
    public void setMigrationRule(MigrationRule rule) {
        this.rule = rule;
    }

    @Override
    public boolean isMigrationMultiRegistry() {
        return migrationMultiRegistry;
    }

}
