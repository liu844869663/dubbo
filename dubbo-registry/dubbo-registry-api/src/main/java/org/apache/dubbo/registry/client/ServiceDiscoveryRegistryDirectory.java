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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.registry.AddressListener;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.registry.integration.DynamicDirectory;
import org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeClient;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.protocol.AsyncToSyncInvoker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.DISABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;

public class ServiceDiscoveryRegistryDirectory<T> extends DynamicDirectory<T> {
    private static final Logger logger = LoggerFactory.getLogger(ServiceDiscoveryRegistryDirectory.class);

    // instance address to invoker mapping.
    /**
     * 保存服务提供者在消费方的 Invoker 对象们
     * key：服务方IP:端口
     * value：对应的 Invoker 对象
     */
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    private ServiceInstancesChangedListener listener;

    public ServiceDiscoveryRegistryDirectory(Class<T> serviceType, URL url) {
        super(serviceType, url);
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 回调监听器的回调处理
     * 消费者向注册中心订阅了服务提供者的 `provieders/` 的子节点后，当其子节点发送变化，这里会实时接收到，进行回调处理
     * <p>
     * 参考 {@link org.apache.dubbo.registry.zookeeper.ZookeeperRegistry#doSubscribe} 方法
     *
     * @param instanceUrls 新的子节点 URL 对象们，也就是最新的服务提供者
     */
    @Override
    public synchronized void notify(List<URL> instanceUrls) {
        // Set the context of the address notification thread.
        RpcContext.setRpcContext(getConsumerUrl());

        /**
         * 3.x added for extend URL address
         */
        ExtensionLoader<AddressListener> addressListenerExtensionLoader = ExtensionLoader.getExtensionLoader(AddressListener.class);
        List<AddressListener> supportedListeners = addressListenerExtensionLoader.getActivateExtension(getUrl(), (String[]) null);
        if (supportedListeners != null && !supportedListeners.isEmpty()) {
            for (AddressListener addressListener : supportedListeners) {
                instanceUrls = addressListener.notify(instanceUrls, getConsumerUrl(), this);
            }
        }

        // 回调处理，根据最新的提供者的 URL 们生成相应的 Invoker 对象
        // 那么消费者就可以通过当前 ServiceDiscoveryRegistryDirectory 执行引用服务的方法了
        refreshInvoker(instanceUrls);
    }

    private void refreshInvoker(List<URL> invokerUrls) {
        Assert.notNull(invokerUrls, "invokerUrls should not be null, use empty url list to clear address.");

        // 如果刷新后的 URL 为空，例如服务提供者都下线了
        if (invokerUrls.size() == 0) {
            // 禁止访问
            this.forbidden = true; // Forbid to access
            // 服务提供者的 Invoker 对象们置空
            this.invokers = Collections.emptyList();
            // 同时设置到路由器中去
            routerChain.setInvokers(this.invokers);
            // 销毁所有本地的 Invoker 对象，并清理
            destroyAllInvokers(); // Close all invokers
            return;
        }

        // 有的话则设置为允许访问
        this.forbidden = false; // Allow to access
        // 获取原有服务提供者在消费方的 Invoker 对象们
        Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
        if (CollectionUtils.isEmpty(invokerUrls)) {
            return;
        }

        /**
         * 根据这些服务提供者的 URL 们，一一生成新的 Invoker 对象
         * 当然如果不需要变动，则去本地已有的 Invoker 放入新的缓存集合中
         *
         * 新的 Invoker 对象是一个 RPC Invoker 对象 {@link AsyncToSyncInvoker}，支持异步处理
         * 里面封装一个 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker} 对象
         * 创建的过程会先获取与服务提供者的通信客户端集合，默认初始化一个通信客户端，与服务提供者建立连接（使用 netty 通信）
         * 通信客户端参考 {@link HeaderExchangeClient}，封装了 {@link org.apache.dubbo.remoting.transport.netty4.NettyClient} 对象
         */
        Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map

        if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
            logger.error(new IllegalStateException("Cannot create invokers from url address list (total " + invokerUrls.size() + ")"));
            return;
        }

        List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
        // pre-route and build cache, notice that route cache should build on original Invoker list.
        // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
        // 将新的 PRC Invoker 对象设置到路由器链路中
        routerChain.setInvokers(newInvokers);
        // 将新的 PRC Invoker 对象设置到当前对象中
        this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers;
        this.urlInvokerMap = newUrlInvokerMap;

        if (oldUrlInvokerMap != null) {
            try {
                // 销毁之前的 PRC Invoker 对象，关闭所有的通信客户端
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }

        // notify invokers refreshed
        // 回调 PRC Invoker 被刷新事件
        this.invokersChanged();
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     *
     * @param urls
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<>();
        if (CollectionUtils.isEmpty(urls)) {
            return newUrlInvokerMap;
        }
        // 遍历新的服务提供者的 URL 们
        for (URL url : urls) {
            InstanceAddressURL instanceAddressURL = (InstanceAddressURL) url;
            // 如果是 `empty` 协议，则直接跳过
            if (EMPTY_PROTOCOL.equals(instanceAddressURL.getProtocol())) {
                continue;
            }
            // 如果该协议没有相应的 Protocol 扩展点实现类，则跳过
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(instanceAddressURL.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + instanceAddressURL.getProtocol() +
                        " in notified url: " + instanceAddressURL + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }

            // FIXME, some keys may need to be removed.
            instanceAddressURL.addConsumerParams(getConsumerUrl().getProtocolServiceKey(), queryMap);

            // 获取这个地址（host:port）原有的 Invoker 对象
            Invoker<T> invoker = urlInvokerMap == null ? null : urlInvokerMap.get(instanceAddressURL.getAddress());
            // 如果不存在，或者需要更新（服务提供者 URL 信息有变动），那么重新获取一个 Invoker 对象
            if (invoker == null || urlChanged(invoker, instanceAddressURL)) { // Not in the cache, refer again
                try {
                    // 判断新的服务提供者是否禁用，是否启用
                    boolean enabled = true;
                    if (instanceAddressURL.hasParameter(DISABLED_KEY)) {
                        enabled = !instanceAddressURL.getParameter(DISABLED_KEY, false);
                    } else {
                        enabled = instanceAddressURL.getParameter(ENABLED_KEY, true);
                    }
                    // 如果没有被禁用，启用了
                    if (enabled) {
                        /**
                         * 创建一个 RPC Invoker 对象，该过程会创建一个 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker} 对象
                         * 被封装成了 {@link AsyncToSyncInvoker} 对象，支持异步处理
                         *
                         * 创建过程会先获取与服务提供者的通信客户端集合，默认初始化一个通信客户端，与服务提供者建立连接（使用 netty 通信）
                         * 通信客户端参考 {@link HeaderExchangeClient}，封装了 {@link org.apache.dubbo.remoting.transport.netty4.NettyClient} 对象
                         */
                        invoker = protocol.refer(serviceType, instanceAddressURL);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + instanceAddressURL + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    // 将这个新的 Invoker 对象放入新的缓存集合中
                    newUrlInvokerMap.put(instanceAddressURL.getAddress(), invoker);
                }
                // 否则，不需要更新，也就不用重新生成 Invoker 对象，使用原来就可以
            } else {
                // 将这个原先的 Invoker 对象放入新的缓存集合中
                newUrlInvokerMap.put(instanceAddressURL.getAddress(), invoker);
            }
        }
        // 返回新的缓存集合
        return newUrlInvokerMap;
    }

    private boolean urlChanged(Invoker<T> invoker, InstanceAddressURL newURL) {
        InstanceAddressURL oldURL = (InstanceAddressURL) invoker.getUrl();

        if (!newURL.getInstance().equals(oldURL.getInstance())) {
            return true;
        }

        return !oldURL.getMetadataInfo().getServiceInfo(getConsumerUrl().getProtocolServiceKey())
                .equals(newURL.getMetadataInfo().getServiceInfo(getConsumerUrl().getProtocolServiceKey()));
    }

    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        return invokers;
    }

    /**
     * Close all invokers
     */
    @Override
    protected void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        invokers = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<>();
                    }
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            for (String addressKey : deleted) {
                if (addressKey != null) {
                    Invoker<T> invoker = oldUrlInvokerMap.remove(addressKey);
                    if (invoker != null) {
                        try {
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }
}
