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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Abstract {@link ServiceDiscoveryFactory} implementation with cache, the subclass
 * should implement {@link #createDiscovery(URL)} method to create an instance of {@link ServiceDiscovery}
 *
 * @see ServiceDiscoveryFactory
 * @since 2.7.5
 */
public abstract class AbstractServiceDiscoveryFactory implements ServiceDiscoveryFactory {

    private final ConcurrentMap<String, ServiceDiscovery> discoveries = new ConcurrentHashMap<>();

    @Override
    public ServiceDiscovery getServiceDiscovery(URL registryURL) {
        // 获取注册中心的缓存键，`{protocol}://{username}:{password}@{host}:{port}/{group}/{interfaceName}:{version}`
        // 例如 `zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService`
        String key = registryURL.toServiceStringWithoutResolving();
        return discoveries.computeIfAbsent(key, k -> createDiscovery(registryURL));
    }

    protected abstract ServiceDiscovery createDiscovery(URL registryURL);
}
