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
package org.apache.dubbo.registry.client;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.metadata.*;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.registry.client.metadata.SubscribedURLsSynthesizer;
import org.apache.dubbo.registry.client.metadata.store.InMemoryWritableMetadataService;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.registry.support.FailbackRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.of;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_CHAR_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDED_BY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_CLUSTER_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_TYPE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_TYPE;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.function.ThrowableAction.execute;
import static org.apache.dubbo.common.utils.CollectionUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isBlank;
import static org.apache.dubbo.registry.client.ServiceDiscoveryFactory.getExtension;
import static org.apache.dubbo.rpc.Constants.ID_KEY;

/**
 * Being different to the traditional registry, {@link ServiceDiscoveryRegistry} that is a new service-oriented
 * {@link Registry} based on {@link ServiceDiscovery}, it will not interact in the external registry directly,
 * but store the {@link URL urls} that Dubbo services exported and referenced into {@link WritableMetadataService}
 * when {@link #register(URL)} and {@link #subscribe(URL, NotifyListener)} methods are executed. After that the exported
 * {@link URL urls} can be get from {@link WritableMetadataService#getExportedURLs()} and its variant methods. In contrast,
 * {@link WritableMetadataService#getSubscribedURLs()} method offers the subscribed {@link URL URLs}.
 * <p>
 * Every {@link ServiceDiscoveryRegistry} object has its own {@link ServiceDiscovery} instance that was initialized
 * under {@link #ServiceDiscoveryRegistry(URL) the construction}. As the primary argument of constructor , the
 * {@link URL} of connection the registry decides what the kind of ServiceDiscovery is. Generally, each
 * protocol associates with a kind of {@link ServiceDiscovery}'s implementation if present, or the
 * {@link FileSystemServiceDiscovery} will be the default one. Obviously, it's also allowed to extend
 * {@link ServiceDiscovery} using {@link SPI the Dubbo SPI}.
 * In contrast, current {@link ServiceInstance service instance} will not be registered to the registry whether any
 * Dubbo service is exported or not.
 * <p>
 *
 * @see ServiceDiscovery
 * @see FailbackRegistry
 * @see WritableMetadataService
 * @since 2.7.5
 */
public class ServiceDiscoveryRegistry implements Registry {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 服务发现对象，这里都是 {@link EventPublishingServiceDiscovery} 对象，是对不同协议的 ServiceDiscovery 进行增强，用于当发生某个动作时发布事件
     *
     * 例如 zookeeper 对应 {@link org.apache.dubbo.registry.zookeeper.ZookeeperServiceDiscovery}
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * 需要订阅的服务名称
     */
    private final Set<String> subscribedServices;

    /**
     * 服务名称映射对象，默认为 {@link DynamicConfigurationServiceNameMapping}
     */
    private final ServiceNameMapping serviceNameMapping;

    /**
     * 元数据写入对象，默认为 {@link InMemoryWritableMetadataService}
     */
    private final WritableMetadataService writableMetadataService;

    private final Set<String> registeredListeners = new LinkedHashSet<>();

    /* apps - listener */
    private final Map<String, ServiceInstancesChangedListener> serviceListeners = new HashMap<>();
    private final Map<String, String> serviceToAppsMapping = new HashMap<>();

    /**
     * 注册中心的 URL 对象
     * 例如：`zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=8432&release=2.7.8&timestamp=1627287397524
     * &interface=org.apache.dubbo.registry.RegistryService&refer=服务应用信息`
     */
    private URL registryURL;

    /**
     * A cache for all URLs of services that the subscribed services exported
     * The key is the service name
     * The value is a nested {@link Map} whose key is the revision and value is all URLs of services
     */
    private final Map<String, Map<String, List<URL>>> serviceRevisionExportedURLsCache = new LinkedHashMap<>();

    public ServiceDiscoveryRegistry(URL registryURL) {
        // 设置注册中心的 URL 对象
        this.registryURL = registryURL;
        /**
         * 根据注册中心协议通过 Dubbo SPI 创建一个 ServiceDiscovery 服务发现对象，同时会进行初始化工作
         * 这得获取到的是 {@link EventPublishingServiceDiscovery} 对象，是对上面创建的 ServiceDiscovery 进行增强，用于当发生某个动作时发布事件
         *
         * 例如 {@link org.apache.dubbo.registry.zookeeper.ZookeeperServiceDiscovery} 初始化的时候会做以下事情：
         *  1. 会使用 Curator 创建一个 Zookeeper 客户端
         *  2. 构建一个 Curator 的 ServiceDiscovery 服务发现对象，设置了 Zookeeper 客户端和根路径
         *  3. 启动这个服务发现对象
         */
        this.serviceDiscovery = createServiceDiscovery(registryURL);
        // 设置需要订阅的服务名称
        this.subscribedServices = parseServices(registryURL.getParameter(SUBSCRIBED_SERVICE_NAMES_KEY));
        // 获取 ServiceNameMapping 服务名称映射对象，默认为 DynamicConfigurationServiceNameMapping
        this.serviceNameMapping = ServiceNameMapping.getExtension(registryURL.getParameter(MAPPING_KEY));
        // 获取 WritableMetadataService 元数据写入对象，默认为 InMemoryWritableMetadataService
        this.writableMetadataService = WritableMetadataService.getDefaultExtension();
    }

    public ServiceDiscovery getServiceDiscovery() {
        return serviceDiscovery;
    }

    /**
     * Create the {@link ServiceDiscovery} from the registry {@link URL}
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return non-null
     */
    protected ServiceDiscovery createServiceDiscovery(URL registryURL) {
        /**
         * 先通过 Dubbo SPI 获取协议对应的 {@link ServiceDiscoveryFactory} 服务发现工厂对象
         *
         * 然后创建一个 {@link ServiceDiscovery} 服务发现对象，例如 {@link org.apache.dubbo.registry.zookeeper.ZookeeperServiceDiscovery}
         */
        ServiceDiscovery originalServiceDiscovery = getServiceDiscovery(registryURL);
        /**
         * 对 ServiceDiscovery 服务发现对象进行增强
         * 封装成 {@link EventPublishingServiceDiscovery} 对象，用于当发生某个动作时发布事件
         */
        ServiceDiscovery serviceDiscovery = enhanceEventPublishing(originalServiceDiscovery);
        /**
         * 初始化 ServiceDiscovery，会做以下事情：
         *  1. 会使用 Curator 创建一个 Zookeeper 客户端
         *  2. 构建一个 Curator 的 ServiceDiscovery 服务发现对象，设置了 Zookeeper 客户端和根路径
         *  3. 启动这个服务发现对象
         *
         * 在 {@link EventPublishingServiceDiscovery} 中，初始化的前后会发布相应的 {@link org.apache.dubbo.event.Event} 事件
         */
        execute(() -> {
            serviceDiscovery.initialize(registryURL.addParameter(INTERFACE_KEY, ServiceDiscovery.class.getName())
                    .removeParameter(REGISTRY_TYPE_KEY));
        });
        return serviceDiscovery;
    }

    private List<SubscribedURLsSynthesizer> initSubscribedURLsSynthesizers() {
        ExtensionLoader<SubscribedURLsSynthesizer> loader = ExtensionLoader.getExtensionLoader(SubscribedURLsSynthesizer.class);
        return Collections.unmodifiableList(new ArrayList<>(loader.getSupportedExtensionInstances()));
    }

    /**
     * Get the instance {@link ServiceDiscovery} from the registry {@link URL} using
     * {@link ServiceDiscoveryFactory} SPI
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return
     */
    private ServiceDiscovery getServiceDiscovery(URL registryURL) {
        // Dubbo SPI 获取该协议对应的服务发现工厂对象，例如 zookeeper 对应 ZookeeperServiceDiscoveryFactory
        ServiceDiscoveryFactory factory = getExtension(registryURL);
        // 创建一个服务发现对象，例如 ZookeeperServiceDiscovery
        return factory.getServiceDiscovery(registryURL);
    }

    /**
     * Enhance the original {@link ServiceDiscovery} with event publishing feature
     *
     * @param original the original {@link ServiceDiscovery}
     * @return {@link EventPublishingServiceDiscovery} instance
     */
    private ServiceDiscovery enhanceEventPublishing(ServiceDiscovery original) {
        return new EventPublishingServiceDiscovery(original);
    }

    protected boolean shouldRegister(URL providerURL) {

        String side = providerURL.getParameter(SIDE_KEY);

        boolean should = PROVIDER_SIDE.equals(side); // Only register the Provider.

        if (!should) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("The URL[%s] should not be registered.", providerURL.toString()));
            }
        }

        return should;
    }

    protected boolean shouldSubscribe(URL subscribedURL) {
        return !shouldRegister(subscribedURL);
    }

    @Override
    public final void register(URL url) {
        if (!shouldRegister(url)) { // Should Not Register
            return;
        }
        doRegister(url);
    }

    public void doRegister(URL url) {
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        if (writableMetadataService.exportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] registered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been registered.", url.toString()));
            }
        }
    }

    @Override
    public final void unregister(URL url) {
        if (!shouldRegister(url)) {
            return;
        }
        doUnregister(url);
    }

    public void doUnregister(URL url) {
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        if (writableMetadataService.unexportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] deregistered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been deregistered.", url.toString()));
            }
        }
    }

    @Override
    public final void subscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe
            return;
        }
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        doSubscribe(url, listener);
    }

    public void doSubscribe(URL url, NotifyListener listener) {
        writableMetadataService.subscribeURL(url);

        Set<String> serviceNames = getServices(url, listener);

        if (CollectionUtils.isEmpty(serviceNames)) {
            logger.warn("Should has at least one way to know which services this interface belongs to, subscription url: " + url);
            return;
        }

        subscribeURLs(url, listener, serviceNames);
    }

    @Override
    public final void unsubscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe
            return;
        }
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        doUnsubscribe(url, listener);
    }

    public void doUnsubscribe(URL url, NotifyListener listener) {
        writableMetadataService.unsubscribeURL(url);
        String protocolServiceKey = url.getServiceKey() + GROUP_CHAR_SEPARATOR + url.getParameter(PROTOCOL_KEY, DUBBO);
        String serviceNamesKey = serviceToAppsMapping.remove(protocolServiceKey);
        if (serviceNamesKey == null) {
            return;
        }
        ServiceInstancesChangedListener instancesChangedListener = serviceListeners.get(serviceNamesKey);
        instancesChangedListener.removeListener(protocolServiceKey);
    }

    @Override
    public List<URL> lookup(URL url) {
        throw new UnsupportedOperationException("");
    }

    @Override
    public URL getUrl() {
        return registryURL;
    }

    @Override
    public boolean isAvailable() {
        return !serviceDiscovery.getServices().isEmpty();
    }

    @Override
    public void destroy() {
        AbstractRegistryFactory.removeDestroyedRegistry(this);
        execute(() -> {
            // stop ServiceDiscovery
            serviceDiscovery.destroy();
        });
    }

    protected void subscribeURLs(URL url, NotifyListener listener, Set<String> serviceNames) {
        String serviceNamesKey = serviceNames.toString();
        String protocolServiceKey = url.getServiceKey() + GROUP_CHAR_SEPARATOR + url.getParameter(PROTOCOL_KEY, DUBBO);
        serviceToAppsMapping.put(protocolServiceKey, serviceNamesKey);

        // register ServiceInstancesChangedListener
        ServiceInstancesChangedListener serviceListener = serviceListeners.computeIfAbsent(serviceNamesKey,
                k -> new ServiceInstancesChangedListener(serviceNames, serviceDiscovery));
        serviceListener.setUrl(url);
        listener.addServiceListener(serviceListener);

        serviceListener.addListener(protocolServiceKey, listener);
        registerServiceInstancesChangedListener(url, serviceListener);

        // FIXME: This will cause redundant duplicate notifications
        serviceNames.forEach(serviceName -> {
            List<ServiceInstance> serviceInstances = serviceDiscovery.getInstances(serviceName);
            if (CollectionUtils.isNotEmpty(serviceInstances)) {
                serviceListener.onEvent(new ServiceInstancesChangedEvent(serviceName, serviceInstances));
            } else {
                logger.info("getInstances by serviceName=" + serviceName + " is empty, waiting for serviceListener callback. url=" + url);
            }
        });

        listener.notify(serviceListener.getUrls(protocolServiceKey));

    }

    /**
     * Register the {@link ServiceInstancesChangedListener} If absent
     *
     * @param url      {@link URL}
     * @param listener the {@link ServiceInstancesChangedListener}
     */
    private void registerServiceInstancesChangedListener(URL url, ServiceInstancesChangedListener listener) {
        String listenerId = createListenerId(url, listener);
        if (registeredListeners.add(listenerId)) {
            serviceDiscovery.addServiceInstancesChangedListener(listener);
        }
    }

    private String createListenerId(URL url, ServiceInstancesChangedListener listener) {
        return listener.getServiceNames() + ":" + url.toString(VERSION_KEY, GROUP_KEY, PROTOCOL_KEY);
    }

    /**
     * 1.developer explicitly specifies the application name this interface belongs to
     * 2.check Interface-App mapping
     * 3.use the services specified in registry url.
     *
     * @param subscribedURL
     * @return
     */
    protected Set<String> getServices(URL subscribedURL, final NotifyListener listener) {
        Set<String> subscribedServices = new TreeSet<>();

        String serviceNames = subscribedURL.getParameter(PROVIDED_BY);
        if (StringUtils.isNotEmpty(serviceNames)) {
            logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " instructed by provided-by set by user.");
            subscribedServices.addAll(parseServices(serviceNames));
        }

        if (isEmpty(subscribedServices)) {
            Set<String> mappedServices = findMappedServices(subscribedURL, new DefaultMappingListener(subscribedURL, subscribedServices, listener));
            logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " instructed by remote metadata center.");
            subscribedServices.addAll(mappedServices);
            if (isEmpty(subscribedServices)) {
                logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " by default.");
                subscribedServices.addAll(getSubscribedServices());
            }
        }
        return subscribedServices;
    }

    public static Set<String> parseServices(String literalServices) {
        return isBlank(literalServices) ? emptySet() :
                unmodifiableSet(of(literalServices.split(","))
                        .map(String::trim)
                        .filter(StringUtils::isNotEmpty)
                        .collect(toSet()));
    }

    /**
     * Get the subscribed service names
     *
     * @return non-null
     */
    public Set<String> getSubscribedServices() {
        return subscribedServices;
    }

    /**
     * Get the mapped services name by the specified {@link URL}
     *
     * @param subscribedURL
     * @return
     */
    protected Set<String> findMappedServices(URL subscribedURL, MappingListener listener) {
        return serviceNameMapping.getAndListen(subscribedURL, listener);
    }

    /**
     * Create an instance of {@link ServiceDiscoveryRegistry} if supported
     *
     * @param registryURL the {@link URL url} of registry
     * @return <code>null</code> if not supported
     */
    public static ServiceDiscoveryRegistry create(URL registryURL) {
        return supports(registryURL) ? new ServiceDiscoveryRegistry(registryURL) : null;
    }

    /**
     * Supports or not ?
     *
     * @param registryURL the {@link URL url} of registry
     * @return if supported, return <code>true</code>, or <code>false</code>
     */
    public static boolean supports(URL registryURL) {
        return SERVICE_REGISTRY_TYPE.equalsIgnoreCase(registryURL.getParameter(REGISTRY_TYPE_KEY));
    }

    private static List<URL> filterSubscribedURLs(URL subscribedURL, List<URL> exportedURLs) {
        return exportedURLs.stream()
                .filter(url -> isSameServiceInterface(subscribedURL, url))
                .filter(url -> isSameParameter(subscribedURL, url, VERSION_KEY))
                .filter(url -> isSameParameter(subscribedURL, url, GROUP_KEY))
                .filter(url -> isCompatibleProtocol(subscribedURL, url))
                .collect(Collectors.toList());
    }

    private static boolean isSameServiceInterface(URL one, URL another) {
        return Objects.equals(one.getServiceInterface(), another.getServiceInterface());
    }

    private static boolean isSameParameter(URL one, URL another, String key) {
        return Objects.equals(one.getParameter(key), another.getParameter(key));
    }

    private static boolean isCompatibleProtocol(URL one, URL another) {
        String protocol = one.getParameter(PROTOCOL_KEY);
        return isCompatibleProtocol(protocol, another);
    }

    private static boolean isCompatibleProtocol(String protocol, URL targetURL) {
        return protocol == null || Objects.equals(protocol, targetURL.getParameter(PROTOCOL_KEY))
                || Objects.equals(protocol, targetURL.getProtocol());
    }

    private class DefaultMappingListener implements MappingListener {
        private URL url;
        private Set<String> oldApps;
        private NotifyListener listener;

        public DefaultMappingListener(URL subscribedURL, Set<String> serviceNames, NotifyListener listener) {
            this.url = subscribedURL;
            this.oldApps = serviceNames;
            this.listener = listener;
        }

        @Override
        public void onEvent(MappingChangedEvent event) {
            Set<String> newApps = event.getApps();
            Set<String> tempOldApps = oldApps;
            oldApps = newApps;

            if (CollectionUtils.isEmpty(newApps)) {
                return;
            }

            if (CollectionUtils.isEmpty(tempOldApps) && newApps.size() > 0) {
                subscribeURLs(url, listener, newApps);
                return;
            }

            for (String newAppName : newApps) {
                if (!tempOldApps.contains(newAppName)) {
                    subscribeURLs(url, listener, newApps);
                    return;
                }
            }
        }
    }
}
