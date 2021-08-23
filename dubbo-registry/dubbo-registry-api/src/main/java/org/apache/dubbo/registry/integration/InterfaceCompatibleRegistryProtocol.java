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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory;
import org.apache.dubbo.registry.client.migration.MigrationInvoker;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.interceptor.ConsumerContextClusterInterceptor;
import org.apache.dubbo.rpc.cluster.interceptor.ZoneAwareClusterInterceptor;
import org.apache.dubbo.rpc.cluster.support.FailoverClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker;

import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY;

/**
 * RegistryProtocol
 */
public class InterfaceCompatibleRegistryProtocol extends RegistryProtocol {

    private static final Logger logger = LoggerFactory.getLogger(InterfaceCompatibleRegistryProtocol.class);

    @Override
    protected URL getRegistryUrl(Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();
        if (REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            String protocol = registryUrl.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(REGISTRY_KEY);
        }
        return registryUrl;
    }

    @Override
    protected URL getRegistryUrl(URL url) {
        return URLBuilder.from(url)
                .setProtocol(url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY))
                .removeParameter(REGISTRY_KEY)
                .build();
    }

    @Override
    public <T> ClusterInvoker<T> getInvoker(Cluster cluster, Registry registry, Class<T> type, URL url) {
        DynamicDirectory<T> directory = new RegistryDirectory<>(type, url);
        return doCreateInvoker(directory, cluster, registry, type);
    }

    @Override
    public <T> ClusterInvoker<T> getServiceDiscoveryInvoker(Cluster cluster, Registry registry, Class<T> type, URL url) {
        try {
            /**
             * 获取 Registry 注册中心对象
             * 可参考 {@link RegistryProtocol#getRegistry(URL)} 方法
             */
            registry = registryFactory.getRegistry(super.getRegistryUrl(url));
        } catch (IllegalStateException e) {
            String protocol = url.getProtocol();
            logger.warn(protocol + " do not support service discovery, automatically switch to interface-level service discovery.");
            registry = AbstractRegistryFactory.getDefaultNopRegistryIfNotSupportServiceDiscovery();
        }

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

    @Override
    protected <T> ClusterInvoker<T> getMigrationInvoker(RegistryProtocol registryProtocol, Cluster cluster, Registry registry,
                                                        Class<T> type, URL url, URL consumerUrl) {
//        ClusterInvoker<T> invoker = getInvoker(cluster, registry, type, url);
        return new MigrationInvoker<T>(registryProtocol, cluster, registry, type, url, consumerUrl);
    }

}
