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
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * NettyClientHandler
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyClientHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyClientHandler.class);

    /**
     * Dubbo 服务(消费者)的 URL 对象
     */
    private final URL url;

    /**
     * 通道处理对象
     */
    private final ChannelHandler handler;

    public NettyClientHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    // 接收到连接
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // 连接处理
        handler.connected(channel);
        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getLocalAddress() + " -> " + channel.getRemoteAddress() + " is established.");
        }
    }

    // 连接断开
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            // 连接断开处理
            handler.disconnected(channel);
        } finally {
            // 从 NettyChannel 缓存中移除
            NettyChannel.removeChannel(ctx.channel());
        }

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getLocalAddress() + " -> " + channel.getRemoteAddress() + " is disconnected.");
        }
    }

    // 接收到消息
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // 处理接收到的消息
        handler.received(channel, msg);
    }

    // 写入数据
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 往 `ctx` 中写入数据
        super.write(ctx, msg, promise);
        // 从缓存中获取这个 netty channel 对应的 dubbo channel（NettyChannel）对象
        // 也就是将 netty channel、url、handler 封装成 NettyChannel 对象
        final NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // 是否是请求消息
        final boolean isRequest = msg instanceof Request;

        // We add listeners to make sure our out bound event is correct.
        // If our out bound event has an error (in most cases the encoder fails),
        // we need to have the request return directly instead of blocking the invoke process.
        promise.addListener(future -> {
            if (future.isSuccess()) {
                // if our future is success, mark the future to sent.
                // 向通道发送消息
                handler.sent(channel, msg);
                return;
            }

            Throwable t = future.cause();
            if (t != null && isRequest) {
                Request request = (Request) msg;
                // 构建一个错误响应
                Response response = buildErrorResponse(request, t);
                // 处理这个错误响应
                handler.received(channel, response);
            }
        });
    }

    // 事件触发
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // send heartbeat when read idle.
        if (evt instanceof IdleStateEvent) { // 长时间空闲
            try {
                NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
                if (logger.isDebugEnabled()) {
                    logger.debug("IdleStateEvent triggered, send heartbeat to channel " + channel);
                }
                // 发送心跳时间
                Request req = new Request();
                req.setVersion(Version.getProtocolVersion());
                req.setTwoWay(true);
                req.setEvent(HEARTBEAT_EVENT);
                channel.send(req);
            } finally {
                // 如果 netty 断开连接，则从 NettyChannel 移除缓存，将 dubbo channel 标记为无效
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        } else {
            // 否则，交由父类处理该事件
            super.userEventTriggered(ctx, evt);
        }
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

    public void handshakeCompleted(SslHandlerInitializer.HandshakeCompletionEvent evt) {
        // TODO
    }

    /**
     * build a bad request's response
     *
     * @param request the request
     * @param t       the throwable. In most cases, serialization fails.
     * @return the response
     */
    private static Response buildErrorResponse(Request request, Throwable t) {
        Response response = new Response(request.getId(), request.getVersion());
        response.setStatus(Response.BAD_REQUEST);
        response.setErrorMessage(StringUtils.toString(t));
        return response;
    }
}
