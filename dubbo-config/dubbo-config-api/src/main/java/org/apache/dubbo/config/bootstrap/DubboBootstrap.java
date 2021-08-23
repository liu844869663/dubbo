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
package org.apache.dubbo.config.bootstrap;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.config.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.common.config.configcenter.wrapper.CompositeDynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.lang.ShutdownHookCallbacks;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.concurrent.ScheduledCompletableFuture;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.ServiceConfigBase;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.config.bootstrap.builders.ApplicationBuilder;
import org.apache.dubbo.config.bootstrap.builders.ConsumerBuilder;
import org.apache.dubbo.config.bootstrap.builders.ProtocolBuilder;
import org.apache.dubbo.config.bootstrap.builders.ProviderBuilder;
import org.apache.dubbo.config.bootstrap.builders.ReferenceBuilder;
import org.apache.dubbo.config.bootstrap.builders.RegistryBuilder;
import org.apache.dubbo.config.bootstrap.builders.ServiceBuilder;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.metadata.ConfigurableMetadataServiceExporter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceExporter;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.metadata.report.MetadataReportFactory;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.metadata.report.support.AbstractMetadataReportFactory;
import org.apache.dubbo.registry.client.DefaultServiceInstance;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.ServiceInstanceCustomizer;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.registry.client.metadata.store.InMemoryWritableMetadataService;
import org.apache.dubbo.registry.client.metadata.store.RemoteMetadataServiceImpl;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.config.configcenter.DynamicConfiguration.getDynamicConfiguration;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;
import static org.apache.dubbo.common.function.ThrowableAction.execute;
import static org.apache.dubbo.common.utils.StringUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isNotEmpty;
import static org.apache.dubbo.metadata.MetadataConstants.DEFAULT_METADATA_PUBLISH_DELAY;
import static org.apache.dubbo.metadata.MetadataConstants.METADATA_PUBLISH_DELAY_KEY;
import static org.apache.dubbo.metadata.WritableMetadataService.getDefaultExtension;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.calInstanceRevision;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.setMetadataStorageType;
import static org.apache.dubbo.registry.support.AbstractRegistryFactory.getServiceDiscoveries;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;

/**
 * See {@link ApplicationModel} and {@link ExtensionLoader} for why this class is designed to be singleton.
 * <p>
 * The bootstrap class of Dubbo
 * <p>
 * Get singleton instance by calling static method {@link #getInstance()}.
 * Designed as singleton because some classes inside Dubbo, such as ExtensionLoader, are designed only for one instance per process.
 *
 * @since 2.7.5
 */
public class DubboBootstrap {

    public static final String DEFAULT_REGISTRY_ID = "REGISTRY#DEFAULT";

    public static final String DEFAULT_PROTOCOL_ID = "PROTOCOL#DEFAULT";

    public static final String DEFAULT_SERVICE_ID = "SERVICE#DEFAULT";

    public static final String DEFAULT_REFERENCE_ID = "REFERENCE#DEFAULT";

    public static final String DEFAULT_PROVIDER_ID = "PROVIDER#DEFAULT";

    public static final String DEFAULT_CONSUMER_ID = "CONSUMER#DEFAULT";

    /**
     * DubboBootstrap 名称
     */
    private static final String NAME = DubboBootstrap.class.getSimpleName();

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * DubboBootstrap 单例对象
     */
    private static volatile DubboBootstrap instance;

    private final AtomicBoolean awaited = new AtomicBoolean(false);

    private final Lock lock = new ReentrantLock();

    private final Condition condition = lock.newCondition();

    /**
     * 销毁时的可重入锁
     */
    private final Lock destroyLock = new ReentrantLock();

    /**
     * 单线程的线程池
     */
    private final ExecutorService executorService = newSingleThreadExecutor();

    private final ExecutorRepository executorRepository = getExtensionLoader(ExecutorRepository.class).getDefaultExtension();

    /**
     * 配置类管理器
     */
    private final ConfigManager configManager;

    /**
     * 环境对象
     */
    private final Environment environment;

    /**
     * 缓存消费者已经完成引用服务的配置类
     */
    private ReferenceConfigCache cache;

    /**
     * 是否异步暴露服务
     */
    private volatile boolean exportAsync;

    /**
     * 是否异步引用服务
     */
    private volatile boolean referAsync;

    /**
     * 是否已完成相关初始化工作
     */
    private AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * 是否已启动
     */
    private AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 是否已准备就绪
     */
    private AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * 销毁状态
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile ServiceInstance serviceInstance;

    private volatile MetadataService metadataService;

    private volatile MetadataServiceExporter metadataServiceExporter;

    /**
     * 已经暴露的 Dubbo 服务提供者对应的配置类
     */
    private Map<String, ServiceConfigBase<?>> exportedServices = new ConcurrentHashMap<>();

    /**
     * 异步暴露 Dubbo 服务提供者的 Future 对象
     */
    private List<Future<?>> asyncExportingFutures = new ArrayList<>();

    /**
     * 异步引用 Dubbo 服务的 Future 对象
     */
    private List<CompletableFuture<Object>> asyncReferringFutures = new ArrayList<>();

    /**
     * See {@link ApplicationModel} and {@link ExtensionLoader} for why DubboBootstrap is designed to be singleton.
     */
    public static DubboBootstrap getInstance() {
        if (instance == null) {
            synchronized (DubboBootstrap.class) {
                if (instance == null) {
                    instance = new DubboBootstrap();
                }
            }
        }
        return instance;
    }

    private DubboBootstrap() {
        configManager = ApplicationModel.getConfigManager();
        environment = ApplicationModel.getEnvironment();

        // 向 JVM 注册一个 DubboShutdownHook 钩子函数，当 JVM 关闭时触发这个任务，以实现优雅停机
        // 提示：kill -9 不会触发
        DubboShutdownHook.getDubboShutdownHook().register();
        // 添加一个回调，当触发上面的钩子函数时，会执行这个回调，也就是执行当前 `this#destroy()` 销毁方法
        ShutdownHookCallbacks.INSTANCE.addCallback(DubboBootstrap.this::destroy);
    }

    public void unRegisterShutdownHook() {
        DubboShutdownHook.getDubboShutdownHook().unregister();
    }

    private boolean isOnlyRegisterProvider() {
        Boolean registerConsumer = getApplication().getRegisterConsumer();
        return registerConsumer == null || !registerConsumer;
    }

    private String getMetadataType() {
        String type = getApplication().getMetadataType();
        if (StringUtils.isEmpty(type)) {
            type = DEFAULT_METADATA_STORAGE_TYPE;
        }
        return type;
    }

    public DubboBootstrap metadataReport(MetadataReportConfig metadataReportConfig) {
        configManager.addMetadataReport(metadataReportConfig);
        return this;
    }

    public DubboBootstrap metadataReports(List<MetadataReportConfig> metadataReportConfigs) {
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            return this;
        }

        configManager.addMetadataReports(metadataReportConfigs);
        return this;
    }

    // {@link ApplicationConfig} correlative methods

    /**
     * Set the name of application
     *
     * @param name the name of application
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(String name) {
        return application(name, builder -> {
            // DO NOTHING
        });
    }

    /**
     * Set the name of application and it's future build
     *
     * @param name            the name of application
     * @param consumerBuilder {@link ApplicationBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(String name, Consumer<ApplicationBuilder> consumerBuilder) {
        ApplicationBuilder builder = createApplicationBuilder(name);
        consumerBuilder.accept(builder);
        return application(builder.build());
    }

    /**
     * Set the {@link ApplicationConfig}
     *
     * @param applicationConfig the {@link ApplicationConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(ApplicationConfig applicationConfig) {
        configManager.setApplication(applicationConfig);
        return this;
    }


    // {@link RegistryConfig} correlative methods

    /**
     * Add an instance of {@link RegistryConfig} with {@link #DEFAULT_REGISTRY_ID default ID}
     *
     * @param consumerBuilder the {@link Consumer} of {@link RegistryBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(Consumer<RegistryBuilder> consumerBuilder) {
        return registry(DEFAULT_REGISTRY_ID, consumerBuilder);
    }

    /**
     * Add an instance of {@link RegistryConfig} with the specified ID
     *
     * @param id              the {@link RegistryConfig#getId() id}  of {@link RegistryConfig}
     * @param consumerBuilder the {@link Consumer} of {@link RegistryBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(String id, Consumer<RegistryBuilder> consumerBuilder) {
        RegistryBuilder builder = createRegistryBuilder(id);
        consumerBuilder.accept(builder);
        return registry(builder.build());
    }

    /**
     * Add an instance of {@link RegistryConfig}
     *
     * @param registryConfig an instance of {@link RegistryConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(RegistryConfig registryConfig) {
        configManager.addRegistry(registryConfig);
        return this;
    }

    /**
     * Add an instance of {@link RegistryConfig}
     *
     * @param registryConfigs the multiple instances of {@link RegistryConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registries(List<RegistryConfig> registryConfigs) {
        if (CollectionUtils.isEmpty(registryConfigs)) {
            return this;
        }
        registryConfigs.forEach(this::registry);
        return this;
    }


    // {@link ProtocolConfig} correlative methods
    public DubboBootstrap protocol(Consumer<ProtocolBuilder> consumerBuilder) {
        return protocol(DEFAULT_PROTOCOL_ID, consumerBuilder);
    }

    public DubboBootstrap protocol(String id, Consumer<ProtocolBuilder> consumerBuilder) {
        ProtocolBuilder builder = createProtocolBuilder(id);
        consumerBuilder.accept(builder);
        return protocol(builder.build());
    }

    public DubboBootstrap protocol(ProtocolConfig protocolConfig) {
        return protocols(singletonList(protocolConfig));
    }

    public DubboBootstrap protocols(List<ProtocolConfig> protocolConfigs) {
        if (CollectionUtils.isEmpty(protocolConfigs)) {
            return this;
        }
        configManager.addProtocols(protocolConfigs);
        return this;
    }

    // {@link ServiceConfig} correlative methods
    public <S> DubboBootstrap service(Consumer<ServiceBuilder<S>> consumerBuilder) {
        return service(DEFAULT_SERVICE_ID, consumerBuilder);
    }

    public <S> DubboBootstrap service(String id, Consumer<ServiceBuilder<S>> consumerBuilder) {
        ServiceBuilder builder = createServiceBuilder(id);
        consumerBuilder.accept(builder);
        return service(builder.build());
    }

    public DubboBootstrap service(ServiceConfig<?> serviceConfig) {
        configManager.addService(serviceConfig);
        return this;
    }

    public DubboBootstrap services(List<ServiceConfig> serviceConfigs) {
        if (CollectionUtils.isEmpty(serviceConfigs)) {
            return this;
        }
        serviceConfigs.forEach(configManager::addService);
        return this;
    }

    // {@link Reference} correlative methods
    public <S> DubboBootstrap reference(Consumer<ReferenceBuilder<S>> consumerBuilder) {
        return reference(DEFAULT_REFERENCE_ID, consumerBuilder);
    }

    public <S> DubboBootstrap reference(String id, Consumer<ReferenceBuilder<S>> consumerBuilder) {
        ReferenceBuilder builder = createReferenceBuilder(id);
        consumerBuilder.accept(builder);
        return reference(builder.build());
    }

    public DubboBootstrap reference(ReferenceConfig<?> referenceConfig) {
        configManager.addReference(referenceConfig);
        return this;
    }

    public DubboBootstrap references(List<ReferenceConfig> referenceConfigs) {
        if (CollectionUtils.isEmpty(referenceConfigs)) {
            return this;
        }

        referenceConfigs.forEach(configManager::addReference);
        return this;
    }

    // {@link ProviderConfig} correlative methods
    public DubboBootstrap provider(Consumer<ProviderBuilder> builderConsumer) {
        return provider(DEFAULT_PROVIDER_ID, builderConsumer);
    }

    public DubboBootstrap provider(String id, Consumer<ProviderBuilder> builderConsumer) {
        ProviderBuilder builder = createProviderBuilder(id);
        builderConsumer.accept(builder);
        return provider(builder.build());
    }

    public DubboBootstrap provider(ProviderConfig providerConfig) {
        return providers(singletonList(providerConfig));
    }

    public DubboBootstrap providers(List<ProviderConfig> providerConfigs) {
        if (CollectionUtils.isEmpty(providerConfigs)) {
            return this;
        }

        providerConfigs.forEach(configManager::addProvider);
        return this;
    }

    // {@link ConsumerConfig} correlative methods
    public DubboBootstrap consumer(Consumer<ConsumerBuilder> builderConsumer) {
        return consumer(DEFAULT_CONSUMER_ID, builderConsumer);
    }

    public DubboBootstrap consumer(String id, Consumer<ConsumerBuilder> builderConsumer) {
        ConsumerBuilder builder = createConsumerBuilder(id);
        builderConsumer.accept(builder);
        return consumer(builder.build());
    }

    public DubboBootstrap consumer(ConsumerConfig consumerConfig) {
        return consumers(singletonList(consumerConfig));
    }

    public DubboBootstrap consumers(List<ConsumerConfig> consumerConfigs) {
        if (CollectionUtils.isEmpty(consumerConfigs)) {
            return this;
        }

        consumerConfigs.forEach(configManager::addConsumer);
        return this;
    }

    // {@link ConfigCenterConfig} correlative methods
    public DubboBootstrap configCenter(ConfigCenterConfig configCenterConfig) {
        return configCenters(singletonList(configCenterConfig));
    }

    public DubboBootstrap configCenters(List<ConfigCenterConfig> configCenterConfigs) {
        if (CollectionUtils.isEmpty(configCenterConfigs)) {
            return this;
        }
        configManager.addConfigCenters(configCenterConfigs);
        return this;
    }

    public DubboBootstrap monitor(MonitorConfig monitor) {
        configManager.setMonitor(monitor);
        return this;
    }

    public DubboBootstrap metrics(MetricsConfig metrics) {
        configManager.setMetrics(metrics);
        return this;
    }

    public DubboBootstrap module(ModuleConfig module) {
        configManager.setModule(module);
        return this;
    }

    public DubboBootstrap ssl(SslConfig sslConfig) {
        configManager.setSsl(sslConfig);
        return this;
    }

    public DubboBootstrap cache(ReferenceConfigCache cache) {
        this.cache = cache;
        return this;
    }

    public ReferenceConfigCache getCache() {
        if (cache == null) {
            cache = ReferenceConfigCache.getCache();
        }
        return cache;
    }

    public DubboBootstrap exportAsync() {
        this.exportAsync = true;
        return this;
    }

    public DubboBootstrap referAsync() {
        this.referAsync = true;
        return this;
    }

    @Deprecated
    public void init() {
        initialize();
    }

    /**
     * Initialize
     */
    public void initialize() {
        // 设置为已完成相关初始化工作，已经初始化则跳过
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        // 执行所有的 FrameworkExt 框架扩展类的初始化工作
        // 例如 Environment 初始化会读取配置中心的配置
        ApplicationModel.initFrameworkExts();

        // 启动配置中心
        startConfigCenter();

        // 加载远程的配置
        loadRemoteConfigs();

        // 检查全局配置
        checkGlobalConfigs();

        // @since 2.7.8
        // 启动元数据中心，默认使用注册中心作为元数据中心
        startMetadataCenter();

        // 初始化元数据服务，默认使用 InMemoryWritableMetadataService 内存操作
        initMetadataService();

        if (logger.isInfoEnabled()) {
            logger.info(NAME + " has been initialized!");
        }
    }

    private void checkGlobalConfigs() {
        // check Application
        ConfigValidationUtils.validateApplicationConfig(getApplication());

        // check Metadata
        Collection<MetadataReportConfig> metadatas = configManager.getMetadataConfigs();
        if (CollectionUtils.isEmpty(metadatas)) {
            MetadataReportConfig metadataReportConfig = new MetadataReportConfig();
            metadataReportConfig.refresh();
            if (metadataReportConfig.isValid()) {
                configManager.addMetadataReport(metadataReportConfig);
                metadatas = configManager.getMetadataConfigs();
            }
        }
        if (CollectionUtils.isNotEmpty(metadatas)) {
            for (MetadataReportConfig metadataReportConfig : metadatas) {
                metadataReportConfig.refresh();
                ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
            }
        }

        // check Provider
        Collection<ProviderConfig> providers = configManager.getProviders();
        if (CollectionUtils.isEmpty(providers)) {
            configManager.getDefaultProvider().orElseGet(() -> {
                ProviderConfig providerConfig = new ProviderConfig();
                configManager.addProvider(providerConfig);
                providerConfig.refresh();
                return providerConfig;
            });
        }
        for (ProviderConfig providerConfig : configManager.getProviders()) {
            ConfigValidationUtils.validateProviderConfig(providerConfig);
        }
        // check Consumer
        Collection<ConsumerConfig> consumers = configManager.getConsumers();
        if (CollectionUtils.isEmpty(consumers)) {
            configManager.getDefaultConsumer().orElseGet(() -> {
                ConsumerConfig consumerConfig = new ConsumerConfig();
                configManager.addConsumer(consumerConfig);
                consumerConfig.refresh();
                return consumerConfig;
            });
        }
        for (ConsumerConfig consumerConfig : configManager.getConsumers()) {
            ConfigValidationUtils.validateConsumerConfig(consumerConfig);
        }

        // check Monitor
        ConfigValidationUtils.validateMonitorConfig(getMonitor());
        // check Metrics
        ConfigValidationUtils.validateMetricsConfig(getMetrics());
        // check Module
        ConfigValidationUtils.validateModuleConfig(getModule());
        // check Ssl
        ConfigValidationUtils.validateSslConfig(getSsl());
    }

    private void startConfigCenter() {

        // 使用注册中心作为配置中心（如果没有指定配置中心）
        useRegistryAsConfigCenterIfNecessary();

        Collection<ConfigCenterConfig> configCenters = configManager.getConfigCenters();

        // check Config Center
        if (CollectionUtils.isEmpty(configCenters)) {
            ConfigCenterConfig configCenterConfig = new ConfigCenterConfig();
            configCenterConfig.refresh();
            if (configCenterConfig.isValid()) {
                configManager.addConfigCenter(configCenterConfig);
                configCenters = configManager.getConfigCenters();
            }
        } else {
            for (ConfigCenterConfig configCenterConfig : configCenters) {
                configCenterConfig.refresh();
                ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            }
        }

        if (CollectionUtils.isNotEmpty(configCenters)) {
            CompositeDynamicConfiguration compositeDynamicConfiguration = new CompositeDynamicConfiguration();
            for (ConfigCenterConfig configCenter : configCenters) {
                compositeDynamicConfiguration.addConfiguration(prepareEnvironment(configCenter));
            }
            environment.setDynamicConfiguration(compositeDynamicConfiguration);
        }
        configManager.refreshAll();
    }

    private void startMetadataCenter() {

        useRegistryAsMetadataCenterIfNecessary();

        ApplicationConfig applicationConfig = getApplication();

        String metadataType = applicationConfig.getMetadataType();
        // FIXME, multiple metadata config support.
        Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            if (REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                throw new IllegalStateException("No MetadataConfig found, Metadata Center address is required when 'metadata=remote' is enabled.");
            }
            return;
        }

        for (MetadataReportConfig metadataReportConfig : metadataReportConfigs) {
            ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
            if (!metadataReportConfig.isValid()) {
                return;
            }
            MetadataReportInstance.init(metadataReportConfig);
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center when
     * there's no config center specified explicitly and
     * useAsConfigCenter of registryConfig is null or true
     */
    private void useRegistryAsConfigCenterIfNecessary() {
        // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
        if (environment.getDynamicConfiguration().isPresent()) {
            return;
        }

        if (CollectionUtils.isNotEmpty(configManager.getConfigCenters())) {
            return;
        }

        configManager
                .getDefaultRegistries()
                .stream()
                .filter(this::isUsedRegistryAsConfigCenter)
                .map(this::registryAsConfigCenter)
                .forEach(configManager::addConfigCenter);
    }

    private boolean isUsedRegistryAsConfigCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsConfigCenter, "config",
                DynamicConfigurationFactory.class);
    }

    private ConfigCenterConfig registryAsConfigCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        Integer port = registryConfig.getPort();
        String id = "config-center-" + protocol + "-" + port;
        if (configManager.getConfigCenter(id) != null) {
            return null;
        }

        ConfigCenterConfig cc = new ConfigCenterConfig();
        cc.setId(id);
        if (cc.getParameters() == null) {
            cc.setParameters(new HashMap<>());
        }
        if (registryConfig.getParameters() != null) {
            cc.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        cc.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        cc.setProtocol(protocol);
        cc.setPort(port);
        if (StringUtils.isNotEmpty(registryConfig.getGroup())) {
            cc.setGroup(registryConfig.getGroup());
        }
        cc.setAddress(getRegistryCompatibleAddress(registryConfig));
        cc.setNamespace(registryConfig.getGroup());
        cc.setUsername(registryConfig.getUsername());
        cc.setPassword(registryConfig.getPassword());
        if (registryConfig.getTimeout() != null) {
            cc.setTimeout(registryConfig.getTimeout().longValue());
        }
        cc.setHighestPriority(false);
        return cc;
    }

    private void useRegistryAsMetadataCenterIfNecessary() {

        Collection<MetadataReportConfig> metadataConfigs = configManager.getMetadataConfigs();

        if (CollectionUtils.isNotEmpty(metadataConfigs)) {
            return;
        }

        configManager
                .getDefaultRegistries()
                .stream()
                .filter(this::isUsedRegistryAsMetadataCenter)
                .map(this::registryAsMetadataCenter)
                .forEach(configManager::addMetadataReport);

    }

    private boolean isUsedRegistryAsMetadataCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsMetadataCenter, "metadata",
                MetadataReportFactory.class);
    }

    /**
     * Is used the specified registry as a center infrastructure
     *
     * @param registryConfig       the {@link RegistryConfig}
     * @param usedRegistryAsCenter the configured value on
     * @param centerType           the type name of center
     * @param extensionClass       an extension class of a center infrastructure
     * @return
     * @since 2.7.8
     */
    private boolean isUsedRegistryAsCenter(RegistryConfig registryConfig, Supplier<Boolean> usedRegistryAsCenter,
                                           String centerType,
                                           Class<?> extensionClass) {
        final boolean supported;

        Boolean configuredValue = usedRegistryAsCenter.get();
        if (configuredValue != null) { // If configured, take its value.
            supported = configuredValue.booleanValue();
        } else {                       // Or check the extension existence
            String protocol = registryConfig.getProtocol();
            supported = supportsExtension(extensionClass, protocol);
            if (logger.isInfoEnabled()) {
                logger.info(format("No value is configured in the registry, the %s extension[name : %s] %s as the %s center"
                        , extensionClass.getSimpleName(), protocol, supported ? "supports" : "does not support", centerType));
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info(format("The registry[%s] will be %s as the %s center", registryConfig,
                    supported ? "used" : "not used", centerType));
        }
        return supported;
    }

    /**
     * Supports the extension with the specified class and name
     *
     * @param extensionClass the {@link Class} of extension
     * @param name           the name of extension
     * @return if supports, return <code>true</code>, or <code>false</code>
     * @since 2.7.8
     */
    private boolean supportsExtension(Class<?> extensionClass, String name) {
        if (isNotEmpty(name)) {
            ExtensionLoader extensionLoader = getExtensionLoader(extensionClass);
            return extensionLoader.hasExtension(name);
        }
        return false;
    }

    private MetadataReportConfig registryAsMetadataCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        Integer port = registryConfig.getPort();
        String id = "metadata-center-" + protocol + "-" + port;
        if (configManager.getMetadataConfig(id) != null) {
            return null;
        }

        MetadataReportConfig metadataReportConfig = new MetadataReportConfig();
        metadataReportConfig.setId(id);
        if (metadataReportConfig.getParameters() == null) {
            metadataReportConfig.setParameters(new HashMap<>());
        }
        if (registryConfig.getParameters() != null) {
            metadataReportConfig.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        metadataReportConfig.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        metadataReportConfig.setGroup(registryConfig.getGroup());
        metadataReportConfig.setAddress(getRegistryCompatibleAddress(registryConfig));
        metadataReportConfig.setUsername(registryConfig.getUsername());
        metadataReportConfig.setPassword(registryConfig.getPassword());
        metadataReportConfig.setTimeout(registryConfig.getTimeout());
        return metadataReportConfig;
    }

    private String getRegistryCompatibleAddress(RegistryConfig registryConfig) {
        String registryAddress = registryConfig.getAddress();
        String[] addresses = REGISTRY_SPLIT_PATTERN.split(registryAddress);
        if (ArrayUtils.isEmpty(addresses)) {
            throw new IllegalStateException("Invalid registry address found.");
        }
        String address = addresses[0];
        // since 2.7.8
        // Issue : https://github.com/apache/dubbo/issues/6476
        StringBuilder metadataAddressBuilder = new StringBuilder();
        URL url = URL.valueOf(address);
        String protocolFromAddress = url.getProtocol();
        if (isEmpty(protocolFromAddress)) {
            // If the protocol from address is missing, is like :
            // "dubbo.registry.address = 127.0.0.1:2181"
            String protocolFromConfig = registryConfig.getProtocol();
            metadataAddressBuilder.append(protocolFromConfig).append("://");
        }
        metadataAddressBuilder.append(address);
        return metadataAddressBuilder.toString();
    }

    private void loadRemoteConfigs() {
        // registry ids to registry configs
        List<RegistryConfig> tmpRegistries = new ArrayList<>();
        Set<String> registryIds = configManager.getRegistryIds();
        registryIds.forEach(id -> {
            if (tmpRegistries.stream().noneMatch(reg -> reg.getId().equals(id))) {
                tmpRegistries.add(configManager.getRegistry(id).orElseGet(() -> {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setId(id);
                    registryConfig.refresh();
                    return registryConfig;
                }));
            }
        });

        configManager.addRegistries(tmpRegistries);

        // protocol ids to protocol configs
        List<ProtocolConfig> tmpProtocols = new ArrayList<>();
        Set<String> protocolIds = configManager.getProtocolIds();
        protocolIds.forEach(id -> {
            if (tmpProtocols.stream().noneMatch(prot -> prot.getId().equals(id))) {
                tmpProtocols.add(configManager.getProtocol(id).orElseGet(() -> {
                    ProtocolConfig protocolConfig = new ProtocolConfig();
                    protocolConfig.setId(id);
                    protocolConfig.refresh();
                    return protocolConfig;
                }));
            }
        });

        configManager.addProtocols(tmpProtocols);
    }


    /**
     * Initialize {@link MetadataService} from {@link WritableMetadataService}'s extension
     */
    private void initMetadataService() {
        this.metadataService = getDefaultExtension();
        this.metadataServiceExporter = new ConfigurableMetadataServiceExporter(metadataService);
    }

    /**
     * 在启动之前 Dubbo 应用之前，Spring 应用上下文会有处理器解析所有的 Dubbo 服务并注册，同时会注册一个对应的 AbstractConfig 配置类
     * 参考 ServiceClassPostProcessor、ReferenceAnnotationBeanPostProcessor 两个处理器和 DubboNamespaceHandler `<dubbo />` 标签处理器
     *
     * 在 AbstractConfig#addIntoConfigManager 方法（`@PostConstruct` 标注）中会将这个配置类添加到 ConfigManager 配置管理器
     * 所以这里可以通过 ConfigManager#configsCache 获取到所有 Dubbo 服务信息
     *
     * Start the bootstrap
     */
    public DubboBootstrap start() {
        // Dubbo 引用启动状态由 `false` 修改为 `true`
        if (started.compareAndSet(false, true)) {
            // 设置还未准备就绪
            ready.set(false);
            // 进行初始化工作，配置中心和元数据中心
            initialize();
            if (logger.isInfoEnabled()) {
                logger.info(NAME + " is starting...");
            }
            /**
             * 1. 暴露所有的 Dubbo 服务提供者
             * 也就是调用其 {@link ServiceConfig#export() 方法，这一步做了非常多的工作，例如会创建 Invoker 和 Export 对象，向注册中心注册等等
             */
            exportServices();

            // Not only provider register
            if (!isOnlyRegisterProvider() || hasExportedServices()) {
                // 2. export MetadataService
                exportMetadataService();
                //3. Register the local ServiceInstance if required
                registerServiceInstance();
            }

            /**
             * 引用服务
             */
            referServices();
            // 如果存在异步暴露服务
            if (asyncExportingFutures.size() > 0) {
                new Thread(() -> {
                    try {
                        // 等待全部完成
                        this.awaitFinish();
                    } catch (Exception e) {
                        logger.warn(NAME + " exportAsync occurred an exception.");
                    }
                    // 设置准备就绪
                    ready.set(true);
                    if (logger.isInfoEnabled()) {
                        logger.info(NAME + " is ready.");
                    }
                    // Dubbo SPI 获取 DubboBootstrapStartStopListener 监听器，触发启动事件
                    ExtensionLoader<DubboBootstrapStartStopListener> exts = getExtensionLoader(DubboBootstrapStartStopListener.class);
                    exts.getSupportedExtensionInstances().forEach(ext -> ext.onStart(this));
                }).start();
            } else {
                // 设置准备就绪
                ready.set(true);
                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " is ready.");
                }
                // Dubbo SPI 获取 DubboBootstrapStartStopListener 监听器，触发启动事件
                ExtensionLoader<DubboBootstrapStartStopListener> exts = getExtensionLoader(DubboBootstrapStartStopListener.class);
                exts.getSupportedExtensionInstances().forEach(ext -> ext.onStart(this));
            }
            if (logger.isInfoEnabled()) {
                logger.info(NAME + " has started.");
            }
        }
        return this;
    }

    private boolean hasExportedServices() {
        return !metadataService.getExportedURLs().isEmpty();
    }

    /**
     * Block current thread to be await.
     *
     * @return {@link DubboBootstrap}
     */
    public DubboBootstrap await() {
        // if has been waited, no need to wait again, return immediately
        if (!awaited.get()) {
            if (!executorService.isShutdown()) {
                executeMutually(() -> {
                    while (!awaited.get()) {
                        if (logger.isInfoEnabled()) {
                            logger.info(NAME + " awaiting ...");
                        }
                        try {
                            condition.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }
        return this;
    }

    public DubboBootstrap awaitFinish() throws Exception {
        logger.info(NAME + " waiting services exporting / referring ...");
        if (exportAsync && asyncExportingFutures.size() > 0) {
            CompletableFuture future = CompletableFuture.allOf(asyncExportingFutures.toArray(new CompletableFuture[0]));
            future.get();
        }
        if (referAsync && asyncReferringFutures.size() > 0) {
            CompletableFuture future = CompletableFuture.allOf(asyncReferringFutures.toArray(new CompletableFuture[0]));
            future.get();
        }

        logger.info("Service export / refer finished.");
        return this;
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isReady() {
        return ready.get();
    }


    public DubboBootstrap stop() throws IllegalStateException {
        destroy();
        return this;
    }
    /* serve for builder apis, begin */

    private ApplicationBuilder createApplicationBuilder(String name) {
        return new ApplicationBuilder().name(name);
    }

    private RegistryBuilder createRegistryBuilder(String id) {
        return new RegistryBuilder().id(id);
    }

    private ProtocolBuilder createProtocolBuilder(String id) {
        return new ProtocolBuilder().id(id);
    }

    private ServiceBuilder createServiceBuilder(String id) {
        return new ServiceBuilder().id(id);
    }

    private ReferenceBuilder createReferenceBuilder(String id) {
        return new ReferenceBuilder().id(id);
    }

    private ProviderBuilder createProviderBuilder(String id) {
        return new ProviderBuilder().id(id);
    }

    private ConsumerBuilder createConsumerBuilder(String id) {
        return new ConsumerBuilder().id(id);
    }
    /* serve for builder apis, end */

    private DynamicConfiguration prepareEnvironment(ConfigCenterConfig configCenter) {
        if (configCenter.isValid()) {
            if (!configCenter.checkOrUpdateInited()) {
                return null;
            }
            DynamicConfiguration dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            String configContent = dynamicConfiguration.getProperties(configCenter.getConfigFile(), configCenter.getGroup());

            String appGroup = getApplication().getName();
            String appConfigContent = null;
            if (isNotEmpty(appGroup)) {
                appConfigContent = dynamicConfiguration.getProperties
                        (isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                                appGroup
                        );
            }
            try {
                environment.setConfigCenterFirst(configCenter.isHighestPriority());
                Map<String, String> globalRemoteProperties = parseProperties(configContent);
                if (CollectionUtils.isEmptyMap(globalRemoteProperties)) {
                    logger.info("No global configuration in config center");
                }
                environment.updateExternalConfigurationMap(globalRemoteProperties);

                Map<String, String> appRemoteProperties = parseProperties(appConfigContent);
                if (CollectionUtils.isEmptyMap(appRemoteProperties)) {
                    logger.info("No application level configuration in config center");
                }
                environment.updateAppExternalConfigurationMap(appRemoteProperties);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
            return dynamicConfiguration;
        }
        return null;
    }

    /**
     * export {@link MetadataService}
     */
    private void exportMetadataService() {
        metadataServiceExporter.export();
    }

    private void unexportMetadataService() {
        if (metadataServiceExporter != null && metadataServiceExporter.isExported()) {
            metadataServiceExporter.unexport();
        }
    }

    private void exportServices() {
        // 通过配置类管理器获取所有的 Dubbo 服务提供者配置类，也就是所有的 ServiceConfig 配置类
        // 遍历，依次进行暴露
        configManager.getServices().forEach(sc -> {
            // TODO, compatible with ServiceConfig.export()
            ServiceConfig serviceConfig = (ServiceConfig) sc;
            // 设置启动器为当前对象
            serviceConfig.setBootstrap(this);

            if (exportAsync) { // 异步暴露，默认没开启
                // 获取异步暴露服务的线程池
                ExecutorService executor = executorRepository.getServiceExporterExecutor();
                // 将暴露这个服务的任务放入线程池中进行
                Future<?> future = executor.submit(() -> {
                    try {
                        exportService(serviceConfig);
                    } catch (Throwable t) {
                        logger.error("export async catch error : " + t.getMessage(), t);
                    }
                });
                // 异步处理的 Future 结果缓存起来
                asyncExportingFutures.add(future);
            } else { // 否则，阻塞暴露
                /**
                 * 暴露这个 Dubbo 服务提供者，也就是调用这个 {@link ServiceConfig#export() 方法
                 * 这一步做了非常多的工作，例如会创建 Invoker 和 Export 对象，向注册中心注册等等
                 */
                exportService(serviceConfig);
            }
        });
    }

    private void exportService(ServiceConfig sc) {
        // 如果已经暴露了这个服务，则抛出异常
        if (exportedServices.containsKey(sc.getServiceName())) {
            throw new IllegalStateException("There are multiple ServiceBean instances with the same service name: [" +
                    sc.getServiceName() + "], instances: [" +
                    exportedServices.get(sc.getServiceName()).toString() + ", " +
                    sc.toString() + "]. Only one service can be exported for the same triple (group, interface, version), " +
                    "please modify the group or version if you really need to export multiple services of the same interface.");
        }
        // 【关键】暴露服务
        sc.export();
        // 将这个已暴露的服务放入缓存中
        exportedServices.put(sc.getServiceName(), sc);
    }

    private void unexportServices() {
        // 遍历已经暴露的 Dubbo 服务提供者对应的配置类
        exportedServices.forEach((serviceName, sc) -> {
            // 从配置管理器中删除
            configManager.removeConfig(sc);
            // 销毁这个 Dubbo 服务
            sc.unexport();
        });

        // 如果还有正在异步暴露服务的任务，则取消
        asyncExportingFutures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        // 清理资源
        asyncExportingFutures.clear();
        exportedServices.clear();
    }

    private void referServices() {
        // 获取引用服务配置类的缓存对象
        if (cache == null) {
            cache = ReferenceConfigCache.getCache();
        }

        /**
         * 通过配置类管理器获取所有的服务消费者的配置类，也就是所有的 ReferenceConfig 配置类，遍历，依次进行引用
         * 参考 ReferenceAnnotationBeanPostProcessor，有些通过 `@DubboReference` 注解标注的服务已经完成引用了，在 ConfigManager 中不会有缓存
         *
         * 通过 `<dubbo:reference />` 标签引用的服务的配置类会添加至 ConfigManager 缓存中
         * 参考它的 `init` 配置（默认 false），是否在 ReferenceBean#afterPropertiesSet() 时饥饿初始化引用，否则等到有人注入或引用该实例时再初始化
         * 所以说，一般在注入这个对象的时候已经初始化，完成引用了
         */
        configManager.getReferences().forEach(rc -> {
            // TODO, compatible with  ReferenceConfig.refer()
            ReferenceConfig referenceConfig = (ReferenceConfig) rc;
            // 设置启动器为当前对象
            referenceConfig.setBootstrap(this);

            // 应该初始化，也就是配置了 `init=true`，默认不需要
            if (rc.shouldInit()) {
                if (referAsync) { // 异步处理
                    CompletableFuture<Object> future = ScheduledCompletableFuture.submit(
                            executorRepository.getServiceExporterExecutor(),
                            () -> cache.get(rc)
                    );
                    asyncReferringFutures.add(future);
                } else {
                    /**
                     * 引用服务，也就是调用 {@link ReferenceConfig#get()} 方法
                     * 这一步做了非常多的工作，例如创建 Invoker 对象，动态代理对象，向注册中心订阅等等
                     */
                    cache.get(rc);
                }
            }
        });
    }

    private void unreferServices() {
        // 获取引用服务配置类的缓存对象
        if (cache == null) {
            cache = ReferenceConfigCache.getCache();
        }

        // 如果还有未完成的异步引用服务任务，则取消
        asyncReferringFutures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        // 清理资源
        asyncReferringFutures.clear();
        // 销毁缓存中所有的引用服务，例如销毁动态代理对象
        cache.destroyAll();
    }

    private void registerServiceInstance() {
        if (CollectionUtils.isEmpty(getServiceDiscoveries())) {
            return;
        }

        ApplicationConfig application = getApplication();

        String serviceName = application.getName();

        URL exportedURL = selectMetadataServiceExportedURL();

        String host = exportedURL.getHost();

        int port = exportedURL.getPort();

        ServiceInstance serviceInstance = createServiceInstance(serviceName, host, port);

        doRegisterServiceInstance(serviceInstance);

        // scheduled task for updating Metadata and ServiceInstance
        executorRepository.nextScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                InMemoryWritableMetadataService localMetadataService = (InMemoryWritableMetadataService) WritableMetadataService.getDefaultExtension();
                localMetadataService.blockUntilUpdated();
                ServiceInstanceMetadataUtils.refreshMetadataAndInstance();
            } catch (Throwable e) {
                logger.error("refresh metadata and instance failed", e);
            }
        }, 0, ConfigurationUtils.get(METADATA_PUBLISH_DELAY_KEY, DEFAULT_METADATA_PUBLISH_DELAY), TimeUnit.MILLISECONDS);
    }

    private void doRegisterServiceInstance(ServiceInstance serviceInstance) {
        //FIXME
        if (logger.isInfoEnabled()) {
            logger.info("Start publishing metadata to remote center, this only makes sense for applications enabled remote metadata center.");
        }
        publishMetadataToRemote(serviceInstance);

        logger.info("Start registering instance address to registry.");
        getServiceDiscoveries().forEach(serviceDiscovery ->
        {
            calInstanceRevision(serviceDiscovery, serviceInstance);
            if (logger.isDebugEnabled()) {
                logger.debug("Start registering instance address to registry" + serviceDiscovery.getUrl() + ", instance " + serviceInstance);
            }
            // register metadata
            serviceDiscovery.register(serviceInstance);
        });
    }

    private void publishMetadataToRemote(ServiceInstance serviceInstance) {
//        InMemoryWritableMetadataService localMetadataService = (InMemoryWritableMetadataService)WritableMetadataService.getDefaultExtension();
//        localMetadataService.blockUntilUpdated();
        RemoteMetadataServiceImpl remoteMetadataService = MetadataUtils.getRemoteMetadataService();
        remoteMetadataService.publishMetadata(serviceInstance.getServiceName());
    }

    private URL selectMetadataServiceExportedURL() {

        URL selectedURL = null;

        SortedSet<String> urlValues = metadataService.getExportedURLs();

        for (String urlValue : urlValues) {
            URL url = URL.valueOf(urlValue);
            if ("rest".equals(url.getProtocol())) { // REST first
                selectedURL = url;
                break;
            } else {
                selectedURL = url; // If not found, take any one
            }
        }

        if (selectedURL == null && CollectionUtils.isNotEmpty(urlValues)) {
            selectedURL = URL.valueOf(urlValues.iterator().next());
        }

        return selectedURL;
    }

    private void unregisterServiceInstance() {
        if (serviceInstance != null) {
            getServiceDiscoveries().forEach(serviceDiscovery -> {
                serviceDiscovery.unregister(serviceInstance);
            });
        }
    }

    private ServiceInstance createServiceInstance(String serviceName, String host, int port) {
        this.serviceInstance = new DefaultServiceInstance(serviceName, host, port);
        setMetadataStorageType(serviceInstance, getMetadataType());

        ExtensionLoader<ServiceInstanceCustomizer> loader =
                ExtensionLoader.getExtensionLoader(ServiceInstanceCustomizer.class);
        // FIXME, sort customizer before apply
        loader.getSupportedExtensionInstances().forEach(customizer -> {
            // customizes
            customizer.customize(this.serviceInstance);
        });

        return this.serviceInstance;
    }

    public void destroy() {
        // 加锁
        if (destroyLock.tryLock()) {
            try {
                // 将启动状态由 true 改为 false
                if (started.compareAndSet(true, false)
                        // 将销毁状态由 false 改为 true
                        && destroyed.compareAndSet(false, true)) {

                    unregisterServiceInstance();
                    unexportMetadataService();
                    // 将已经暴露的服务进行下线
                    unexportServices();
                    // 销毁缓存中的引用的服务的代理对象
                    unreferServices();

                    // 销毁所有注册中心，例如关闭 zk 客户端，取消所有订阅
                    destroyRegistries();

                    // 关闭所有 ServiceDiscoveryRegistry 类型的注册中心实例对象
                    destroyServiceDiscoveries();
                    // 销毁所有线程池
                    destroyExecutorRepository();
                    // 清理资源
                    clear();
                    // 关闭当前对象中的单线程线程池
                    shutdown();
                    // 释放锁，唤醒正在等待的 DubboBootstrap 对象，疑惑？？让主线程继续？
                    release();
                    // Dubbo SPI 获取 DubboBootstrapStartStopListener 监听器，触发停止事件
                    ExtensionLoader<DubboBootstrapStartStopListener> exts = getExtensionLoader(DubboBootstrapStartStopListener.class);
                    exts.getSupportedExtensionInstances().forEach(ext -> ext.onStop(this));
                }

                // 销毁所有注册中心（上面已经销毁了就不再进行操作）和 Protocol 协议
                DubboShutdownHook.destroyAll();
            } finally {
                // 释放锁
                destroyLock.unlock();
            }
        }
    }

    private void destroyExecutorRepository() {
        ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension().destroyAll();
    }

    private void destroyRegistries() {
        AbstractRegistryFactory.destroyAll();
    }

    private void destroyServiceDiscoveries() {
        getServiceDiscoveries().forEach(serviceDiscovery -> {
            execute(serviceDiscovery::destroy);
        });
        if (logger.isDebugEnabled()) {
            logger.debug(NAME + "'s all ServiceDiscoveries have been destroyed.");
        }
    }

    private void clear() {
        // 清理 ConfigManager 配置管理器中的所有配置类
        clearConfigs();
        clearApplicationModel();
    }

    private void clearApplicationModel() {

    }

    private void clearConfigs() {
        configManager.destroy();
        if (logger.isDebugEnabled()) {
            logger.debug(NAME + "'s configs have been clear.");
        }
    }

    private void release() {
        executeMutually(() -> {
            while (awaited.compareAndSet(false, true)) {
                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " is about to shutdown...");
                }
                condition.signalAll();
            }
        });
    }

    private void shutdown() {
        if (!executorService.isShutdown()) {
            // Shutdown executorService
            executorService.shutdown();
        }
    }

    private void executeMutually(Runnable runnable) {
        try {
            lock.lock();
            runnable.run();
        } finally {
            lock.unlock();
        }
    }

    public ApplicationConfig getApplication() {
        ApplicationConfig application = configManager
                .getApplication()
                .orElseGet(() -> {
                    ApplicationConfig applicationConfig = new ApplicationConfig();
                    configManager.setApplication(applicationConfig);
                    return applicationConfig;
                });

        application.refresh();
        return application;
    }

    private MonitorConfig getMonitor() {
        MonitorConfig monitor = configManager
                .getMonitor()
                .orElseGet(() -> {
                    MonitorConfig monitorConfig = new MonitorConfig();
                    configManager.setMonitor(monitorConfig);
                    return monitorConfig;
                });

        monitor.refresh();
        return monitor;
    }

    private MetricsConfig getMetrics() {
        MetricsConfig metrics = configManager
                .getMetrics()
                .orElseGet(() -> {
                    MetricsConfig metricsConfig = new MetricsConfig();
                    configManager.setMetrics(metricsConfig);
                    return metricsConfig;
                });
        metrics.refresh();
        return metrics;
    }

    private ModuleConfig getModule() {
        ModuleConfig module = configManager
                .getModule()
                .orElseGet(() -> {
                    ModuleConfig moduleConfig = new ModuleConfig();
                    configManager.setModule(moduleConfig);
                    return moduleConfig;
                });

        module.refresh();
        return module;
    }

    private SslConfig getSsl() {
        SslConfig ssl = configManager
                .getSsl()
                .orElseGet(() -> {
                    SslConfig sslConfig = new SslConfig();
                    configManager.setSsl(sslConfig);
                    return sslConfig;
                });

        ssl.refresh();
        return ssl;
    }

    public void setReady(boolean ready) {
        this.ready.set(ready);
    }


    /**
     * Try reset dubbo status for new instance.
     * @deprecated  For testing purposes only
     */
    @Deprecated
    public static void reset() {
        reset(true);
    }

    /**
     * Try reset dubbo status for new instance.
     * @deprecated For testing purposes only
     */
    @Deprecated
    public static void reset(boolean destroy) {
        if (destroy) {
            if (instance != null) {
                instance.destroy();
                instance = null;
            }
            ApplicationModel.reset();
            AbstractRegistryFactory.reset();
            MetadataReportInstance.destroy();
            AbstractMetadataReportFactory.clear();
            ExtensionLoader.resetExtensionLoader(DynamicConfigurationFactory.class);
            ExtensionLoader.resetExtensionLoader(MetadataReportFactory.class);
            ExtensionLoader.destroyAll();
        } else {
            instance = null;
            ApplicationModel.reset();
        }
    }
}
