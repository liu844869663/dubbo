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
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeHandler;
import org.apache.dubbo.remoting.transport.netty4.SslHandlerInitializer.HandshakeCompletionEvent;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty 服务器的处理器
 * <p>
 * {@link Sharable} 注解主要是用来标示一个 ChannelHandler 可以被安全地共享
 * 即可以在多个 Channel 的 ChannelPipeline 中使用同一个ChannelHandler，而不必每一个 ChannelPipeline 都重新 new 一个新的 ChannelHandler
 * <p>
 * NettyServerHandler.
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyServerHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyServerHandler.class);
    /**
     * 缓存工作的 dubbo channel
     * <p>
     * the cache for alive worker channel.
     * <ip:port, dubbo channel>
     */
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();

    /**
     * Dubbo 服务的 URL 对象
     */
    private final URL url;

    /**
     * 通道处理对象
     */
    private final ChannelHandler handler;

    public NettyServerHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    // 接收到连接
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        if (channel != null) {
            // 新连接，则将这个 NettyChannel 也缓存至这里
            channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), channel);
        }
        // 连接处理
        handler.connected(channel);

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is established.");
        }
    }

    // 连接断开
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            // 从这个缓存中移除
            channels.remove(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()));
            // 连接断开处理
            handler.disconnected(channel);
        } finally {
            // 也从 NettyChannel 缓存中移除
            NettyChannel.removeChannel(ctx.channel());
        }

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is disconnected.");
        }
    }

    // 接收到消息
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        /**
         * 处理接收到的消息，进入 {@link HeaderExchangeHandler#received} 方法
         * 会执行目标 Dubbo 服务的方法，然后将执行结果通过这个对应的 NettyChannel 发送给客户端
         */
        handler.received(channel, msg);
    }


    // 写入数据
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 往 `ctx` 中写入数据
        super.write(ctx, msg, promise);
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // 发送数据
        handler.sent(channel, msg);
    }

    // 事件触发
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // server will close channel when server don't receive any heartbeat from client util timeout.
        if (evt instanceof IdleStateEvent) { // 长时间空闲
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
            try {
                logger.info("IdleStateEvent triggered, close channel " + channel);
                // 关闭 netty channel
                channel.close();
            } finally {
                // 如果 netty 断开连接，则从 NettyChannel 移除缓存，将 dubbo channel 标记为无效
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        }
        // 继续交由父类处理该事件
        super.userEventTriggered(ctx, evt);
    }

    // 异常处理
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            // 处理异常
            handler.caught(channel, cause);
        } finally {
            // 如果 netty 断开连接，则从 NettyChannel 移除缓存，将 dubbo channel 标记为无效
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    public void handshakeCompleted(HandshakeCompletionEvent evt) {
        // TODO
    }
}
