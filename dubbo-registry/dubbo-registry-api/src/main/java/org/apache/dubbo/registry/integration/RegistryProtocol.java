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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.factory.SpiExtensionFactory;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory;
import org.apache.dubbo.registry.client.migration.MigrationInvoker;
import org.apache.dubbo.registry.client.migration.MigrationRuleHandler;
import org.apache.dubbo.registry.client.migration.MigrationRuleListener;
import org.apache.dubbo.registry.client.migration.ServiceDiscoveryMigrationInvoker;
import org.apache.dubbo.registry.retry.ReExportTask;
import org.apache.dubbo.registry.support.SkipFailbackWrapperException;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;
import org.apache.dubbo.rpc.cluster.interceptor.ConsumerContextClusterInterceptor;
import org.apache.dubbo.rpc.cluster.interceptor.ZoneAwareClusterInterceptor;
import org.apache.dubbo.rpc.cluster.support.FailoverCluster;
import org.apache.dubbo.rpc.cluster.support.FailoverClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.MergeableCluster;
import org.apache.dubbo.rpc.cluster.support.migration.MigrationClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.protocol.AsyncToSyncInvoker;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.constants.CommonConstants.*;
import static org.apache.dubbo.common.constants.FilterConstants.VALIDATION_KEY;
import static org.apache.dubbo.common.constants.QosConstants.*;
import static org.apache.dubbo.common.constants.RegistryConstants.*;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.*;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;
import static org.apache.dubbo.remoting.Constants.*;
import static org.apache.dubbo.rpc.Constants.INTERFACES;
import static org.apache.dubbo.rpc.Constants.*;
import static org.apache.dubbo.rpc.cluster.Constants.*;

/**
 * TODO, replace RegistryProtocol completely in the future.
 */
public class RegistryProtocol implements Protocol {
    public static final String[] DEFAULT_REGISTER_PROVIDER_KEYS = {
            APPLICATION_KEY, CODEC_KEY, EXCHANGER_KEY, SERIALIZATION_KEY, CLUSTER_KEY, CONNECTIONS_KEY, DEPRECATED_KEY,
            GROUP_KEY, LOADBALANCE_KEY, MOCK_KEY, PATH_KEY, TIMEOUT_KEY, TOKEN_KEY, VERSION_KEY, WARMUP_KEY,
            WEIGHT_KEY, TIMESTAMP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    public static final String[] DEFAULT_REGISTER_CONSUMER_KEYS = {
            APPLICATION_KEY, VERSION_KEY, GROUP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    private static final String REGISTRY_PROTOCOL_LISTENER_KEY = "registry.protocol.listener";
    private static final int DEFAULT_PORT = 9090;

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryProtocol.class);
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<>();
    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    private final ProviderConfigurationListener providerConfigurationListener = new ProviderConfigurationListener();

    /**
     * 绑定关系集合，key：Dubbo 服务对应 URL 生成的 key
     */
    // To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    // 用于解决rmi重复暴露端口冲突的问题，已经暴露过的服务不再重新暴露
    // providerurl <--> exporter
    private final ConcurrentMap<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<>();
    /**
     * Protocol 扩展点实现类的自适应对象
     *
     * 在通过 Dubbo SPI 获取当前 RegistryProtocol 扩展点实现类时，
     * 会通过 {@link SpiExtensionFactory} 获取 {@link org.apache.dubbo.rpc.Protocol$Adaptive} 设置到该属性中
     */
    protected Protocol protocol;
    /**
     * RegistryFactory 扩展点实现类的自适应对象
     *
     * 在通过 Dubbo SPI 获取当前 RegistryProtocol 扩展点实现类时，
     * 会通过 {@link SpiExtensionFactory} 获取 {@link org.apache.dubbo.registry.RegistryFactory$Adaptive} 设置到该属性中
     */
    protected RegistryFactory registryFactory;
    /**
     * ProxyFactory 扩展点实现类的自适应对象
     *
     * 在通过 Dubbo SPI 获取当前 RegistryProtocol 扩展点实现类时，
     * 会通过 {@link SpiExtensionFactory} 获取 {@link org.apache.dubbo.rpc.ProxyFactory$Adaptive} 设置到该属性中
     */
    protected ProxyFactory proxyFactory;

    private ConcurrentMap<URL, ReExportTask> reExportFailedTasks = new ConcurrentHashMap<>();
    private HashedWheelTimer retryTimer =
            new HashedWheelTimer(new NamedThreadFactory("DubboReexportTimer", true), DEFAULT_REGISTRY_RETRY_PERIOD, TimeUnit.MILLISECONDS,
                    128);

    // get the parameters which shouldn't been displayed in url string(Starting with .)
    private static String[] getHiddenKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (CollectionUtils.isNotEmptyMap(params)) {
            return params.keySet().stream()
                    .filter(k -> k.startsWith(HIDDEN_KEY_PREFIX))
                    .toArray(String[]::new);
        } else {
            return new String[0];
        }
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    private void registerStatedUrl(URL registryUrl, URL registeredProviderUrl, boolean registered) {
        ProviderModel model = ApplicationModel.getProviderModel(registeredProviderUrl.getServiceKey());
        model.addStatedUrl(new ProviderModel.RegisterStatedURL(
                registeredProviderUrl,
                registryUrl,
                registered));
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        /**
         * 获取 Registry 注册中心对应的 URL 对象
         * 在子类 {@link InterfaceCompatibleRegistryProtocol} 中
         * 把协议设置为 `registry` 的参数值，对应注册中心的名称，例如协议设置为 `zookeeper`
         * 没有这个参数则默认设置为 `dubbo` 协议，例如直连提供者
         * 同时移除 `registry` 参数
         */
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        // 获取 Dubbo 服务对应的 URL 对象
        URL providerUrl = getProviderUrl(originInvoker);

        // Subscribe the override data
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call
        //  the same service. Because the subscribed is cached key with the name of the service, it causes the
        //  subscription information to cover.
        // 将 Dubbo 服务对应的 URL 对象的 `protocol` 设置为 `provider`，并添加 `category=configurators&check=false` 两个参数，暂时忽略
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        /**
         * 创建 Export 对象，例如 `dubbo` 协议会由 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol} 创建一个 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboExporter} 对象
         * 同时会本地启动服务，例如 {@link org.apache.dubbo.remoting.transport.netty4.NettyServer} 会开启你的 Dubbo 端口的监听，处理远程发过来的请求
         * 通过找到本地的 Invoker 对象执行 Dubbo 服务方法，然后返回执行结果
         * 注意，这里还没向注册中心注册服务
         */
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        /**
         * Dubbo SPI 通过 RegistryFactory 扩展点自适应对象根据注册中心协议获取对应的 RegistryFactory 扩展点实现类
         * 例如 `zookeeper` 协议对应 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistryFactory}
         * 会创建一个 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistry} 注册中心对象
         *
         * 在创建注册中心对象的时候，会通过 {@link org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperTransporter} 对象
         * 创建一个 {@link org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient} zk 客户端
         * 创建它的时候会通过 curator 构建一个 CuratorFrameworkImpl 对象，并启动它，直至启动成功，有与 zk 可用的连接（或者 5s 超时），否则抛出异常
         * 同时会设置 StateListener zk 连接状态监听器，当重连或者新的 session 创建时会有相关处理
         */
        final Registry registry = getRegistry(originInvoker);

        // 获取这个 Dubbo 服务需要向注册中心注册的 URL 对象
        final URL registeredProviderUrl = getUrlToRegistry(providerUrl, registryUrl);

        // decide if we need to delay publish
        // 是否需要向注册中心注册 Dubbo 服务，默认需要
        boolean register = providerUrl.getParameter(REGISTER_KEY, true);
        if (register) {
            /*
             * 向注册中心注册这个服务
             * 例如 zk 会通过 zk 客户端创建一个 `/分组名称(默认为dubbo)/服务名称/providers/服务提供者的所有信息` 路径
             * 除了最后的 `服务提供者的所有信息` 节点是临时节点，其他的都是永久节点，不会消失
             */
            registry.register(registeredProviderUrl);
        }

        // register stated url on provider model
        /**
         * 将这三个参数（注册中心 URL、服务 URL、是否注册）封装成 {@link ProviderModel.RegisterStatedURL} 对象
         * 从 {@link ServiceRepository} 仓库中找到这个服务对应的 {@link ProviderModel} 对象，将上面信息保存至其中
         */
        registerStatedUrl(registryUrl, registeredProviderUrl, register);


        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);

        // Deprecated! Subscribe to override rules in 2.6.x or before.
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        /**
         * Dubbo SPI 获取 {@link RegistryProtocolListener} 监听器们
         * 内部只有一个 {@link MigrationRuleListener} 对象，但是{@link MigrationRuleListener#onExport} 是一个空方法
         */
        notifyExport(exporter);
        //Ensure that a new exporter instance is returned every time export
        // 将 `exporter` 包装成 DestroyableExporter 返回
        return new DestroyableExporter<>(exporter);
    }

    private <T> void notifyExport(ExporterChangeableWrapper<T> exporter) {
        List<RegistryProtocolListener> listeners = ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getActivateExtension(exporter.getOriginInvoker().getUrl(), REGISTRY_PROTOCOL_LISTENER_KEY);
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (RegistryProtocolListener listener : listeners) {
                listener.onExport(this, exporter);
            }
        }
    }

    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);
        ServiceConfigurationListener serviceConfigurationListener = new ServiceConfigurationListener(providerUrl, listener);
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
        // 获得在 `bounds` 中的缓存 key
        String key = getCacheKey(originInvoker);

        // 从缓存中获取 ExporterChangeableWrapper 对象，没有的话创建一个
        return (ExporterChangeableWrapper<T>) bounds.computeIfAbsent(key, s -> {
            // 再次创建一个 Invoker 代理对象，封装了这个 Invoker 和 URL
            Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);
            /**
             * 1. 首先通过 `protocol` 暴露这个 Invoker 对象，也就是暴露这个 Dubbo 服务，返回一个 Exporter 对象
             * 例如 DubboProtocol 返回 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboExporter} 对象
             * 同时会启动通信服务器，例如 {@link org.apache.dubbo.remoting.transport.netty4.NettyServer} 服务器，监听某个端口，处理远程发过来的请求
             *
             * 2. 将得到的 Exporter 和原始的 Invoker 对象封装成一个 ExporterChangeableWrapper 对象，这样就形成了绑定的关系
             */
            return new ExporterChangeableWrapper<>((Exporter<T>) protocol.export(invokerDelegate), originInvoker);
        });
    }

    public <T> void reExport(Exporter<T> exporter, URL newInvokerUrl) {
        if (exporter instanceof ExporterChangeableWrapper) {
            ExporterChangeableWrapper<T> exporterWrapper = (ExporterChangeableWrapper<T>) exporter;
            Invoker<T> originInvoker = exporterWrapper.getOriginInvoker();
            reExport(originInvoker, newInvokerUrl);
        }
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     * @param <T>
     */
    @SuppressWarnings("unchecked")
    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        URL registeredUrl = exporter.getRegisterUrl();

        URL registryUrl = getRegistryUrl(originInvoker);
        URL newProviderUrl = getUrlToRegistry(newInvokerUrl, registryUrl);

        // update local exporter
        Invoker<T> invokerDelegate = new InvokerDelegate<T>(originInvoker, newInvokerUrl);
        exporter.setExporter(protocol.export(invokerDelegate));

        // update registry
        if (!newProviderUrl.equals(registeredUrl)) {
            try {
                doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl);
            } catch (Exception e) {
                ReExportTask oldTask = reExportFailedTasks.get(registeredUrl);
                if (oldTask != null) {
                    return;
                }
                ReExportTask task = new ReExportTask(
                        () -> doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl),
                        registeredUrl,
                        null
                );
                oldTask = reExportFailedTasks.putIfAbsent(registeredUrl, task);
                if (oldTask == null) {
                    // never has a retry task. then start a new task for retry.
                    retryTimer.newTimeout(task, registryUrl.getParameter(REGISTRY_RETRY_PERIOD_KEY, DEFAULT_REGISTRY_RETRY_PERIOD),
                            TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private <T> void doReExport(final Invoker<T> originInvoker, ExporterChangeableWrapper<T> exporter,
                                URL registryUrl, URL oldProviderUrl, URL newProviderUrl) {
        if (getProviderUrl(originInvoker).getParameter(REGISTER_KEY, true)) {
            Registry registry = null;
            try {
                registry = getRegistry(originInvoker);
            } catch (Exception e) {
                throw new SkipFailbackWrapperException(e);
            }

            LOGGER.info("Try to unregister old url: " + oldProviderUrl);
            registry.reExportUnregister(oldProviderUrl);

            LOGGER.info("Try to register new url: " + newProviderUrl);
            registry.reExportRegister(newProviderUrl);
        }
        try {
            ProviderModel.RegisterStatedURL statedUrl = getStatedUrl(registryUrl, newProviderUrl);
            statedUrl.setProviderUrl(newProviderUrl);
            exporter.setRegisterUrl(newProviderUrl);
        } catch (Exception e) {
            throw new SkipFailbackWrapperException(e);
        }
    }

    private ProviderModel.RegisterStatedURL getStatedUrl(URL registryUrl, URL providerUrl) {
        ProviderModel providerModel = ApplicationModel.getServiceRepository()
                .lookupExportedService(providerUrl.getServiceKey());

        List<ProviderModel.RegisterStatedURL> statedUrls = providerModel.getStatedUrl();
        return statedUrls.stream()
                .filter(u -> u.getRegistryUrl().equals(registryUrl)
                        && u.getProviderUrl().getProtocol().equals(providerUrl.getProtocol()))
                .findFirst().orElseThrow(() -> new IllegalStateException("There should have at least one registered url."));
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    protected Registry getRegistry(final Invoker<?> originInvoker) {
        /**
         * 获取 Registry 注册中心对应的 URL 对象
         * 在子类 {@link InterfaceCompatibleRegistryProtocol} 中
         * 会把协议设置为 `registry` 参数值，对应注册中心的名称，例如协议设置为 `zookeeper`
         * 同时移除 `registry` 参数
         */
        URL registryUrl = getRegistryUrl(originInvoker);
        // 根据注册中心的 URL 对象获取一个注册中心对象
        return getRegistry(registryUrl);
    }

    protected Registry getRegistry(URL url) {
        try {
            /**
             * Dubbo SPI 通过 RegistryFactory 扩展点自适应对象根据注册中心协议获取对应的 RegistryFactory 扩展点实现类
             * 例如 `zookeeper` 协议对应 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistryFactory}
             * 会创建一个 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistry} 注册中心对象
             *
             * 在创建注册中心对象的时候，会通过 {@link org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperTransporter} 对象
             * 创建一个 {@link org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient} zk 客户端
             * 创建它的时候会通过 curator 构建一个 CuratorFrameworkImpl 对象，并启动它，直至启动成功，有与 zk 可用的连接（或者 5s 超时），否则抛出异常
             * 同时会设置 StateListener zk 连接状态监听器，当重连或者新的 session 创建时会有相关处理
             */
            return registryFactory.getRegistry(url);
        } catch (Throwable t) {
            LOGGER.error(t.getMessage(), t);
            throw t;
        }
    }

    protected URL getRegistryUrl(Invoker<?> originInvoker) {
        return originInvoker.getUrl();
    }

    protected URL getRegistryUrl(URL url) {
        if (SERVICE_REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return url;
        }
        return url.addParameter(REGISTRY_KEY, url.getProtocol()).setProtocol(SERVICE_REGISTRY_PROTOCOL);
    }

    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl
     * @return url to registry.
     */
    private URL getUrlToRegistry(final URL providerUrl, final URL registryUrl) {

        URL registeredProviderUrl = removeUselessParameters(providerUrl);

        //The address you see at the registry
        // 如果注册中心没有配置 simplified，也就是不需要简化向注册中心注册的 URL 信息，默认不需要，兼容老版本
        // simplified 是 dubbo 2.7 新特性，只上传那些必要的服务信息，其他信息全部存在元数据中心
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            // 移除需要隐藏的参数
            return registeredProviderUrl.removeParameters(getHiddenKeys(registeredProviderUrl)).removeParameters(
                    MONITOR_KEY, BIND_IP_KEY, BIND_PORT_KEY, QOS_ENABLE, QOS_HOST, QOS_PORT, ACCEPT_FOREIGN_IP, VALIDATION_KEY,
                    INTERFACES);
        } else {
            String extraKeys = registryUrl.getParameter(EXTRA_KEYS_KEY, "");
            // if path is not the same as interface name then we should keep INTERFACE_KEY,
            // otherwise, the registry structure of zookeeper would be '/dubbo/path/providers',
            // but what we expect is '/dubbo/interface/providers'
            if (!registeredProviderUrl.getPath().equals(registeredProviderUrl.getParameter(INTERFACE_KEY))) {
                if (StringUtils.isNotEmpty(extraKeys)) {
                    extraKeys += ",";
                }
                extraKeys += INTERFACE_KEY;
            }
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_PROVIDER_KEYS
                    , COMMA_SPLIT_PATTERN.split(extraKeys));
            return URL.valueOf(registeredProviderUrl, paramsToRegistry, registeredProviderUrl.getParameter(METHODS_KEY, (String[]) null));
        }

    }

    /**
     * Remove information that does not require registration
     *
     * @param providerUrl
     * @return
     */
    private URL removeUselessParameters(URL providerUrl) {
        return providerUrl.removeParameters(ON_CONNECT_KEY, ON_DISCONNECT_KEY);
    }

    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl.setProtocol(PROVIDER_PROTOCOL)
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        String export = originInvoker.getUrl().getParameterAndDecoded(EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + originInvoker.getUrl());
        }
        return URL.valueOf(export);
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        /**
         * 获取 Registry 注册中心对应的 URL 对象
         * 在子类 {@link InterfaceCompatibleRegistryProtocol} 中
         * 把协议设置为 `registry` 的参数值，对应注册中心的名称，例如协议设置为 `zookeeper`
         * 没有这个参数则默认设置为 `dubbo` 协议，例如直连提供者
         * 同时移除 `registry` 参数
         */
        url = getRegistryUrl(url);
        /**
         * Dubbo SPI 通过 RegistryFactory 扩展点自适应对象根据注册中心协议获取对应的 RegistryFactory 扩展点实现类
         * 例如 `zookeeper` 协议对应 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistryFactory}
         * 会创建一个 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistry} 注册中心对象
         *
         * 在创建注册中心对象的时候，会通过 {@link org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperTransporter} 对象
         * 创建一个 {@link org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient} zk 客户端
         * 创建它的时候会通过 curator 构建一个 CuratorFrameworkImpl 对象，并启动它，直至启动成功，有与 zk 可用的连接（或者 5s 超时），否则抛出异常
         * 同时会设置 StateListener zk 连接状态监听器，当重连或者新的 session 创建时会有相关处理
         */
        Registry registry = getRegistry(url);

        // 如果引用的是 RegistryService 类型接口，暂时忽略
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        // 获取 url 中的 `refer` 参数，也就是消费方引用服务的详细信息
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        String group = qs.get(GROUP_KEY);
        // 分组聚合，通过分组对结果进行聚合并返回聚合后的结果
        // 比如菜单服务，用group区分同一接口的多种实现，现在消费方需从每种group中调用一次并返回结果，对结果进行合并之后返回，这样就可以实现聚合菜单项。
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
                // 执行服务引用，这里使用的是 MergeableCluster 集群对象，会得到一个 Invoker 对象
                return doRefer(Cluster.getCluster(MergeableCluster.NAME), registry, type, url, qs);
            }
        }

        /**
         * 获取集群容错的对象，例如有以下可选：
         * failover（默认）：失败自动切换，当出现失败，重试其它服务器。通常用于读操作，但重试会带来更长延迟。可通过 retries="2" 来设置重试次数(不含第一次)。
         * failfast：快速失败，只发起一次调用，失败立即报错。通常用于非幂等性的写操作，比如新增记录。
         * failsafe：失败安全，出现异常时，直接忽略。通常用于写入审计日志等操作。
         * failback：失败自动恢复，后台记录失败请求，定时重发。通常用于消息通知操作。
         * forking：并行调用多个服务器，只要一个成功即返回。通常用于实时性要求较高的读操作，但需要浪费更多服务资源。可通过 forks="2" 来设置最大并行数。
         * broadcast：广播调用所有提供者，逐个调用，任意一台报错则报错。通常用于通知所有提供者更新缓存或日志等本地资源信息。
         *
         * 默认使用 failover 集群容错，对应 {@link FailoverCluster} 对象
         * 会被一个 {@link org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterWrapper} 包装类包装
         */
        Cluster cluster = Cluster.getCluster(qs.get(CLUSTER_KEY));
        /**
         * 执行服务引用，会得到一个 Invoker 对象（伪装者），内部维护者多个 PRC Invoker 提供者
         * 参考 {@link MigrationInvoker} 对象
         *
         * @see MockClusterInvoker
         * @see FailoverClusterInvoker
         * @see AsyncToSyncInvoker
         * @see org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker
         */
        return doRefer(cluster, registry, type, url, qs);
    }

    /**
     * 引用服务，获取一个 Invoker 对象
     *
     * @param cluster    集群容错对应
     * @param registry   注册中心对象
     * @param type       引用的服务
     * @param url        消费方 URL 对象（包括注册中心和引用的服务信息）
     * @param parameters 引用的服务信息
     * @param <T>        泛型（引用的服务）
     * @return Invoker 对象
     */
    protected <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url, Map<String, String> parameters) {
        /**
         * 重新为消费方引用的服务创建一个 URL 对象：
         * 设置协议为 `consumer`
         * 地址为为当前主机 IP（移除 `register.ip` 参数）
         * 端口为 0
         * 路径为引用服务的接口名称
         * 添加所有的 `parameters` 参数
         */
        URL consumerUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        /**
         * 将这些参数封装成一个 {@link MigrationInvoker} 对象
         */
        ClusterInvoker<T> migrationInvoker = getMigrationInvoker(this, cluster, registry, type, url, consumerUrl);
        /**
         * 根据 `url` 中的信息获取对应的 {@link RegistryProtocolListener} 监听器
         * 默认有一个 {@link MigrationRuleListener} 监听器，会调用其 {@link MigrationRuleListener#onRefer} 方法，会做以下事情：
         *
         *  1. 创建一个 {@link MigrationRuleHandler} 处理器，封装了这个 `migrationInvoker` 对象，会把这个处理器添加到 {@link MigrationRuleListener#listeners}，当发送配置变更时会触发这个监听器
         *
         *  2. 这里会主动执行这个处理器，调用其 {@link MigrationRuleHandler#doMigrate} 方法，内部会调用这个 `migrationInvoker` 的 migrateToServiceDiscoveryInvoker(false) 方法
         *
         *  3. 那么接下来进入 {@link MigrationInvoker#migrateToServiceDiscoveryInvoker} 方法，入参为 false，不强制转移
         *
         *  4. 在这个方法里面会借助 {@link InterfaceCompatibleRegistryProtocol#getServiceDiscoveryInvoker} 创建一个服务发现 ClusterInvoker 对象
         *      1. 会先创建一个 {@link ServiceDiscoveryRegistryDirectory} 字典对象，然后进入 {@link this#doCreateInvoker} 方法
         *      2. 对这个 `directory` 进行相关配置，向注册中心订阅服务提供者信息，监听着它们的变化，动态维护着一些 PRC Invoker 对象 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker}
         *      3. 借助 Cluster 对象将这个 `directory` 封装成一个 Invoker 对象，例如 {@link FailoverClusterInvoker}
         *      4. 构建一条 Invoker 链，在其前面添加一系列 ClusterInterceptor 拦截器
         *         默认会有 {@link ConsumerContextClusterInterceptor} 拦截器
         *         `cluster=zone-aware` 会有 {@link ZoneAwareClusterInterceptor} 拦截器
         *      5. 将这个 Invoker 对象封装成 {@link MockClusterInvoker} 对象
         *      总结：将一系列服务提供者的 PRC Invoker 封装成一个 Invoker 对象
         *
         *  5. 上一步创建的服务发现 ClusterInvoker 对象会设置到这个 `migrationInvoker` 对象中
         *
         *  总结：最终返回的这个 `migrationInvoker` 对象，我们可以向翻字典的方式一样，拿到它就可以调用远程的 Dubbo 服务
         *  由 Cluster 的将多个 PRC Invoker 伪装成一个 Invoker，至于选择哪个 Invoker 等其他中间操作你无需关心，你只要知道通过它可以调用远程的服务集合
         */
        return interceptInvoker(migrationInvoker, url, consumerUrl);
    }

    protected <T> ClusterInvoker<T> getMigrationInvoker(RegistryProtocol registryProtocol, Cluster cluster, Registry registry,
                                                        Class<T> type, URL url, URL consumerUrl) {
        return new ServiceDiscoveryMigrationInvoker<T>(registryProtocol, cluster, registry, type, url, consumerUrl);
    }

    protected <T> Invoker<T> interceptInvoker(ClusterInvoker<T> invoker, URL url, URL consumerUrl) {
        // Dubbo SPI 根据 `url` 找打 RegistryProtocolListener 扩展点实现类，默认有一个 MigrationRuleListener
        List<RegistryProtocolListener> listeners = findRegistryProtocolListeners(url);
        if (CollectionUtils.isEmpty(listeners)) {
            return invoker;
        }

        for (RegistryProtocolListener listener : listeners) {
            listener.onRefer(this, invoker, consumerUrl);
        }
        return invoker;
    }

    public <T> ClusterInvoker<T> getServiceDiscoveryInvoker(Cluster cluster, Registry registry, Class<T> type, URL url) {
        // 创建一个 ServiceDiscoveryRegistryDirectory 对象
        DynamicDirectory<T> directory = new ServiceDiscoveryRegistryDirectory<>(type, url);
        /**
         * 1. 对这个 `directory` 进行相关配置，向注册中心订阅服务提供者信息，监听着它们的变化，动态维护着一些 PRC Invoker 对象
         * 2. 借助 Cluster 对象将这个 `directory` 封装成一个 Invoker 对象，例如 {@link FailoverClusterInvoker}
         * 3. 构建一条 Invoker 链，在其前面添加一系列 ClusterInterceptor 拦截器
         *    默认会有 {@link ConsumerContextClusterInterceptor} 拦截器
         *    `cluster=zone-aware` 会有 {@link ZoneAwareClusterInterceptor} 拦截器
         * 4. 将这个 Invoker 对象封装成 {@link MockClusterInvoker} 对象
         *
         * 总结：将一系列服务提供者的 PRC Invoker 封装成一个 Invoker 对象
         */
        return doCreateInvoker(directory, cluster, registry, type);
    }

    public <T> ClusterInvoker<T> getInvoker(Cluster cluster, Registry registry, Class<T> type, URL url) {
        // FIXME, this method is currently not used, create the right registry before enable.
        DynamicDirectory<T> directory = new RegistryDirectory<>(type, url);
        return doCreateInvoker(directory, cluster, registry, type);
    }

    protected <T> ClusterInvoker<T> doCreateInvoker(DynamicDirectory<T> directory, Cluster cluster, Registry registry, Class<T> type) {
        // 设置 Registry 注册中心对象
        directory.setRegistry(registry);
        // 设置 Protocol 扩展点自适应对象
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        // 获取消费方引用服务的相关信息
        Map<String, String> parameters = new HashMap<String, String>(directory.getConsumerUrl().getParameters());
        // 创建消费方对应的 URL 对象，并添加了引用服务的相关信息，需要注册服务消费者信息
        // 例如 `consumer://消费者主机IP/org.apache.dubbo.demo.service.StudyService?application=springboot-demo-consumer&dubbo=2.0.2&init=false
        // &interface=org.apache.dubbo.demo.service.StudyService&metadata-type=remote&methods=researchSpring,researchDubbo&pid=8432
        // &qos.enable=false&qos.port=0&release=2.7.8&revision=2.0.0&side=consumer&sticky=false&timestamp=1627286764932&version=2.0.0`
        URL urlToRegistry = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        // 是否应该向注册中心注册消费者的信息，默认需要
        if (directory.isShouldRegister()) {
            // 设置需要注册的 URL 对象
            directory.setRegisteredConsumerUrl(urlToRegistry);
            // 往注册中心注册消费者信息
            // 例如在 zookeeper 创建 `/dubbo/服务名称/consumers/消费方信息` 临时节点
            registry.register(directory.getRegisteredConsumerUrl());
        }
        // 构建一条 RouterChain 路由链，默认有 MockInvokersSelector、TagRouter、AppRouter、ServiceRouter 四个 Router 路由对象
        directory.buildRouterChain(urlToRegistry);
        /**
         * 1. 先往 `urlToRegistry` 添加 `category=providers,configurators,routers` 参数，这个参数就是需要订阅的几个子节点
         *
         * 2. 向注册中心订阅服务提供者的相关信息，也就是监听 zk 上这个服务的 `/providers`、`/configurators`、`/routers` 子节点的子节点
         *
         * 3. 设置的子节点监听器为 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistry.RegistryChildListenerImpl} 对象
         * 例如当 zk 上这个服务的 `/providers` 子节点下面的子节点发生变化时，也就是服务提供者发送变化时（例如上线、下线），会被监听到
         * 此时解析出消费方匹配的服务提供者 URL 们出来，然后由其内部的 NotifyListener 回调监听器来进行回调处理
         *
         * 4. 设置的 NotifyListener 回调监听器就是 this 对象，也就是 {@link ServiceDiscoveryRegistryDirectory#notify(List<URL>)} 来进行回调处理
         * 根据新的 URL 对象们来刷新内部的 Invoker 对象们，例如刚开始订阅有服务提供者，则根据对应的 URL 对象获取一个 PRC Invoker 对象
         *
         * 5. 例如 `dubbo` 协议对应 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol}，根据服务提供者的信息{@link AsyncToSyncInvoker} 对象
         * 里面封装了一个 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker} 对象，支持异步处理
         * 创建的过程会先获取与服务提供者的通信客户端集合，默认初始化一个通信客户端，与服务提供者建立连接（使用 netty 通信）
         * 通信客户端参考 {@link HeaderExchangeClient}，封装了 {@link org.apache.dubbo.remoting.transport.netty4.NettyClient} 对象
         *
         * 总结：这个 ServiceDiscoveryRegistryDirectory 字典实时监听着服务提供者的变化，动态维护着一些 PRC Invoker 对象
         */
        directory.subscribe(toSubscribeUrl(urlToRegistry));
        /**
         * 1. 借助 Cluster 对象将这个 ServiceDiscoveryRegistryDirectory 封装成一个 Invoker 对象，例如 {@link FailoverClusterInvoker}
         * 2. 构建一条 Invoker 链，在其前面添加一系列 ClusterInterceptor 拦截器
         *    默认会有 {@link ConsumerContextClusterInterceptor} 拦截器
         *    `cluster=zone-aware` 会有 {@link ZoneAwareClusterInterceptor} 拦截器
         * 3. 在 MockClusterWrapper 包装类中会将这个 Invoker 对象封装成 {@link MockClusterInvoker} 对象
         */
        return (ClusterInvoker<T>) cluster.join(directory);
    }

    public <T> void reRefer(ClusterInvoker<?> invoker, URL newSubscribeUrl) {
        if (!(invoker instanceof MigrationClusterInvoker)) {
            LOGGER.error("Only invoker type of MigrationClusterInvoker supports reRefer, current invoker is " + invoker.getClass());
            return;
        }

        MigrationClusterInvoker<?> migrationClusterInvoker = (MigrationClusterInvoker<?>) invoker;
        migrationClusterInvoker.reRefer(newSubscribeUrl);
    }

    public static URL toSubscribeUrl(URL url) {
        return url.addParameter(CATEGORY_KEY, PROVIDERS_CATEGORY + "," + CONFIGURATORS_CATEGORY + "," + ROUTERS_CATEGORY);
    }

    protected List<RegistryProtocolListener> findRegistryProtocolListeners(URL url) {
        return ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getActivateExtension(url, REGISTRY_PROTOCOL_LISTENER_KEY);
    }

    // available to test
    public String[] getParamsToRegistry(String[] defaultKeys, String[] additionalParameterKeys) {
        int additionalLen = additionalParameterKeys.length;
        String[] registryParams = new String[defaultKeys.length + additionalLen];
        System.arraycopy(defaultKeys, 0, registryParams, 0, defaultKeys.length);
        System.arraycopy(additionalParameterKeys, 0, registryParams, defaultKeys.length, additionalLen);
        return registryParams;
    }

    @Override
    public void destroy() {
        List<RegistryProtocolListener> listeners = ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getLoadedExtensionInstances();
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (RegistryProtocolListener listener : listeners) {
                listener.onDestroy();
            }
        }

        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();

        ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                .removeListener(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX, providerConfigurationListener);
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

    // merge the urls of configurators
    private static URL getConfiguredInvokerUrl(List<Configurator> configurators, URL url) {
        if (configurators != null && configurators.size() > 0) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    private static class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;


        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            LOGGER.debug("original override urls: " + urls);

            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl.addParameter(CATEGORY_KEY,
                    CONFIGURATORS_CATEGORY));
            LOGGER.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            doOverrideIfNecessary();
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                LOGGER.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            URL newUrl = getConfiguredInvokerUrl(configurators, originUrl);
            newUrl = getConfiguredInvokerUrl(providerConfigurationListener.getConfigurators(), newUrl);
            newUrl = getConfiguredInvokerUrl(serviceConfigurationListeners.get(originUrl.getServiceKey())
                    .getConfigurators(), newUrl);
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.reExport(originInvoker, newUrl);
                LOGGER.info("exported provider url changed, origin url: " + originUrl +
                        ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(CATEGORY_KEY) == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        public ServiceConfigurationListener(URL providerUrl, OverrideListener notifyListener) {
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.initWith(DynamicConfiguration.getRuleKey(providerUrl) + CONFIGURATORS_SUFFIX);
        }

        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfiguredInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            notifyListener.doOverrideIfNecessary();
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        public ProviderConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         */
        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfiguredInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            overrideListeners.values().forEach(listener -> ((OverrideListener) listener).doOverrideIfNecessary());
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ExecutorService executor = newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;

        private AtomicBoolean unexported = new AtomicBoolean(false);

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            if (!unexported.compareAndSet(false,true)) {
                return;
            }

            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);

            Registry registry = RegistryProtocol.this.getRegistry(originInvoker);
            try {
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                LOGGER.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.this.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
                ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                        .removeListener(subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                serviceConfigurationListeners.get(subscribeUrl.getServiceKey()));
            } catch (Throwable t) {
                LOGGER.warn(t.getMessage(), t);
            }

            executor.submit(() -> {
                try {
                    int timeout = ConfigurationUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        LOGGER.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. " +
                                "Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    LOGGER.warn(t.getMessage(), t);
                }
            });
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }

        public URL getRegisterUrl() {
            return registerUrl;
        }
    }

    // for unit test
    private static RegistryProtocol INSTANCE;

    // for unit test
    public RegistryProtocol() {
        INSTANCE = this;
    }

    // for unit test
    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }
}
