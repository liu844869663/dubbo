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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.AbstractClient;
import org.apache.dubbo.remoting.utils.UrlUtils;

import java.net.InetSocketAddress;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.dubbo.common.constants.CommonConstants.SSL_ENABLED_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_CONNECT_TIMEOUT;
import static org.apache.dubbo.remoting.transport.netty4.NettyEventLoopFactory.eventLoopGroup;
import static org.apache.dubbo.remoting.transport.netty4.NettyEventLoopFactory.socketChannelClass;

/**
 * NettyClient.
 */
public class NettyClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);
    /**
     * netty 客户端线程组，线程数默认是 `cpu个数+1`
     *
     * netty client bootstrap
     */
    private static final EventLoopGroup EVENT_LOOP_GROUP = eventLoopGroup(Constants.DEFAULT_IO_THREADS, "NettyClientWorker");

    private static final String SOCKS_PROXY_HOST = "socksProxyHost";

    private static final String SOCKS_PROXY_PORT = "socksProxyPort";

    private static final String DEFAULT_SOCKS_PROXY_PORT = "1080";

    /**
     * netty server bootstrap.
     */
    private Bootstrap bootstrap;

    /**
     * current channel. Each successful invocation of {@link NettyClient#doConnect()} will replace this with new channel and close old channel.
     * <b>volatile, please copy reference to use.</b>
     * <p>
     * 因为客户端可能会断开重连，需要保证多线程的可见性。
     */
    private volatile Channel channel;

    /**
     * The constructor of NettyClient.
     * It wil init and start netty.
     */
    public NettyClient(final URL url, final ChannelHandler handler) throws RemotingException {
        // you can customize name and type of client thread pool by THREAD_NAME_KEY and THREADPOOL_KEY in CommonConstants.
        // the handler will be wrapped: MultiMessageHandler->HeartbeatHandler->handler
        /**
         * 包装这个 ChannelHandler 通道处理对象，实现 Dubbo 线程模型的工厂
         * 1. Dubbo SPI 获取对应的 {@link org.apache.dubbo.remoting.Dispatcher} 扩展点实现类对其进行处理
         *    例如默认使用 `all` 线程模型，由 AllDispatcher 将其包装成 {@link org.apache.dubbo.remoting.transport.dispatcher.all.AllChannelHandler}
         *    服务端发送过来的响应在线程池中处理
         * 2. 再包装成 HeartbeatHandler 对象，支持心跳检测的处理
         * 3. 再包装成 MultiMessageHandler 对象，支持多消息的处理
         */
        super(url, wrapChannelHandler(url, handler));
    }

    /**
     * Init bootstrap
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {
        final NettyClientHandler nettyClientHandler = new NettyClientHandler(getUrl(), this);
        // 实例化 netty Bootstrap 启动器对象
        bootstrap = new Bootstrap();
        // 设置线程组
        bootstrap.group(EVENT_LOOP_GROUP)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) // 对象池，重用缓冲区
                //.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getTimeout())
                .channel(socketChannelClass()); // 设置 Channel 类型，分为 epoll 和 nio

        // 设置连接超时时间，不得低于 3s
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(DEFAULT_CONNECT_TIMEOUT, getConnectTimeout()));
        // 设置责任链
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                int heartbeatInterval = UrlUtils.getHeartbeat(getUrl());

                if (getUrl().getParameter(SSL_ENABLED_KEY, false)) {
                    ch.pipeline().addLast("negotiation", SslHandlerInitializer.sslClientHandler(getUrl(), nettyClientHandler));
                }

                // 创建 NettyCodecAdapter 对象，编解码的适配器
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyClient.this);
                ch.pipeline()//.addLast("logging",new LoggingHandler(LogLevel.INFO))//for debug
                        .addLast("decoder", adapter.getDecoder()) // 解码
                        .addLast("encoder", adapter.getEncoder()) // 编码
                        // 空闲检测
                        // readerIdleTimeSeconds：读超时，即当在指定的时间间隔内没有从 Channel 读取到数据时，会触发一个 READER_IDLE 状态的 IdleStateEvent 事件
                        // writerIdleTimeSeconds：写超时，即当在指定的时间间隔内没有数据写入到 Channel 时, 会触发一个 WRITER_IDLE 状态的 IdleStateEvent 事件
                        // allIdleTimeSeconds：读/写超时，即当在指定的时间间隔内没有读或写操作时, 会触发一个 ALL_IDLE 状态的 IdleStateEvent 事件
                        .addLast("client-idle-handler", new IdleStateHandler(heartbeatInterval, 0, 0, MILLISECONDS))
                        .addLast("handler", nettyClientHandler); // 处理器

                String socksProxyHost = ConfigUtils.getProperty(SOCKS_PROXY_HOST);
                if (socksProxyHost != null) {
                    int socksProxyPort = Integer.parseInt(ConfigUtils.getProperty(SOCKS_PROXY_PORT, DEFAULT_SOCKS_PROXY_PORT));
                    Socks5ProxyHandler socks5ProxyHandler = new Socks5ProxyHandler(new InetSocketAddress(socksProxyHost, socksProxyPort));
                    ch.pipeline().addFirst(socks5ProxyHandler);
                }
            }
        });
    }

    /**
     * 连接远程服务器
     *
     * @throws Throwable
     */
    @Override
    protected void doConnect() throws Throwable {
        long start = System.currentTimeMillis();
        // 连接服务器
        ChannelFuture future = bootstrap.connect(getConnectAddress());
        try {
            // 等待连接成功或者超时，默认 3s 超时时间
            boolean ret = future.awaitUninterruptibly(getConnectTimeout(), MILLISECONDS);

            if (ret && future.isSuccess()) { // 连接成功
                Channel newChannel = future.channel();
                try {
                    // Close old channel
                    // copy reference
                    // 关闭老的连接
                    Channel oldChannel = NettyClient.this.channel;
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            oldChannel.close();
                        } finally {
                            NettyChannel.removeChannelIfDisconnected(oldChannel);
                        }
                    }
                } finally {
                    // 若 NettyClient 被关闭，关闭连接
                    if (NettyClient.this.isClosed()) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close new netty channel " + newChannel + ", because the client closed.");
                            }
                            newChannel.close();
                        } finally {
                            NettyClient.this.channel = null;
                            NettyChannel.removeChannelIfDisconnected(newChannel);
                        }
                    } else { // 否则，设置新的连接
                        NettyClient.this.channel = newChannel;
                    }
                }
            } else if (future.cause() != null) { // 否则，连接发生异常，则抛出
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + ", error message is:" + future.cause().getMessage(), future.cause());
            } else { // 否则，无结果（连接超时），抛出超时异常
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + " client-side timeout "
                        + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion());
            }
        } finally {
            // just add new valid channel to NettyChannel's cache
            if (!isConnected()) {
                //future.cancel(true);
            }
        }
    }

    @Override
    protected void doDisConnect() throws Throwable {
        try {
            // 如果 netty 断开连接，则从 NettyChannel 移除缓存，将 dubbo channel 标记为无效
            NettyChannel.removeChannelIfDisconnected(channel);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    @Override
    protected void doClose() throws Throwable {
        // can't shutdown nioEventLoopGroup because the method will be invoked when closing one channel but not a client,
        // but when and how to close the nioEventLoopGroup ?
        // nioEventLoopGroup.shutdownGracefully();
    }

    @Override
    protected org.apache.dubbo.remoting.Channel getChannel() {
        Channel c = channel;
        if (c == null) {
            return null;
        }
        return NettyChannel.getOrAddChannel(c, getUrl(), this);
    }

    Channel getNettyChannel() {
        return channel;
    }

    @Override
    public boolean canHandleIdle() {
        return true;
    }
}
