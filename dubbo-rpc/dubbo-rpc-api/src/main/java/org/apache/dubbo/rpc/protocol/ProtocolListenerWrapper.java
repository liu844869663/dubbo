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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.ExporterListener;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.listener.ListenerExporterWrapper;
import org.apache.dubbo.rpc.listener.ListenerInvokerWrapper;

import java.util.Collections;
import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.EXPORTER_LISTENER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INVOKER_LISTENER_KEY;

/**
 * ListenerProtocol
 */
@Activate(order = 200)
public class ProtocolListenerWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolListenerWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 如果是注册中心的 URL，则直接暴露
        if (UrlUtils.isRegistry(invoker.getUrl())) {
            return protocol.export(invoker);
        }
        // 将这个 Exporter 和 ExporterListener 数组封装成一个 ListenerExporterWrapper 对象
        // 目的就是执行这些 ExporterListener 监听器
        return new ListenerExporterWrapper<T>(
                // 先通过这个 Protocol 扩展点实现类暴露服务，获取一个 Exporter 对象
                protocol.export(invoker),
                // Dubbo SPI 获得符合自动激活条件的 ExporterListener 拓展点实现类数组
                // 可通过 listener=a,b 来指定需要哪些监听器，Dubbo 好像没有默认实现
                Collections.unmodifiableList(
                        ExtensionLoader.getExtensionLoader(ExporterListener.class).getActivateExtension(invoker.getUrl(), EXPORTER_LISTENER_KEY)));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 如果是注册中心的 URL，则直接引用服务
        if (UrlUtils.isRegistry(url)) {
            return protocol.refer(type, url);
        }
        // 将这个 Invoker 和 InvokerListener 数组封装成一个 ListenerInvokerWrapper 对象
        // 目的就是执行这些 InvokerListener 监听器
        return new ListenerInvokerWrapper<T>(
                // 先通过这个 Protocol 扩展点实现类引用服务，获取一个 Invoker 对象
                protocol.refer(type, url),
                // Dubbo SPI 获得符合自动激活条件的 InvokerListener 拓展点实现类数组
                // 可通过 listener=a,b 来指定需要哪些监听器
                // 例如你配置了 deprecated=true，则会有一个 DeprecatedInvokerListener 打印错误日志
                Collections.unmodifiableList(
                        ExtensionLoader.getExtensionLoader(InvokerListener.class) .getActivateExtension(url, INVOKER_LISTENER_KEY)));
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }
}
