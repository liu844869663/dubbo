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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.constants.RegistryConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ReferenceConfigDestroyedEvent;
import org.apache.dubbo.config.event.ReferenceConfigInitializedEvent;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.registry.client.migration.MigrationInvoker;
import org.apache.dubbo.registry.integration.RegistryProtocol;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.FailoverCluster;
import org.apache.dubbo.rpc.cluster.support.FailoverClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.registry.ZoneAwareCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.AsyncMethodInfo;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper;
import org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;
import org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory;
import org.apache.dubbo.rpc.proxy.jdk.JdkProxyFactory;
import org.apache.dubbo.rpc.service.Destroyable;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR_CHAR;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROXY_CLASS_REF;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SEMICOLON_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.StringUtils.splitToSet;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Please avoid using this class for any new application,
 * use {@link ReferenceConfigBase} instead.
 */
public class ReferenceConfig<T> extends ReferenceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ReferenceConfig.class);

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private static final Protocol REF_PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * The {@link Cluster}'s implementation with adaptive functionality, and actually it will get a {@link Cluster}'s
     * specific implementation who is wrapped with <b>MockClusterInvoker</b>
     */
    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * The interface proxy reference
     */
    private transient volatile T ref;

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    private final ServiceRepository repository;

    private DubboBootstrap bootstrap;

    /**
     * The service names that the Dubbo interface subscribed.
     *
     * @since 2.7.8
     */
    private String services;

    public ReferenceConfig() {
        super();
        this.repository = ApplicationModel.getServiceRepository();
    }

    public ReferenceConfig(Reference reference) {
        super(reference);
        this.repository = ApplicationModel.getServiceRepository();
    }

    /**
     * Get a string presenting the service names that the Dubbo interface subscribed.
     * If it is a multiple-values, the content will be a comma-delimited String.
     *
     * @return non-null
     * @see RegistryConstants#SUBSCRIBED_SERVICE_NAMES_KEY
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(key = SUBSCRIBED_SERVICE_NAMES_KEY)
    public String getServices() {
        return services;
    }

    /**
     * It's an alias method for {@link #getServices()}, but the more convenient.
     *
     * @return the String {@link List} presenting the Dubbo interface subscribed
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(excluded = true)
    public Set<String> getSubscribedServices() {
        return splitToSet(getServices(), COMMA_SEPARATOR_CHAR);
    }

    /**
     * Set the service names that the Dubbo interface subscribed.
     *
     * @param services If it is a multiple-values, the content will be a comma-delimited String.
     * @since 2.7.8
     */
    public void setServices(String services) {
        this.services = services;
    }

    public synchronized T get() {
        // 如果已经标记被销毁状态，则抛出异常
        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }
        // 动态代理对象为空，则进行初始化
        if (ref == null) {
            // 引用远程服务，创建一个动态代理对象
            init();
        }
        // 返回这个动态代理对象
        return ref;
    }

    @Override
    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected error occurred when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;

        // dispatch a ReferenceConfigDestroyedEvent since 2.7.4
        dispatch(new ReferenceConfigDestroyedEvent(this));
    }

    public synchronized void init() {
        // 如果已经初始化，则直接返回
        if (initialized) {
            return;
        }

        if (bootstrap == null) {
            // 创建一个 Dubbo 启动器
            // 同时会向 JVM 注册一个钩子函数，用于 JVM 关闭时清理资源
            bootstrap = DubboBootstrap.getInstance();
            // compatible with api call.
            // 将注册中心的配置全部放入 ConfigManager 配置管理器中
            if (null != this.getRegistries()) {
                bootstrap.registries(this.getRegistries());
            }
            // 初始化启动器，启动配置中心，加载配置
            bootstrap.initialize();
        }

        // 对当前需要引用的接口的配置进行校验，并赋值，这里加载好 `interfaceClass`
        checkAndUpdateSubConfigs();

        // 校验 `stub` 的一些配置
        checkStubAndLocal(interfaceClass);
        // 校验 `mock` 的一些配置，与 Local 的区别在于，Local 总是被执行，而 Mock 只在出现非业务异常(比如超时，网络异常等)时执行，Local 在远程调用之前执行，Mock 在远程调用后执行。
        ConfigValidationUtils.checkMock(interfaceClass, this);

        Map<String, String> map = new HashMap<String, String>();
        // 添加 `side=consumer`，表示服务消费者
        map.put(SIDE_KEY, CONSUMER_SIDE);

        // 添加 `dubbo=2.0.2&release=2.7.8&timestamp=当前时间戳&pid=当前进程号` 参数
        ReferenceConfigBase.appendRuntimeParameters(map);
        // 如果不支持泛化引用
        if (!ProtocolUtils.isGeneric(generic)) {
            // 获取这个服务的版本
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                // 添加 `revision=版本号` 参数
                map.put(REVISION_KEY, revision);
            }
            // 获取这个服务的所有方法名称
            String[] methods = methods(interfaceClass);
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 添加 `methods=方法名称（以逗号分隔）` 参数
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), COMMA_SEPARATOR));
            }
        }
        // 添加 `interface=接口名称` 参数
        map.put(INTERFACE_KEY, interfaceName);
        AbstractConfig.appendParameters(map, getMetrics());
        // 从 ApplicationConfig 中获取需要的参数，例如 `application=demo`
        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ConsumerConfig
        // appendParameters(map, consumer, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, consumer);
        // 从当前 ReferenceConfig 对象获取需要的参数，例如 `version=版本号&check=false&protocol=dubbo&sticky=false&generic=true` 等配置
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }
        Map<String, AsyncMethodInfo> attributes = null;
        // 如果单独进行了 `dubbo:method` 方法配置，那么逐一解析
        if (CollectionUtils.isNotEmpty(getMethods())) {
            attributes = new HashMap<>();
            for (MethodConfig methodConfig : getMethods()) {
                // 添加参数，例如 `方法名称.retries=0&方法名称.timeout=5000` 方法级别的参数
                AbstractConfig.appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                // 将 `方法名称.retry=false` 替换成 `方法名称.retries=0`，禁用重试
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                // 如果有方法执行的拦截处理（执行前后或者异常拦截），则创建一个异步方法对象，保存这些拦截处理的信息
                AsyncMethodInfo asyncMethodInfo = AbstractConfig.convertMethodConfig2AsyncInfo(methodConfig);
                // 如果有拦截处理，则将这个异步方法信息对象保存起来
                if (asyncMethodInfo != null) {
//                    consumerModel.getMethodModel(methodConfig.getName()).addAttribute(ASYNC_KEY, asyncMethodInfo);
                    attributes.put(methodConfig.getName(), asyncMethodInfo);
                }
            }
        }

        // 从系统变量中获取 DUBBO_IP_TO_REGISTRY 指定的当前 IP 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            // 如果没有指定，则尝试获取当前主机的 IP 地址
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                    "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        // 添加 `register.ip=注册IP地址` 参数
        map.put(REGISTER_IP_KEY, hostToRegistry);

        // 将上面所有的参数添加到 ServiceMetadata 元数据对象中
        serviceMetadata.getAttachments().putAll(map);

        // 根据这些信息创建一个动态代理对象
        ref = createProxy(map);

        // 将这个动态代理对象设置到 ServiceMetadata 元数据对象中
        serviceMetadata.setTarget(ref);
        serviceMetadata.addAttribute(PROXY_CLASS_REF, ref);
        // 将这个动态代理对象设置到对应的 ConsumerModel 对象中
        ConsumerModel consumerModel = repository.lookupReferredService(serviceMetadata.getServiceKey());
        consumerModel.setProxyObject(ref);
        consumerModel.init(attributes);

        // 标记为已初始化
        initialized = true;

        // 检查得到的 Invoker 对象是否可用（默认需要检查）
        // 也就是检查服务提供者是否有用
        checkInvokerAvailable();

        // dispatch a ReferenceConfigInitializedEvent since 2.7.4
        // 发布服务引用已初始化事件
        dispatch(new ReferenceConfigInitializedEvent(this, invoker));
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        // 是否应该使用本地 JVM 的引用
        if (shouldJvmRefer(map)) {
            // 创建一个 injvm 协议的 URL 对象，并添加上面所有的参数
            URL url = new URL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName()).addParameters(map);
            /**
             * 根据 URL 对象为消费方引用的服务创建一个 Invoker 对象
             *
             * 借助 Protocol 扩展点实现类的自适应对象  {@link org.apache.dubbo.rpc.Protocol$Adaptive} 获取一个 Invoker 对象
             * 先通过 Dubbo SPI 根据协议名称获取到对应的 Protocol 扩展点实现类，injvm 得到的是不是 {@link InjvmProtocol} 呢？
             * 其实不然，对于 Protocol 还有 Filter 和 ExportListener 两种 Wrapper 包装类，会将 {@link InjvmProtocol} 包装起来
             *
             * 1. 先由最里面的 {@link InjvmProtocol} 创建一个 {@link org.apache.dubbo.rpc.protocol.injvm.InjvmInvoker} 对象
             *    返回的是 {@link org.apache.dubbo.rpc.protocol.AsyncToSyncInvoker} 对象，支持异步获取执行结果
             *
             * 2. 然后在 {@link ProtocolListenerWrapper} 中将这个 Invoker 对象和 InvokerListener 监听器们封装起来，目的就是执行这些监听器
             *    例如服务端配置了 deprecated=true，消费方引用时，DeprecatedInvokerListener 会打印服务过时的 error 日志
             *
             * 3. 最后在 {@link ProtocolFilterWrapper} 中将这个 Invoker 转换成一条 Invoker 链，也就是在前面加上一条 Filter 过滤器链
             */
            invoker = REF_PROTOCOL.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            // 清理掉 URL 对象们
            urls.clear();
            // 如果是直接服务方
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                // 将 url 指定的直连地址根据 ; 进行分割，可以配置多个
                String[] us = SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    // 循环处理
                    for (String u : us) {
                        // 创建 URL 对象
                        URL url = URL.valueOf(u);
                        if (StringUtils.isEmpty(url.getPath())) {
                            // 设置路径为接口名称
                            url = url.setPath(interfaceName);
                        }
                        // 如果是注册中心的地址，则添加 `refer` 参数，保存引用服务的信息，添加至 `urls` 集合中
                        if (UrlUtils.isRegistry(url)) {
                            urls.add(url.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        // 否则，将这个 URL 和引用服务的信息合并，添加至 `urls` 集合中
                        } else {
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } else { // assemble URL from register center's configuration
                // if protocols not injvm checkRegistry
                // 如果不是 injvm 协议
                if (!LOCAL_PROTOCOL.equalsIgnoreCase(getProtocol())) {
                    // 校验注册中心的地址是否不为空
                    checkRegistry();
                    // 获取注册中心对应的 URL 们，支持多注册中心，所以可能返回多个
                    // 例如这里得到 `registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=8432&registry=zookeeper&release=2.7.8&timestamp=1627287397524`
                    // 如果是集群的话，后面会有一个 `&backup=其他节点IP` 参数信息，也就是你填写 zookeeper 地址时候填写的参数信息
                    List<URL> us = ConfigValidationUtils.loadRegistries(this, false);
                    if (CollectionUtils.isNotEmpty(us)) {
                        for (URL u : us) {
                            // 如果设置了监控中心，则加载出 `<dubbo:monitor />` 监控中心的 URL 对象
                            URL monitorUrl = ConfigValidationUtils.loadMonitor(this, u);
                            if (monitorUrl != null) {
                                map.put(MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                            }
                            // 将这个注册中心的 URL 对象保存至 `urls` 集合中
                            // 同时，添加 `refer` 参数中，保存引用服务的信息
                            urls.add(u.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        }
                    }
                    // 如果没有注册中心，则抛出异常
                    if (urls.isEmpty()) {
                        throw new IllegalStateException(
                                "No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() +
                                        " use dubbo version " + Version.getVersion() +
                                        ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                    }
                }
            }

            // 只有一个 Dubbo 服务消费者的 URL 对象
            if (urls.size() == 1) {
                /**
                 * 根据 URL 对象为消费方引用的服务创建一个 Invoker 对象
                 * 因为上面创建一般都是注册中心的 URL 对象，所以对应的协议为 `registry`，当然，直连的情况通常用于测试场景，绕过来注册中心
                 *
                 * 借助 Protocol 扩展点实现类的自适应对象  {@link org.apache.dubbo.rpc.Protocol$Adaptive} 获取一个 Invoker 对象
                 * 先通过 Dubbo SPI 根据协议名称获取到对应的 Protocol 扩展点实现类
                 * `registry` 对应 {@link org.apache.dubbo.registry.integration.InterfaceCompatibleRegistryProtocol}，继承 {@link RegistryProtocol}
                 * 当然，对于 Protocol 还有 Filter 和 ExportListener 两种 Wrapper 包装类，会将其包装起来
                 * 
                 * 1. 因为是注册中心，所以在 {@link ProtocolFilterWrapper} 和 {@link ProtocolListenerWrapper} 中没有做任何处理
                 * 
                 * 2. 接下来进入 {@link RegistryProtocol#refer(Class, URL)} 方法
                 *
                 * 2.1 通过 Dubbo SPI 获取对应的 Registry 注册中心对象，例如 `zookeeper` 协议对应 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistry} 注册中心
                 *     创建的时候会使用 curator 创建一个 zk 客户端，zkclient 在后续版本被移除，支持自行扩展，通过 client 或者 transporter 指定
                 *
                 * 2.2 通过 Dubbo SPI 获取对应的 Cluster 集群容错对象，默认为 `failover`，对应 {@link FailoverCluster}
                 *
                 * 2.2 接下来引用服务，会得到一个 {@link MigrationInvoker} 对象，过程非常复杂，大致如下：
                 *  2.2.1 创建一个 {@link ServiceDiscoveryRegistryDirectory} 对象，向 zk 订阅信息，例如监听这个服务的 `/providers` 的子节点
                 *  2.2.2 每个子节点对应一个服务提供者，这里会解析成一个 PRC Invoker 对象 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker}
                 *        每个 DubboInvoker 都有通信客户端集合，默认初始化一个通信客户端，与服务提供者建立连接（使用 netty 通信），当你本地调用时，则向远程的服务提供者发送请求信息
                 *        在这个 Directory 字典中动态维护着这一系列的 PRC Invoker 对象
                 *  2.2.3 由 Cluster 集群容错对象将这个 Directory 字典对象伪装成一个 Invoker 对象，例如 {@link FailoverClusterInvoker}
                 *  2.2.4 最终这个服务发现对象 ClusterInvoker 对象会设置到 {@link MigrationInvoker#currentAvailableInvoker} 中
                 */
                invoker = REF_PROTOCOL.refer(interfaceClass, urls.get(0));
            // 否则，多注册中心
            } else {
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                for (URL url : urls) {
                    // 参考上面说明
                    Invoker<?> referInvoker = REF_PROTOCOL.refer(interfaceClass, url);
                    if (shouldCheck()) {
                        if (referInvoker.isAvailable()) {
                            invokers.add(referInvoker);
                        } else {
                            referInvoker.destroy();
                        }
                    } else {
                        invokers.add(referInvoker);
                    }

                    if (UrlUtils.isRegistry(url)) {
                        registryURL = url; // use last registry url
                    }
                }

                if (shouldCheck() && invokers.size() == 0) {
                    throw new IllegalStateException("Failed to check the status of the service "
                            + interfaceName
                            + ". No provider available for the service "
                            + (group == null ? "" : group + "/")
                            + interfaceName +
                            (version == null ? "" : ":" + version)
                            + " from the multi registry cluster"
                            + " use dubbo version " + Version.getVersion());
                }

                if (registryURL != null) { // registry url is available
                    // for multi-subscription scenario, use 'zone-aware' policy by default
                    String cluster = registryURL.getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME);
                    // The invoker wrap sequence would be: ZoneAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, routing happens here) -> Invoker
                    invoker = Cluster.getCluster(cluster, false).join(new StaticDirectory(registryURL, invokers));
                } else { // not a registry url, must be direct invoke.
                    String cluster = CollectionUtils.isNotEmpty(invokers)
                            ?
                            (invokers.get(0).getUrl() != null ? invokers.get(0).getUrl().getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME) :
                                    Cluster.DEFAULT)
                            : Cluster.DEFAULT;
                    invoker = Cluster.getCluster(cluster).join(new StaticDirectory(invokers));
                }
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }

        // 创建一个消费者信息的 URL 对象
        URL consumerURL = new URL(CONSUMER_PROTOCOL, map.remove(REGISTER_IP_KEY), 0, map.get(INTERFACE_KEY), map);
        // 将 Dubbo 服务消费者的元数据信息保存
        MetadataUtils.publishServiceDefinition(consumerURL);

        // create service proxy
        /**
         * 借助 ProxyFactory 扩展点实现类的自适应对象 {@link org.apache.dubbo.rpc.ProxyFactory$Adaptive} 创建一个动态代理对象
         *
         * 1. 获取代理类要实现的接口，有以下几个：
         *      - 引用的服务接口
         *      - 如果开启泛化引用，则调价 {@link GenericService} 接口，当消费端没有服务端 API 模型时，可通过该接口的 {@link GenericService#$invoke} 方法执行远程目标方法
         *      - {@link Destroyable} 接口，用于销毁这个 Invoker 对象
         *      - {@link org.apache.dubbo.rpc.service.EchoService} 接口，回声测试，可用于检测服务是否可用
         *
         *  2. 创建一个 {@link InvokerInvocationHandler} 拦截处理器，内部有这个 `invoker` 的引用，用于拦截代理类的方法，并通过这个 `invoker` 来执行
         *
         *  3. 创建一个动态代理对象，实现上述几个接口，设置上述拦截处理器
         *      - {@link JavassistProxyFactory}（默认）底层使用 javassist 创建代理对象
         *      - {@link JdkProxyFactory} 使用 JDK 动态代理创建代理类对象
         *
         *  总结：这个动态代理对象的拦截处理器是 {@link InvokerInvocationHandler} 对象，会先将执行方法的相关信息封装成 {@link RpcInvocation} 对象
         *  然后调用 {@link MigrationInvoker#invoke(Invocation)} 方法来调用远程服务的方法，底层使用 netty 基于 TCP 长连接与服务端进行通信
         *  得到响应后调用 {@link Result#recreate()} 方法获取执行结果
         */
        return (T) PROXY_FACTORY.getProxy(invoker, ProtocolUtils.isGeneric(generic));
    }

    private void checkInvokerAvailable() throws IllegalStateException {
        if (shouldCheck() && !invoker.isAvailable()) {
            invoker.destroy();
            throw new IllegalStateException("Failed to check the status of the service "
                    + interfaceName
                    + ". No provider available for the service "
                    + (group == null ? "" : group + "/")
                    + interfaceName +
                    (version == null ? "" : ":" + version)
                    + " from the url "
                    + invoker.getUrl()
                    + " to the consumer "
                    + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     */
    public void checkAndUpdateSubConfigs() {
        // 如果需要引用的接口名为空，则抛出异常
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        // 先从 ConsumerConfig、ApplicationConfig 中设置默认的配置（如果有配置的话）
        completeCompoundConfigs(consumer);
        // get consumer's global configuration
        // 创建 ConsumerConfig 放入 ConfigManager 配置管理器
        // 拼接属性配置（环境变量 + properties 属性）到 ConsumerConfig 对象
        checkDefault();

        // init some null configuration.
        // SPI 扩展，执行配置初始器
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf("configInitializer://"), (String[]) null);
        configInitializers.forEach(e -> e.initReferConfig(this));

        // 刷新当前 ReferenceConfig 的属性值，重新设置
        this.refresh();
        // 如果没有配置 `generic`，则从 ConsumerConfig 获取并设置
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        // 如果开启了泛化调用，则设置引用的接口的 Class 对象为 GenericService
        // 在消费者端没有服务端模型的情况下，可以通过执行 GenericService.$invoke(String method, String[] parameterTypes, Object[] args) 方法调用远程服务
        if (ProtocolUtils.isGeneric(generic)) {
            interfaceClass = GenericService.class;
        } else {
            try {
                // 否则，根据接口名称获取其 Class 对象
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 校验该接口配置的方法
            checkInterfaceAndMethods(interfaceClass, getMethods());
        }

        // 初始化当前需要引用的接口的 ServiceMetadata 元数据对象
        // 版本、分组和接口名称
        initServiceMetadata(consumer);
        // 引用的接口的实际类型
        serviceMetadata.setServiceType(getActualInterface());
        // TODO, uncomment this line once service key is unified
        // 生成一个 key，`分组/接口名称:版本名称`
        serviceMetadata.setServiceKey(URL.buildKey(interfaceName, group, version));

        // 获取服务接口仓库
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        // 往仓库中注册需要引用的服务接口，没有指定版本号
        ServiceDescriptor serviceDescriptor = repository.registerService(interfaceClass);
        // 往仓库中注册需要引用的服务接口的详细信息
        repository.registerConsumer(
                serviceMetadata.getServiceKey(),
                serviceDescriptor,
                this,
                null,
                serviceMetadata);

        // 加载 ${user.home}/dubbo-resolve.properties 文件，可通过 -Ddubbo.resolve.file=xxx.properties 配置
        // 通过该文件制定需要直连的服务，key 为服务名称，value 为对应的直连 url 地址
        resolveFile();
        // 校验需要引用的接口的一些配置是否合法
        ConfigValidationUtils.validateReferenceConfig(this);
        // SPI 机制，加载 ConfigPostProcessor 配置后置处理器，进行处理
        postProcessConfig();
    }


    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     */
    protected boolean shouldJvmRefer(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        boolean isJvmRefer;
        // 如果没有配置 injvm
        if (isInjvm() == null) {
            // if a url is specified, don't do local reference
            // 通过 URL 直连提供者，绕过注册中心，则不应该使用当前 JVM 内的引用，所以返回 false
            if (url != null && url.length() > 0) {
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                /**
                 * 默认情况，都会走到这里
                 * 1. 如果配置了 scope=local 或者 injvm=true，则返回 true
                 * 2. 否则，如果配置了 scope=remote，则返回 false
                 * 3. 否则，如果配置了 generic=true，则返回 false
                 * 4. 否则，如果找到了 Exporter，也就代表本地暴露的该服务，没必要使用远程服务，减少网络开销，提高性能，则返回 true
                 *    这里如果配置的 cluster=broadcast，需要广播所有提供者，有一个报错，则报错，那么返回 false
                 * 5. 否则，返回 false
                 */
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            // 否则，返回配置的 injvm
            isJvmRefer = isInjvm();
        }
        // 返回是否应该使用 JVM 内的引用
        return isJvmRefer;
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    protected void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf("configPostProcessor://"), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessReferConfig(this));
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }
}
