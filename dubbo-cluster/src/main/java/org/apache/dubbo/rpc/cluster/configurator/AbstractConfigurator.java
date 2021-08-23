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
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACES;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.OVERRIDE_PROVIDERS_KEY;

/**
 * AbstractOverrideConfigurator
 */
public abstract class AbstractConfigurator implements Configurator {

    private static final String TILDE = "~";

    /**
     * 配置规则 URL
     */
    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    @Override
    public URL configure(URL url) {
        // If override url is not enabled or is invalid, just return.
        if (!configuratorUrl.getParameter(ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /*
         * This if branch is created since 2.7.0.
         */
        String apiVersion = configuratorUrl.getParameter(CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            // 当前是消费者还是提供者
            String currentSide = url.getParameter(SIDE_KEY);
            // 配置的是消费者规则，还是提供者的
            String configuratorSide = configuratorUrl.getParameter(SIDE_KEY);
            if (currentSide.equals(configuratorSide) && CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) { // 消费者
                // 消费者的地址
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (currentSide.equals(configuratorSide) && PROVIDER.equals(configuratorSide) && url.getPort() == configuratorUrl.getPort()) { // 提供者
                // 提供者的地址
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /*
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            url = configureDeprecated(url);
        }
        return url;
    }

    @Deprecated
    private URL configureDeprecated(URL url) {
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        // 配置规则，URL 带有端口( port )，意图是控制提供者机器。可以在提供端生效 也可以在消费端生效
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else { // 配置规则，URL 没有端口，override 输入消费端地址 或者 0.0.0.0
            /*
             *  override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0.
             *  1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore.
             *    如果是消费端地址，则意图是控制消费者机器，必定在消费端生效，提供端忽略；
             *  2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider.
             *    如果是0.0.0.0可能是控制提供端，也可能是控制提供端
             */
            if (url.getParameter(SIDE_KEY, PROVIDER).equals(CONSUMER)) {
                // NetUtils.getLocalHost is the ip address consumer registered to registry.
                // NetUtils.getLocalHost 是消费端注册到zk的消费者地址
                return configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (url.getParameter(SIDE_KEY, CONSUMER).equals(PROVIDER)) {
                // take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
                // 控制所有提供端，地址必定是0.0.0.0，否则就要配端口从而执行上面的if分支了
                return configureIfMatch(ANYHOST_VALUE, url);
            }
        }
        return url;
    }

    private URL configureIfMatch(String host, URL url) {
        // 匹配 host
        if (ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            String providers = configuratorUrl.getParameter(OVERRIDE_PROVIDERS_KEY);
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(ANYHOST_VALUE)) {
                // 匹配 application
                String configApplication = configuratorUrl.getParameter(APPLICATION_KEY,
                        configuratorUrl.getUsername());
                String currentApplication = url.getParameter(APPLICATION_KEY, url.getUsername());
                if (configApplication == null || ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {
                    // 配置规则 URL 中的条件 KEYS 集合
                    Set<String> tildeKeys = new HashSet<>();
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        String tildeKey = StringUtils.isNotEmpty(key) && key.startsWith(TILDE) ? key : null;

                        // 判断传入的 url 是否匹配配置规则 URL 的条件。除了 "application" 和 "side" 之外，带有 "~" 开头的 KEY ，也是条件
                        if (tildeKey != null || APPLICATION_KEY.equals(key) || SIDE_KEY.equals(key)) {
                            if (value != null && !ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(tildeKey != null ? key.substring(1) : key))) {
                                return url;
                            }
                        }

                        if (tildeKey != null) {
                            tildeKeys.add(tildeKey);
                        }
                    }

                    Set<String> conditionKeys = new HashSet<>();
                    conditionKeys.add(CATEGORY_KEY);
                    conditionKeys.add(Constants.CHECK_KEY);
                    conditionKeys.add(DYNAMIC_KEY);
                    conditionKeys.add(ENABLED_KEY);
                    conditionKeys.add(GROUP_KEY);
                    conditionKeys.add(VERSION_KEY);
                    conditionKeys.add(APPLICATION_KEY);
                    conditionKeys.add(SIDE_KEY);
                    conditionKeys.add(CONFIG_VERSION_KEY);
                    conditionKeys.add(COMPATIBLE_CONFIG_KEY);
                    conditionKeys.add(INTERFACES);
                    conditionKeys.addAll(tildeKeys);

                    // 移除条件 KEYS 集合，并配置到 URL 中
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
