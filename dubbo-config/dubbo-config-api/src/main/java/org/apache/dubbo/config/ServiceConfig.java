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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ServiceConfigExportedEvent;
import org.apache.dubbo.config.event.ServiceConfigUnexportedEvent;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.registry.integration.RegistryProtocol;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper;
import org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory;
import org.apache.dubbo.rpc.proxy.jdk.JdkProxyFactory;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_TYPE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_TYPE;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.MULTICAST;
import static org.apache.dubbo.config.Constants.MULTIPLE;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;

public class ServiceConfig<T> extends ServiceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService DELAY_EXPORT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    private String serviceName;

    /**
     * Protocol 扩展点实现类的自适应对象
     *
     * @see org.apache.dubbo.rpc.Protocol$Adaptive
     */
    private static final Protocol PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * ProxyFactory 扩展点实现类的自适应对象
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its default implementation
     *
     * @see org.apache.dubbo.rpc.ProxyFactory$Adaptive
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    /**
     * Dubbo 启动器
     */
    private DubboBootstrap bootstrap;

    /**
     * The exported services
     * Dubbo 服务提供者暴露的 Exporter 集合
     * 因为存在多注册中心、多协议、本地暴露、远程暴露，所以会有多个 Exporter
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    private static final String CONFIG_INITIALIZER_PROTOCOL = "configInitializer://";

    private static final String TRUE_VALUE = "true";

    private static final String FALSE_VALUE = "false";

    private static final String STUB_SUFFIX = "Stub";

    private static final String LOCAL_SUFFIX = "Local";

    private static final String CONFIG_POST_PROCESSOR_PROTOCOL = "configPostProcessor://";

    private static final String RETRY_SUFFIX = ".retry";

    private static final String RETRIES_SUFFIX = ".retries";

    private static final String ZERO_VALUE = "0";

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        super(service);
    }

    @Parameter(excluded = true)
    @Override
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    @Override
    public boolean isUnexported() {
        return unexported;
    }

    @Override
    public void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occurred when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;

        // dispatch a ServiceConfigUnExportedEvent since 2.7.4
        dispatch(new ServiceConfigUnexportedEvent(this));
    }

    @Override
    public synchronized void export() {
        if (bootstrap == null) {
            // 创建一个 Dubbo 启动器
            // 同时会向 JVM 注册一个钩子函数，用于 JVM 关闭时清理资源
            bootstrap = DubboBootstrap.getInstance();
            // compatible with api call.
            // 将注册中心的配置全部放入 ConfigManager 配置管理器中
            if (null != this.getRegistry()) {
                bootstrap.registries(this.getRegistries());
            }
            // 初始化启动器，启动配置中心，加载配置
            bootstrap.initialize();
        }

        // 对当前需要暴露的接口的配置进行校验，并赋值
        checkAndUpdateSubConfigs();

        // 初始化当前需要暴露的接口的 ServiceMetadata 元数据对象
        // 版本、分组和接口名称
        initServiceMetadata(provider);
        // 接口的 Class 对象
        serviceMetadata.setServiceType(getInterfaceClass());
        // 所引用的目标对象（也就是接口的实现类）
        serviceMetadata.setTarget(getRef());
        // 生成一个 key，`分组/接口名称:版本名称`
        serviceMetadata.generateServiceKey();

        // 如果通过 `export` 配置为 `false` 不进行暴露服务，则直接跳过
        if (!shouldExport()) {
            return;
        }

        // 如果通过 `delay` 配置了延迟暴露服务
        if (shouldDelay()) {
            // 使用单线程池设置多久后暴露服务
            DELAY_EXPORT_EXECUTOR.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            // 直接暴露服务
            doExport();
        }

        exported();
    }

    public void exported() {
        List<URL> exportedURLs = this.getExportedUrls();
        exportedURLs.forEach(url -> {
            // dubbo2.7.x does not register serviceNameMapping information with the registry by default.
            // Only when the user manually turns on the service introspection, can he register with the registration center.
            boolean isServiceDiscovery = UrlUtils.isServiceDiscoveryRegistryType(url);
            if (isServiceDiscovery) {
                Map<String, String> parameters = getApplication().getParameters();
                ServiceNameMapping.getExtension(parameters != null ? parameters.get(MAPPING_KEY) : null).map(url);
            }
        });
        // dispatch a ServiceConfigExportedEvent since 2.7.4
        dispatch(new ServiceConfigExportedEvent(this));
    }

    private void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly with global scope
        // 先从 ProviderConfig、ApplicationConfig 中设置默认的配置（如果有配置的话）
        completeCompoundConfigs();
        // 创建 ProviderConfig 放入 ConfigManager 配置管理器
        // 拼接属性配置（环境变量 + properties 属性）到 ProviderConfig 对象
        checkDefault();
        // 设置协议
        checkProtocol();

        // init some null configuration.
        // SPI 扩展，执行配置初始器
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf(CONFIG_INITIALIZER_PROTOCOL), (String[]) null);
        configInitializers.forEach(e -> e.initServiceConfig(this));

        // if protocol is not injvm checkRegistry
        // 如果不仅仅是 injvm 协议，则校验注册中心
        if (!isOnlyInJvm()) {
            checkRegistry();
        }
        // 刷新当前 ServiceConfig 的属性值，重新设置
        this.refresh();

        // 接口名称不允许为空
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        // 如果引用的对象是泛化接口，则设置接口的 Class 对象为 GenericService
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = TRUE_VALUE;
            }
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
            // 校验所引用，是否是该接口的实现类
            checkRef();
            generic = FALSE_VALUE;
        }

        // 使用 `local`，接口在客户端的本地代理，已废弃，请使用 `stub`
        if (local != null) {
            if (TRUE_VALUE.equals(local)) {
                local = interfaceName + LOCAL_SUFFIX;
            }
            Class<?> localClass;
            try {
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException(
                        "The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 使用 `stub`，接口在客户端的本地代理
        if (stub != null) {
            // 设置开启状态，则使用 `接口名+Stub` 作为接口在客户端的本地代理的代理类名称
            if (TRUE_VALUE.equals(stub)) {
                stub = interfaceName + STUB_SUFFIX;
            }
            // 获取这个代理类名称的 Class 对象，并进行校验
            Class<?> stubClass;
            try {
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException(
                        "The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 校验 `stub` 的一些配置
        checkStubAndLocal(interfaceClass);
        // 校验 `mock` 的一些配置，与 Local 的区别在于，Local 总是被执行，而 Mock 只在出现非业务异常(比如超时，网络异常等)时执行，Local 在远程调用之前执行，Mock 在远程调用后执行。
        ConfigValidationUtils.checkMock(interfaceClass, this);
        // 校验需要暴露的接口的一些配置是否合法
        ConfigValidationUtils.validateServiceConfig(this);
        // SPI 机制，加载 ConfigPostProcessor 配置后置处理器，进行处理
        postProcessConfig();
    }


    protected synchronized void doExport() {
        // 如果标记了不可暴露，则抛出异常
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        // 如果标记已经暴露服务，则直接返回
        if (exported) {
            return;
        }
        // 标记为已经暴露服务
        exported = true;

        // 如果没有设置 `path`，则使用接口名称作为服务路径
        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        // 开始暴露服务
        doExportUrls();
        bootstrap.setReady(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        // 获取服务接口仓库
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        // 往仓库中注册需要暴露的服务接口，没有指定版本号
        ServiceDescriptor serviceDescriptor = repository.registerService(getInterfaceClass());
        // 往仓库中注册需要暴露的服务接口的详细信息
        repository.registerProvider(
                getUniqueServiceName(),
                ref,
                serviceDescriptor,
                this,
                serviceMetadata
        );

        // 获取注册中心对应的 URL 们，支持多注册中心，所以可能返回多个
        // 例如这里得到 `registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=21100&registry=zookeeper&release=2.7.8&timestamp=1627003234595`
        // 如果是集群的话，后面会有一个 `&backup=其他节点IP` 参数信息，也就是你填写 zookeeper 地址时候填写的参数信息
        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);

        int protocolConfigNum = protocols.size();
        // 遍历需要注册的协议，例如 dubbo、rest
        for (ProtocolConfig protocolConfig : protocols) {
            // 生成一个 key，`分组名/接口名称:版本号`
            String pathKey = URL.buildKey(getContextPath(protocolConfig)
                    .map(p -> p + "/" + path)
                    .orElse(path), group, version);
            // In case user specified path, register service one more time to map it to path.
            // 往仓库中注册需要暴露的服务接口，这里指定了版本号
            repository.registerService(pathKey, interfaceClass);
            // 使用指定的协议暴露服务
            doExportUrlsFor1Protocol(protocolConfig, registryURLs, protocolConfigNum);
        }
    }

    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs, int protocolConfigNum) {
        // 获取协议名称，默认为 dubbo
        String name = protocolConfig.getName();
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        // 添加 `side=provider`，表示服务提供者
        map.put(SIDE_KEY, PROVIDER_SIDE);

        // 添加 `dubbo=2.0.2&release=2.7.8&timestamp=当前时间戳&pid=当前进程号` 参数
        ServiceConfig.appendRuntimeParameters(map);
        AbstractConfig.appendParameters(map, getMetrics());
        // 从 ApplicationConfig 中获取需要的参数，例如 `application=demo`
        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        // 从 ProviderConfig 中获取需要的参数，例如 `deprecated=false&dynamic=true`
        AbstractConfig.appendParameters(map, provider);
        AbstractConfig.appendParameters(map, protocolConfig);
        // 从当前 ServiceConfig 对象获取需要的参数，例如 `interface=接口名称&version=版本号`
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }
        // 如果单独进行了 `dubbo:method` 方法配置，那么逐一解析
        if (CollectionUtils.isNotEmpty(getMethods())) {
            for (MethodConfig method : getMethods()) {
                AbstractConfig.appendParameters(map, method, method.getName());
                String retryKey = method.getName() + RETRY_SUFFIX;
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if (FALSE_VALUE.equals(retryValue)) {
                        map.put(method.getName() + RETRIES_SUFFIX, ZERO_VALUE);
                    }
                }
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                AbstractConfig
                                                        .appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException(
                                                        "Argument config error : the index attribute and type attribute not match :index :" +
                                                                argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    AbstractConfig.appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException(
                                                                "Argument config error : the index attribute and type attribute not match :index :" +
                                                                        argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException(
                                    "Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            // 获取这个服务的版本
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                // 添加 `revision=版本号` 参数
                map.put(REVISION_KEY, revision);
            }
            // 获取这个服务的所有方法名称
            String[] methods = methods(this.interfaceClass);
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 添加 `methods=方法名称（以逗号分隔）` 参数
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        /**
         * Here the token value configured by the provider is used to assign the value to ServiceConfig#token
         */

        // 如果设置了 `token`，则设置令牌验证
        if (ConfigUtils.isEmpty(token) && provider != null) {
            token = provider.getToken();
        }

        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }
        //init serviceMetadata attachments
        // 将上面所有的参数添加到 ServiceMetadata 元数据对象中
        serviceMetadata.getAttachments().putAll(map);

        // export service
        // 找到当前应用所部署的主机地址
        String host = findConfigedHosts(protocolConfig, registryURLs, map);
        // 获取当前应用所暴露的协议的端口
        Integer port = findConfigedPorts(protocolConfig, name, map, protocolConfigNum);

        // 为需要暴露的接口创建对应协议的 URL 对象，包含了上面的所有参数，注册中心的地址
        // 如果设置了 contextpath，则添加至服务路径的最前面
        // 例如，这里得到
        // dubbo://192.168.222.61:9090/org.apache.dubbo.demo.service.StudyService?anyhost=true&application=demo-provider
        // &bind.ip=192.168.222.61&bind.port=9090&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false
        // &interface=org.apache.dubbo.demo.service.StudyService&metadata-type=remote
        // &methods=researchSpring,researchDubbo&pid=21100&release=2.7.8&revision=1.0.0&side=provider&timestamp=1627003769406&version=1.0.0
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);

        // You can customize Configurator to append extra parameters
        // 如果有对应协议的扩展器，则对该 URL 对象进行配置
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(SCOPE_KEY);
        // don't export when none is configured
        // 如果设置了该服务需要暴露的范围不是 none，则开始暴露该服务
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            // 如果不仅仅是往 remote 暴露，那默认会往本地暴露该服务，也就是 injvm 协议
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                // 向当前 JVM（本地）暴露这个 Dubbo 服务，将得到的 InjvmExporter 保存至 this#exporters 集合中
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            // 如果不仅仅是往 local 暴露，则需要往远程暴露该服务，也就是往注册中心注册
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    // 遍历注册中心，依次注册
                    for (URL registryURL : registryURLs) {
                        // 如果注册中心的协议为 `service-discovery-registry`，2.7.5 版本之前都是 `registry`，暂时忽略
                        // 可往 RegistryConfig 的 parameter 添加 `registry-type=service` 参数进行设置
                        if (SERVICE_REGISTRY_PROTOCOL.equals(registryURL.getProtocol())) {
                            url = url.addParameterIfAbsent(REGISTRY_TYPE_KEY, SERVICE_REGISTRY_TYPE);
                        }

                        //if protocol is only injvm ,not register
                        // 如果是 injvm 协议，上面已经暴露过，这里直接跳过
                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                            continue;
                        }

                        // 如果没有 `dynamic` 配置，则从注册中心对应的 URL 获取并设置
                        // 在 AbstractServiceConfig 中默认为 `dynamic=true`
                        // 服务是否动态注册，如果设为 false，注册后将显示后 disable 状态，需人工启用，并且服务提供者停止时，也不会自动取消册，需人工禁用
                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));

                        // 如果设置了监控中心，则加载出 `<dubbo:monitor />` 监控中心的 URL 对象
                        URL monitorUrl = ConfigValidationUtils.loadMonitor(this, registryURL);
                        if (monitorUrl != null) {
                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            if (url.getParameter(REGISTER_KEY, true)) {
                                // 打印注册该服务的日志信息
                                logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " +
                                        registryURL);
                            } else {
                                logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                            }
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        // 如果指定了动态代理的方式，则添加到参数中，没有配置的话默认使用 javassist
                        String proxy = url.getParameter(PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                        }

                        /**
                         * 为这个 `ref`（服务提供者的实现类）创建一个 Invoker 对象，可以理解为 `ref` 的代理对象，用于统一执行请求方法
                         *
                         * 1. 先将这个 Dubbo 服务的 URL 对象转换成字符串，添加到（先编码）注册中心的 URL 对象的 `export` 参数中
                         * 因为后续处理过程需要借助这个参数获取 Dubbo 服务的 URL 对象
                         *
                         * 2. 借助 ProxyFactory 扩展点实现类的自适应对象 {@link org.apache.dubbo.rpc.ProxyFactory$Adaptive} 获取一个 Invoker 对象
                         *
                         * {@link JavassistProxyFactory}（默认）创建一个 {@link AbstractProxyInvoker} 对象
                         * 会先通过 Javassist 动态创建一个 Wrapper 包装类，执行 invokeMethod(..) 即可执行 `ref` 的方法
                         * 这个 Invoker 对象则可通过 Result invoke(Invocation invocation) 方法执行 `ref` 的方法
                         *
                         * {@link JdkProxyFactory} 就比较简单，创建的 {@link AbstractProxyInvoker} 对象就是通过反射执行 `ref` 的方法，效率相比 Javassist 没有那么好
                         */
                        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));

                        // 对这个 Invoker 对象进行一层封装，包含了当前 ServiceConfig 的所有信息
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        /**
                         * 对上面创建的 Invoker 对象进行暴露，返回一个 Exporter 对象，可以理解为将 Invoker 暴露后进行封装的一个对象
                         *
                         * 因为上面创建的 Invoker 对象使用的是注册中心的 URL 对象，所以对应的协议为 `registry`
                         *
                         * 借助 Protocol 扩展点实现类的自适应对象  {@link org.apache.dubbo.rpc.Protocol$Adaptive} 获取一个 Exporter 对象
                         * 先通过 Dubbo SPI 根据协议名称获取到对应的 Protocol 扩展点实现类
                         * `registry` 对应 {@link org.apache.dubbo.registry.integration.InterfaceCompatibleRegistryProtocol}，继承 {@link RegistryProtocol}
                         * 当然，对于 Protocol 还有 Filter 和 ExportListener 两种 Wrapper 包装类，会将其包装起来
                         *
                         * 1. 因为是注册中心，所以在 {@link ProtocolFilterWrapper} 和 {@link ProtocolListenerWrapper} 中没有做任何处理
                         *
                         * 2. 接下来进入 {@link RegistryProtocol#export(Invoker)}，其内部又有一个 Protocol 对象，也是 {@link org.apache.dubbo.rpc.Protocol$Adaptive} 自适应实现类
                         *
                         * 3. 会先通过其内部的 Protocol 对象暴露这个 Invoker，返回一个 Export 对象
                         *  3.1 例如 dubbo 协议，对应 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol}，当然，这里也会有 Filter 和 ExportListener 的包装
                         *      会先将 Invoker 转换成一条 Invoker 链，也就是在前面加上一条 Filter 过滤器链
                         *      然后将这条 Invoker 链和 ExporterListener 监听器数组封装起来，目的就是执行这些监听器
                         *  3.2 通过 DubboProtocol 进行暴露，返回一个 DubboExporter 对象
                         *  3.3 同时会启动通信服务器，例如 {@link org.apache.dubbo.remoting.transport.netty4.NettyServer} 服务器，监听某个端口，处理远程发过来的请求
                         *
                         * 4. 通过 Dubbo SPI 获取对应的 Registry 注册中心对象
                         *  4.1 例如 ZookeeperRegistry 注册中心，创建的时候会使用 curator 创建一个 zookeeper 客户端
                         *      当然，Dubbo 内部只有 curator 客户端，zkclient 在后续版本被移除，支持自行扩展，通过 client 或者 transporter 指定
                         *  4.2 向注册中心为 Dubbo 服务提供者，创建一个临时节点：`/dubbo/服务名称/providers/服务提供者信息`
                         */
                        Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                        // 将得到的 Export 对象（一系列的封装）放入 `exporters` 集合中
                        exporters.add(exporter);
                    }
                // 否则，没有注册中心
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                    }
                    // 创建一个 Invoker 对象，这里入参中的 `url` 参数就是当前需要暴露的 Dubbo 服务的信息
                    // 参考上面说明，区别在于这里没有注册中心的 URL 信息，直接暴露，绕过注册中心，直连调用
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);

                    // 对这个 Invoker 对象进行一层封装，包含了当前 ServiceConfig 的所有信息
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    /**
                     * 参考上面的说明，暴露的 URL 信息没有注册中心
                     * 所以这里借助 Protocol 扩展点实现类的自适应对象  {@link org.apache.dubbo.rpc.Protocol$Adaptive} 获取一个 Exporter 对象的过程如下：
                     * 1. 先通过 Dubbo SPI 根据协议名称获取到对应的 Protocol 扩展点实现类，dubbo 对应 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol}
                     *
                     * 2. 当然，这里也会有 Filter 和 ExportListener 的包装
                     *  2.1 会先将 Invoker 转换成一条 Invoker 链，也就是在前面加上一条 Filter 过滤器链
                     *  2.2 然后将这条 Invoker 链和 ExporterListener 监听器数组封装起来，目的就是执行这些监听器
                     *
                     * 3. 最后由 DubboProtocol 进行暴露，返回一个 DubboExporter 对象
                     *
                     * 4. 同时会启动通信服务器，例如 {@link org.apache.dubbo.remoting.transport.netty4.NettyServer} 服务器，监听某个端口，处理远程发过来的请求
                     */
                    Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                    // 将得到的 Export 对象放入 `exporters` 集合中
                    exporters.add(exporter);
                }

                // 将 Dubbo 服务提供者的元数据信息保存至远程
                // 例如 zookeeper，会创建一个永久节点 `/dubbo/metedata/服务名称/版本号/providers/应用名称`，节点数据就是该服务的元数据
                // 也会创建 `/dubbo/config/mapping/服务名称/应用名称` 永久节点
                // 同时会将元数据信息缓存至本地的 `用户目录/.dubbo/dubbo-metedata-xxx.properties` 文件
                MetadataUtils.publishServiceDefinition(url);
            }
        }
        // 将这个 Dubbo 服务提供者的 URL 对象保存
        this.urls.add(url);
    }



    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * always export injvm 本地暴露服务
     */
    private void exportLocal(URL url) {
        // 创建一个 injvm 协议的 URL 对象
        // 例如，这里得到：injvm://127.0.0.1/org.apache.dubbo.demo.service.StudyService?anyhost=true&application=demo-provider
        // &bind.ip=192.168.222.61&bind.port=9090&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false
        // &interface=org.apache.dubbo.demo.service.StudyService&metadata-type=remote
        // &methods=researchSpring,researchDubbo&pid=21100&release=2.7.8&revision=1.0.0&side=provider&timestamp=1627003769406&version=1.0.0
        URL local = URLBuilder.from(url)
                .setProtocol(LOCAL_PROTOCOL)
                .setHost(LOCALHOST_VALUE)
                .setPort(0)
                .build();

        /**
         * 1. 先借助 ProxyFactory 扩展点实现类的自适应对象 {@link org.apache.dubbo.rpc.ProxyFactory$Adaptive} 获取一个 Invoker 对象
         *
         * {@link JavassistProxyFactory}（默认）创建一个 {@link AbstractProxyInvoker} 对象
         * 会先通过 Javassist 动态创建一个 Wrapper 包装类，执行 invokeMethod(..) 即可执行 `ref` 的方法
         * 这个 Invoker 对象则可通过 Result invoke(Invocation invocation) 方法执行 `ref` 的方法
         *
         * {@link JdkProxyFactory} 就比较简单，创建的 {@link AbstractProxyInvoker} 对象就是通过反射执行 `ref` 的方法，效率相比 Javassist 没有那么好
         *
         * 2. 再借助 Protocol 扩展点实现类的自适应对象  {@link org.apache.dubbo.rpc.Protocol$Adaptive} 获取一个 Exporter 对象
         * 先通过 Dubbo SPI 根据协议名称获取到对应的 Protocol 扩展点实现类，injvm 得到的是不是 {@link InjvmProtocol} 呢？
         * 其实不然，对于 Protocol 还有 Filter 和 ExportListener 两种 Wrapper 包装类，会将 {@link InjvmProtocol} 包装起来
         *
         *  1. 首先在 {@link ProtocolFilterWrapper} 中会先将 Invoker 转换成一条 Invoker 链，也就是在前面加上一条 Filter 过滤器链
         *  2. 然后在 {@link ProtocolListenerWrapper} 中将这条 Invoker 链和 ExporterListener 监听器数组封装起来，目的就是执行这些监听器
         *  3. 最后由 {@link InjvmProtocol} 返回一个 {@link org.apache.dubbo.rpc.protocol.injvm.InjvmExporter} 对象
         */
        Exporter<?> exporter = PROTOCOL.export(PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, local));

        // 将这个 InjvmExporter 对象保存起来
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1
                && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }


    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig,
                                     List<URL> registryURLs,
                                     Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    logger.info("No valid ip found from environment, try to find valid host from DNS.");
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (CollectionUtils.isNotEmpty(registryURLs)) {
                        for (URL registryURL : registryURLs) {
                            if (MULTICAST.equalsIgnoreCase(registryURL.getParameter(REGISTRY_KEY))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            if (MULTIPLE.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip, multiple-registry address is a fake ip
                                continue;
                            }
                            try (Socket socket = new Socket()) {
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                hostToBind = socket.getLocalAddress().getHostAddress();
                                break;
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                    "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }


    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @param protocolConfigNum
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig,
                                      String name,
                                      Map<String, String> map, int protocolConfigNum) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // registry port, not used as bind port by default
        String key = DUBBO_PORT_TO_REGISTRY;
        if (protocolConfigNum > 1) {
            key = getProtocolConfigId(protocolConfig).toUpperCase() + "_" + key;
        }
        String portToRegistryStr = getValueFromConfig(protocolConfig, key);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        // save bind port, used as url's key later
        map.put(BIND_PORT_KEY, String.valueOf(portToRegistry));

        return portToRegistry;
    }


    private String getProtocolConfigId(ProtocolConfig config) {
        return Optional.ofNullable(config.getId()).orElse(DUBBO);
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String value = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(value)) {
            value = ConfigUtils.getSystemProperty(key);
        }
        return value;
    }

    private Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf(CONFIG_POST_PROCESSOR_PROTOCOL), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessServiceConfig(this));
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    private void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String getServiceName() {

        if (!StringUtils.isBlank(serviceName)) {
            return serviceName;
        }
        String generateVersion = version;
        String generateGroup = group;

        if (StringUtils.isBlank(version) && provider != null) {
            generateVersion = provider.getVersion();
        }

        if (StringUtils.isBlank(group) && provider != null) {
            generateGroup = provider.getGroup();
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("ServiceBean:");

        if (!StringUtils.isBlank(generateGroup)) {
            stringBuilder.append(generateGroup);
        }

        stringBuilder.append("/").append(interfaceName);

        if (!StringUtils.isBlank(generateVersion)) {
            stringBuilder.append(":").append(generateVersion);
        }

        serviceName = stringBuilder.toString();
        return serviceName;
    }
}
