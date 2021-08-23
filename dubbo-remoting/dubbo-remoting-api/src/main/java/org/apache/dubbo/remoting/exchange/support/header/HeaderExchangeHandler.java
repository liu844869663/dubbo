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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.apache.dubbo.common.constants.CommonConstants.READONLY_EVENT;


/**
 * 基于消息头部( Header )的信息交换处理器实现类
 *
 * ExchangeReceiver
 */
public class HeaderExchangeHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(HeaderExchangeHandler.class);

    /**
     * 代理的信息交换处理器
     */
    private final ExchangeHandler handler;

    public HeaderExchangeHandler(ExchangeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.handler = handler;
    }

    static void handleResponse(Channel channel, Response response) throws RemotingException {
        if (response != null && !response.isHeartbeat()) { // 不是心跳
            // 处理接收到的响应，唤醒等待请求结果的线程
            DefaultFuture.received(channel, response);
        }
    }

    private static boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(url.getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    void handlerEvent(Channel channel, Request req) throws RemotingException {
        if (req.getData() != null && req.getData().equals(READONLY_EVENT)) {
            channel.setAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY, Boolean.TRUE);
        }
    }

    void handleRequest(final ExchangeChannel channel, Request req) throws RemotingException {
        // 创建一个响应对象
        Response res = new Response(req.getId(), req.getVersion());
        if (req.isBroken()) { // 异常请求
            Object data = req.getData();

            String msg;
            if (data == null) {
                msg = null;
            } else if (data instanceof Throwable) {
                msg = StringUtils.toString((Throwable) data);
            } else {
                msg = data.toString();
            }
            // 设置异常信息
            res.setErrorMessage("Fail to decode request due to: " + msg);
            // 设置状态为请求错误
            res.setStatus(Response.BAD_REQUEST);

            // 直接发送响应
            channel.send(res);
            return;
        }
        // find handler by message class.
        // 获取请求数据
        Object msg = req.getData();
        try {
            /**
             * 通过代理的信息交换处理器处理这个请求，得到一个 {@link CompletableFuture<Object>} 对象
             *
             * @see org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler
             */
            CompletionStage<Object> future = handler.reply(channel, msg);
            // 当这个 CompletionStage 完成时，返回执行结果
            future.whenComplete((appResult, t) -> {
                try {
                    if (t == null) { // 没有发生异常
                        // 设置正常响应码
                        res.setStatus(Response.OK);
                        // 设置响应数据
                        res.setResult(appResult);
                    } else { // 发生异常
                        // 设置服务端出现异常响应码
                        res.setStatus(Response.SERVICE_ERROR);
                        // 设置异常信息
                        res.setErrorMessage(StringUtils.toString(t));
                    }
                    /**
                     * 向请求通道发送响应
                     * 参考 {@link org.apache.dubbo.remoting.transport.netty4.NettyChannel#send } 方法
                     */
                    channel.send(res);
                } catch (RemotingException e) {
                    logger.warn("Send result to consumer failed, channel is " + channel + ", msg is " + e);
                }
            });
        } catch (Throwable e) {
            res.setStatus(Response.SERVICE_ERROR);
            res.setErrorMessage(StringUtils.toString(e));
            channel.send(res);
        }
    }

    @Override
    public void connected(Channel channel) throws RemotingException {
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        handler.connected(exchangeChannel);
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.disconnected(exchangeChannel);
        } finally {
            DefaultFuture.closeChannel(channel);
            HeaderExchangeChannel.removeChannel(channel);
        }
    }

    // 发送消息
    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        Throwable exception = null;
        try {
            // 从 `channel` 的 `org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeChannel.CHANNEL` 属性获取 HeaderExchangeChannel 对象
            // 没有的话，创建一个 HeaderExchangeChannel 基于头部的信息交换处理通道对象，包装这个 `channel`
            ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
            // 发送请求
            handler.sent(exchangeChannel, message);
        } catch (Throwable t) {
            exception = t;
            // 从 `channel` 中移除 ExchangeChannel 对象，若已断开
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
        if (message instanceof Request) { // 请求
            Request request = (Request) message;
            // 记录发送时间
            DefaultFuture.sent(channel, request);
        }
        if (exception != null) {
            if (exception instanceof RuntimeException) {
                throw (RuntimeException) exception;
            } else if (exception instanceof RemotingException) {
                throw (RemotingException) exception;
            } else {
                throw new RemotingException(channel.getLocalAddress(), channel.getRemoteAddress(),
                        exception.getMessage(), exception);
            }
        }
    }

    // 处理接收到的消息
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 从 `channel` 的 `org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeChannel.CHANNEL` 属性获取 HeaderExchangeChannel 对象
        // 没有的话，创建一个 HeaderExchangeChannel 基于头部的信息交换处理通道对象，包装这个 `channel`
        final ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        if (message instanceof Request) { // 请求消息
            // handle request.
            Request request = (Request) message;
            if (request.isEvent()) { // 事件
                // 设置为只读
                handlerEvent(channel, request);
            } else {
                if (request.isTwoWay()) { // 需要响应
                    // 处理请求，异步处理，完成时向请求通道发送响应
                    handleRequest(exchangeChannel, request);
                } else { // 不需要响应
                    // 直接让代理的信息交换处理器处理请求，异步处理，不用关心执行结果
                    handler.received(exchangeChannel, request.getData());
                }
            }
        } else if (message instanceof Response) { // 响应消息
            // 处理响应
            handleResponse(channel, (Response) message);
        } else if (message instanceof String) { // 字符串类型
            if (isClientSide(channel)) { // 客户端，打印错误日志
                Exception e = new Exception("Dubbo client can not supported string message: " + message + " in channel: " + channel + ", url: " + channel.getUrl());
                logger.error(e.getMessage(), e);
            } else { // 否则，telnet 命令
                String echo = handler.telnet(channel, (String) message);
                if (echo != null && echo.length() > 0) {
                    channel.send(echo);
                }
            }
        } else {
            handler.received(exchangeChannel, message);
        }
    }

    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        // 当发生 ExecutionException 异常，返回异常响应( Response )
        if (exception instanceof ExecutionException) {
            ExecutionException e = (ExecutionException) exception;
            Object msg = e.getRequest();
            if (msg instanceof Request) {
                Request req = (Request) msg;
                // 需要响应，并且非心跳事件
                if (req.isTwoWay() && !req.isHeartbeat()) {
                    Response res = new Response(req.getId(), req.getVersion());
                    res.setStatus(Response.SERVER_ERROR);
                    res.setErrorMessage(StringUtils.toString(e));
                    channel.send(res);
                    return;
                }
            }
        }
        // 从 `channel` 的 `org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeChannel.CHANNEL` 属性获取 HeaderExchangeChannel 对象
        // 没有的话，创建一个 HeaderExchangeChannel 基于头部的信息交换处理通道对象，包装这个 `channel`
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            // 让代理的信息交换处理器处理异常
            handler.caught(exchangeChannel, exception);
        } finally {
            // 从 `channel` 中移除 ExchangeChannel 对象，若已断开
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public ChannelHandler getHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }
}
