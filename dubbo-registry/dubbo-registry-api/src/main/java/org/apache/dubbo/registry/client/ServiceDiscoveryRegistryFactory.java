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
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;

import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY;

public class ServiceDiscoveryRegistryFactory extends AbstractRegistryFactory {

    @Override
    protected Registry createRegistry(URL url) {
        if (SERVICE_REGISTRY_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            // 获取 `registry` 参数值
            String protocol = url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY);
            // 重新设置协议为 `registry` 参数值，例如设置为 `zookeeper`
            // 同时移除 `registry` 参数
            url = url.setProtocol(protocol).removeParameter(REGISTRY_KEY);
        }
        // 返回一个 ServiceDiscoveryRegistry 对象
        return new ServiceDiscoveryRegistry(url);
    }

}
