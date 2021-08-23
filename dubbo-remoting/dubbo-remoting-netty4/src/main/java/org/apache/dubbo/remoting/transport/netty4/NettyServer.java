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
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.transport.AbstractServer;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;
import org.apache.dubbo.remoting.utils.UrlUtils;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.dubbo.common.constants.CommonConstants.IO_THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.KEEP_ALIVE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SSL_ENABLED_KEY;


/**
 * Netty 服务器的实现
 *
 * NettyServer.
 */
public class NettyServer extends AbstractServer implements RemotingServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);
    /**
     * 缓存存活的 dubbo channel（Netty Channel），这里你可以理解为连接到当前服务器的客户端通道
     *
     * the cache for alive worker channel.
     * <ip:port, dubbo channel>
     */
    private Map<String, Channel> channels;
    /**
     * netty server bootstrap.
     */
    private ServerBootstrap bootstrap;
    /**
     * the boss channel that receive connections and dispatch these to worker channel.
     */
	private io.netty.channel.Channel channel;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        // you can customize name and type of client thread pool by THREAD_NAME_KEY and THREADPOOL_KEY in CommonConstants.
        // the handler will be wrapped: MultiMessageHandler->HeartbeatHandler->handler
        /**
         * 1. 设置线程池的名称，在后面加上 `-{host}:{port}` 后缀
         *
         * 2. 包装这个 ChannelHandler 通道处理对象，实现 Dubbo 线程模型的工厂
         *    1. Dubbo SPI 获取对应的 {@link org.apache.dubbo.remoting.Dispatcher} 扩展点实现类对其进行处理
         *       例如默认使用 `all` 线程模型，由 AllDispatcher 将其包装成 {@link org.apache.dubbo.remoting.transport.dispatcher.all.AllChannelHandler}
         *       远程发送过来的请求在线程池中处理
         *    2. 再包装成 HeartbeatHandler 对象，支持心跳检测的处理
         *    3. 再包装成 MultiMessageHandler 对象，支持多消息的处理
         */
        super(ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME), ChannelHandlers.wrap(handler, url));
    }

    /**
     * 初始化并启动 Netty 服务器
     *
     * Init and start netty server
     *
     * @throws Throwable 过程中可能出现的异常
     */
    @Override
    protected void doOpen() throws Throwable {
        // 实例化 netty ServerBootstrap 启动器对象
        bootstrap = new ServerBootstrap();

        // 创建一个单线程的父 EventLoopGroup
        // 负责处理连接，故开销很小，连接到来，马上按照策略将 SocketChannel 转发给子 EventLoopGroup 进行处理
        bossGroup = NettyEventLoopFactory.eventLoopGroup(1, "NettyServerBoss");
        // 根据 `iothreads` 获取指定的 io 线程数，默认取 `cpu个数+1`，不能超过 32，尽量避免线程上下文切换的开销
        // 创建一个指定线程数的子 EventLoopGroup，用于数据处理
        workerGroup = NettyEventLoopFactory.eventLoopGroup( getUrl().getPositiveParameter(IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS), "NettyServerWorker");

        // 创建一个 netty 服务器处理器对象，主要用于将 netty channel 包装成 dubbo channel
        // 这里传入的 ChannelHandler 对象是 `this`，包装了上面入参中的 `handler`
        final NettyServerHandler nettyServerHandler = new NettyServerHandler(getUrl(), this);
        // 引用这个对象里面的缓存
        channels = nettyServerHandler.getChannels();

        boolean keepalive = getUrl().getParameter(KEEP_ALIVE_KEY, Boolean.FALSE);

        // 设置线程组
        bootstrap.group(bossGroup, workerGroup)
                // 设置 Channel 类型，分为 epoll 和 nio
                .channel(NettyEventLoopFactory.serverSocketChannelClass())
                // 设置可选项
                .option(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.SO_KEEPALIVE, keepalive)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) // 对象池，重用缓冲区
                // 设置责任链路
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        // FIXME: should we use getTimeout()?
                        int idleTimeout = UrlUtils.getIdleTimeout(getUrl());
                        // 创建 NettyCodecAdapter 对象，编解码的适配器
                        NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                        if (getUrl().getParameter(SSL_ENABLED_KEY, false)) {
                            ch.pipeline().addLast("negotiation", SslHandlerInitializer.sslServerHandler(getUrl(), nettyServerHandler));
                        }
                        ch.pipeline()
                                .addLast("decoder", adapter.getDecoder()) // 解码
                                .addLast("encoder", adapter.getEncoder()) // 编码
                                // 空闲检测，readerIdleTime 为 0 表示禁用
                                .addLast("server-idle-handler", new IdleStateHandler(0, 0, idleTimeout, MILLISECONDS))
                                .addLast("handler", nettyServerHandler); // 处理器
                    }
                });
        // bind
        // 服务器绑定监听端口
        ChannelFuture channelFuture = bootstrap.bind(getBindAddress());
        channelFuture.syncUninterruptibly();
        channel = channelFuture.channel();

    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                // 关闭服务器通道
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            Collection<Channel> channels = getChannels();
            if (channels != null && channels.size() > 0) {
                for (Channel channel : channels) {
                    try {
                        // 关闭 dubbo channel，也就是连接到服务器的客户端通道
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                // 优雅的关闭工作组，异步
                bossGroup.shutdownGracefully().syncUninterruptibly();
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                // 清空缓存
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new ArrayList<>(this.channels.size());
        chs.addAll(this.channels.values());
        // check of connection status is unnecessary since we are using channels in NettyServerHandler
//        for (Channel channel : this.channels.values()) {
//            if (channel.isConnected()) {
//                chs.add(channel);
//            } else {
//                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
//            }
//        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        // 根据地址获取对应的 dubbo channel（NettyChannel）
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean canHandleIdle() {
        return true;
    }

    @Override
    public boolean isBound() {
        return channel.isActive();
    }

}
