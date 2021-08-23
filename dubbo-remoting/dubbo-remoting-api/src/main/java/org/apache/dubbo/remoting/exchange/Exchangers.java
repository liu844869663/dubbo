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
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.*;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerDispatcher;
import org.apache.dubbo.remoting.exchange.support.Replier;
import org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeClient;
import org.apache.dubbo.remoting.exchange.support.header.HeaderExchanger;
import org.apache.dubbo.remoting.transport.ChannelHandlerAdapter;

/**
 * Exchanger facade. (API, Static, ThreadSafe)
 */
public class Exchangers {

    static {
        // check duplicate jar package
        Version.checkDuplicate(Exchangers.class);
    }

    private Exchangers() {
    }

    public static ExchangeServer bind(String url, Replier<?> replier) throws RemotingException {
        return bind(URL.valueOf(url), replier);
    }

    public static ExchangeServer bind(URL url, Replier<?> replier) throws RemotingException {
        return bind(url, new ChannelHandlerAdapter(), replier);
    }

    public static ExchangeServer bind(String url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return bind(URL.valueOf(url), handler, replier);
    }

    public static ExchangeServer bind(URL url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return bind(url, new ExchangeHandlerDispatcher(replier, handler));
    }

    public static ExchangeServer bind(String url, ExchangeHandler handler) throws RemotingException {
        return bind(URL.valueOf(url), handler);
    }

    public static ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        url = url.addParameterIfAbsent(Constants.CODEC_KEY, "exchange");
        /**
         * 1. Dubbo SPI 根据 `<dubbo:protocol exchanger="xxx" />` 获取对应的 {@link Exchanger} 实现类，默认为 {@link HeaderExchanger}
         *
         * 2. Dubbo SPI 获取 {@link Transporter} 实现类，网络传输层，默认为 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter}，基于 netty4 实现的；
         *    通过 {@link Transporter} 扩展点实现类创建一个 {@link RemotingServer} 服务器对象，默认为 {@link org.apache.dubbo.remoting.transport.netty4.NettyServer}
         *    在创建 NettyServer 对象的过程中，会基于 netty4 创建一个 {@link io.netty.bootstrap.ServerBootstrap} 对象，监听 Dubbo 端口，处理远程发送过来的请求
         *
         * 3. 创建一个 {@link ExchangeServer} 服务器对象，封装这个 {@link RemotingServer} 服务器对象，信息交换层
         *
         * 注意，入参中的 {@link ExchangeHandler} 对象（信息交换层的处理器），会经过一些列的包装，传入到 {@link RemotingServer} 中
         */
        return getExchanger(url).bind(url, handler);
    }

    public static ExchangeClient connect(String url) throws RemotingException {
        return connect(URL.valueOf(url));
    }

    public static ExchangeClient connect(URL url) throws RemotingException {
        return connect(url, new ChannelHandlerAdapter(), null);
    }

    public static ExchangeClient connect(String url, Replier<?> replier) throws RemotingException {
        return connect(URL.valueOf(url), new ChannelHandlerAdapter(), replier);
    }

    public static ExchangeClient connect(URL url, Replier<?> replier) throws RemotingException {
        return connect(url, new ChannelHandlerAdapter(), replier);
    }

    public static ExchangeClient connect(String url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return connect(URL.valueOf(url), handler, replier);
    }

    public static ExchangeClient connect(URL url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return connect(url, new ExchangeHandlerDispatcher(replier, handler));
    }

    public static ExchangeClient connect(String url, ExchangeHandler handler) throws RemotingException {
        return connect(URL.valueOf(url), handler);
    }

    public static ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        /**
         * 1. Dubbo SPI 根据 `<dubbo:protocol exchanger="xxx" />` 获取对应的 {@link Exchanger} 实现类，默认为 {@link HeaderExchanger}
         *
         * 2. Dubbo SPI 获取 {@link Transporter} 实现类，网络传输层，默认为 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter}，基于 netty4 实现的；
         *    通过 {@link Transporter} 扩展点实现类创建一个 {@link Client} 客户端对象，默认为 {@link org.apache.dubbo.remoting.transport.netty4.NettyClient}
         *    在创建 NettyClient 对象的过程中，会基于 netty4 创建一个 {@link io.netty.bootstrap.ServerBootstrap} 对象，与远程服务提供者建立连接，可向远程发送请求
         *
         * 3. 创建一个 {@link HeaderExchangeClient} 服务器对象，封装这个 {@link Client} 服务器对象，信息交换层
         *
         * 注意，入参中的 {@link ExchangeHandler} 对象（信息交换层的处理器），会经过一些列的包装，传入到 {@link Client} 中
         */
        return getExchanger(url).connect(url, handler);
    }

    public static Exchanger getExchanger(URL url) {
        String type = url.getParameter(Constants.EXCHANGER_KEY, Constants.DEFAULT_EXCHANGER);
        return getExchanger(type);
    }

    public static Exchanger getExchanger(String type) {
        return ExtensionLoader.getExtensionLoader(Exchanger.class).getExtension(type);
    }

}
