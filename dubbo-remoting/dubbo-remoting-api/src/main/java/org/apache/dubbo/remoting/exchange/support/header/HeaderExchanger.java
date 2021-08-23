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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporters;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchanger;
import org.apache.dubbo.remoting.transport.DecodeHandler;
import org.apache.dubbo.remoting.transport.MultiMessageHandler;

/**
 * 基于消息头部( Header )的信息交换者实现类
 * <p>
 * DefaultMessenger
 */
public class HeaderExchanger implements Exchanger {

    public static final String NAME = "header";

    @Override
    public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        /**
         * 1. 将这个 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler} 信息交换处理器进行包装，和下面一致
         *
         * 2. Dubbo SPI 获取对应的 {@link org.apache.dubbo.remoting.Transporter} 实现类，默认为 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter} 对象
         *    创建一个 {@link org.apache.dubbo.remoting.transport.netty4.NettyClient} 客户端
         *    在创建 NettyClient 对象的过程中，会基于 netty4 创建一个 {@link io.netty.bootstrap.ServerBootstrap} 对象，与远程服务提供者建立连接，可向远程发送请求
         *
         * 3. 将这个 {@link org.apache.dubbo.remoting.Client} 封装成 {@link HeaderExchangeClient} 返回
         */
        return new HeaderExchangeClient(Transporters.connect(url, new DecodeHandler(new HeaderExchangeHandler(handler))), true);
    }

    @Override
    public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        /**
         * 1. 将这个 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler} 信息交换处理器进行包装
         *    {@link MultiMessageHandler}，支持多消息的处理
         * => {@link HeartbeatHandler}，支持心跳检测的处理
         * => {@link org.apache.dubbo.remoting.transport.dispatcher.all.AllChannelHandler}，默认 `all` 线程模型，不同的线程模型不同包装类，在线程池中处理请求
         * => {@link DecodeHandler}：对实现了 {@link Decodeable} 接口的请求数据或者响应数据进行解码
         * => {@link HeaderExchangeHandler}
         * => {@link ExchangeHandler}，入参对象
         *
         * 2. Dubbo SPI 获取对应的 {@link org.apache.dubbo.remoting.Transporter} 实现类，默认为 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter} 对象
         *    创建一个 {@link org.apache.dubbo.remoting.transport.netty4.NettyServer} 服务器
         *
         * 3. 将这个 {@link org.apache.dubbo.remoting.RemotingServer} 封装成 {@link HeaderExchangeServer} 返回
         */
        return new HeaderExchangeServer(Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler))));
    }

}
