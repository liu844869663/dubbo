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
package org.apache.dubbo.rpc.cluster.directory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Abstract implementation of Directory: Invoker list returned from this Directory's list method have been filtered by Routers
 */
public abstract class AbstractDirectory<T> implements Directory<T> {

    // logger
    private static final Logger logger = LoggerFactory.getLogger(AbstractDirectory.class);

    /**
     * 注册中心 URL 对象
     */
    private final URL url;

    /**
     * 是否被销毁
     */
    private volatile boolean destroyed = false;

    /**
     * 服务消费方信息，例如 `consumer://消费者主机IP/org.apache.dubbo.demo.service.StudyService?application=springboot-demo-consumer&dubbo=2.0.2&init=false
     * &interface=org.apache.dubbo.demo.service.StudyService&metadata-type=remote&methods=researchSpring,researchDubbo&pid=8432
     * &qos.enable=false&qos.port=0&release=2.7.8&revision=2.0.0&side=consumer&sticky=false&timestamp=1627286764932&version=2.0.0
     * &category=providers,configurators,routers`
     */
    protected volatile URL consumerUrl;

    /**
     * 消费者参数
     */
    protected final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    /**
     * 消费者协议，默认为 dubbo
     */
    protected final String consumedProtocol;

    protected RouterChain<T> routerChain;

    public AbstractDirectory(URL url) {
        this(url, null, false);
    }

    public AbstractDirectory(URL url, boolean isUrlFromRegistry) {
        this(url, null, isUrlFromRegistry);
    }

    public AbstractDirectory(URL url, RouterChain<T> routerChain, boolean isUrlFromRegistry) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }

        // 获取这个 URL（注册中心）中的 `refer` 参数，也就是消费者 URL 信息
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        // 消费者协议，默认都是 `dubbo`
        this.consumedProtocol = this.queryMap.get(PROTOCOL_KEY) == null ? DUBBO : this.queryMap.get(PROTOCOL_KEY);
        // 移除 `refer` 和 `monitor` 参数，也就剩下注册中心 URL 信息了
        this.url = url.removeParameter(REFER_KEY).removeParameter(MONITOR_KEY);

        String path = queryMap.get(PATH_KEY);
        // 设置消费者协议，和路径（接口名称）
        URL consumerUrlFrom = this.url.setProtocol(consumedProtocol)
                .setPath(path == null ? queryMap.get(INTERFACE_KEY) : path);
        if (isUrlFromRegistry) {
            // reserve parameters if url is already a consumer url
            consumerUrlFrom = consumerUrlFrom.clearParameters();
        }
        // 创建一个消费者 URL 对象
        this.consumerUrl = consumerUrlFrom.addParameters(queryMap).removeParameter(MONITOR_KEY);

        // 设置路由器
        setRouterChain(routerChain);
    }

    public URL getSubscribeConsumerurl() {
        return this.consumerUrl;
    }

    @Override
    public List<Invoker<T>> list(Invocation invocation) throws RpcException {
        if (destroyed) {
            throw new RpcException("Directory already destroyed .url: " + getUrl());
        }

        return doList(invocation);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    public RouterChain<T> getRouterChain() {
        return routerChain;
    }

    public void setRouterChain(RouterChain<T> routerChain) {
        this.routerChain = routerChain;
    }

    protected void addRouters(List<Router> routers) {
        routers = routers == null ? Collections.emptyList() : routers;
        routerChain.addRouters(routers);
    }

    public URL getConsumerUrl() {
        return consumerUrl;
    }

    public void setConsumerUrl(URL consumerUrl) {
        this.consumerUrl = consumerUrl;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }

    @Override
    public void discordAddresses() {
        // do nothing by default
    }

    protected abstract List<Invoker<T>> doList(Invocation invocation) throws RpcException;

}
