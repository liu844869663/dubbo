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
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_CLIENT_THREADPOOL;
import static org.apache.dubbo.common.constants.CommonConstants.THREADPOOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

/**
 * 客户端抽象类，重点实现了公用的重连逻辑
 * <p>
 * AbstractClient
 */
public abstract class AbstractClient extends AbstractEndpoint implements Client {

    protected static final String CLIENT_THREAD_POOL_NAME = "DubboClientHandler";
    private static final Logger logger = LoggerFactory.getLogger(AbstractClient.class);
    /**
     * 连接锁
     */
    private final Lock connectLock = new ReentrantLock();
    /**
     * 是否需要重连，默认需要
     */
    private final boolean needReconnect;
    //issue-7054:Consumer's executor is sharing globally.
    /**
     * 线程池
     */
    protected volatile ExecutorService executor;
    /**
     * 线程池仓库
     */
    private ExecutorRepository executorRepository = ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();

    public AbstractClient(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
        // set default needReconnect true when channel is not connected
        // 设置是否需要重连，默认需要
        needReconnect = url.getParameter(Constants.SEND_RECONNECT_KEY, true);

        // 初始化线程池
        initExecutor(url);

        try {
            // 初始化客户端
            doOpen();
        } catch (Throwable t) {
            // 抛出异常则关闭
            close();
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }

        try {
            // 连接服务器
            connect();
            if (logger.isInfoEnabled()) {
                logger.info("Start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress() + " connect to the server " + getRemoteAddress());
            }
        } catch (RemotingException t) {
            // 如果设置了 `check=true`，需要检查，则关闭，并抛出异常
            if (url.getParameter(Constants.CHECK_KEY, true)) {
                close();
                throw t;
            } else {
                logger.warn("Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                        + " connect to the server " + getRemoteAddress() + " (check == false, ignore and retry later!), cause: " + t.getMessage(), t);
            }
        } catch (Throwable t) {
            // 抛出异常则关闭
            close();
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }
    }

    private void initExecutor(URL url) {
        //issue-7054:Consumer's executor is sharing globally, thread name not require provider ip.
        // 添加 `threadname` 线程池名称参数，如果没有的话
        url = url.addParameter(THREAD_NAME_KEY, CLIENT_THREAD_POOL_NAME);
        // 添加 `threadpool` 线程池类型，如果没有的话，默认为 `fixed`
        url = url.addParameterIfAbsent(THREADPOOL_KEY, DEFAULT_CLIENT_THREADPOOL);
        /**
         * 1. 尝试从线程池仓库中根据 Dubbo 端口号（消费端取最大值）获取对应的线程池
         *
         * 2. 没有的话，Dubbo SPI 获取对应的 {@link ThreadPool} 实现类创建一个
         *
         * 3.例如 {@link FixedThreadPool} 创建一个线程池，核心线程数和最大线程数都是 200 个，固定好大小
         */
        executor = executorRepository.createExecutorIfAbsent(url);
    }

    protected static ChannelHandler wrapChannelHandler(URL url, ChannelHandler handler) {
        return ChannelHandlers.wrap(handler, url);
    }

    public InetSocketAddress getConnectAddress() {
        return new InetSocketAddress(NetUtils.filterLocalHost(getUrl().getHost()), getUrl().getPort());
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        Channel channel = getChannel();
        if (channel == null) {
            return getUrl().toInetSocketAddress();
        }
        return channel.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        Channel channel = getChannel();
        if (channel == null) {
            return InetSocketAddress.createUnresolved(NetUtils.getLocalHost(), 0);
        }
        return channel.getLocalAddress();
    }

    @Override
    public boolean isConnected() {
        Channel channel = getChannel();
        if (channel == null) {
            return false;
        }
        return channel.isConnected();
    }

    @Override
    public Object getAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return null;
        }
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        Channel channel = getChannel();
        if (channel == null) {
            return;
        }
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return;
        }
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return false;
        }
        return channel.hasAttribute(key);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 未连接时，先进行连接
        if (needReconnect && !isConnected()) {
            connect();
        }
        // 获取通信通道
        Channel channel = getChannel();
        //TODO Can the value returned by getChannel() be null? need improvement.
        if (channel == null || !channel.isConnected()) {
            throw new RemotingException(this, "message can not send, because channel is closed . url:" + getUrl());
        }
        // 发送消息
        channel.send(message, sent);
    }

    protected void connect() throws RemotingException {
        // 获得锁
        connectLock.lock();

        try {
            // 已连接，则直接返回
            if (isConnected()) {
                return;
            }

            // 已关闭，或者正在关闭，则打印日志，直接返回
            if (isClosed() || isClosing()) {
                logger.warn("No need to connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion() + ", cause: client status is closed or closing.");
                return;
            }

            // 执行连接
            doConnect();

            // 连接失败，抛出异常
            if (!isConnected()) {
                throw new RemotingException(this, "Failed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                                + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                                + ", cause: Connect wait timeout: " + getConnectTimeout() + "ms.");

            } else { // 否则，打印连接成功日志
                if (logger.isInfoEnabled()) {
                    logger.info("Successed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                                    + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                                    + ", channel is " + this.getChannel());
                }
            }

        } catch (RemotingException e) {
            throw e;

        } catch (Throwable e) {
            throw new RemotingException(this, "Failed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                            + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                            + ", cause: " + e.getMessage(), e);

        } finally {
            // 释放锁
            connectLock.unlock();
        }
    }

    public void disconnect() {
        connectLock.lock();
        try {
            try {
                Channel channel = getChannel();
                if (channel != null) {
                    channel.close();
                }
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
            try {
                doDisConnect();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void reconnect() throws RemotingException {
        if (!isConnected()) {
            connectLock.lock();
            try {
                if (!isConnected()) {
                    disconnect();
                    connect();
                }
            } finally {
                connectLock.unlock();
            }
        }
    }

    @Override
    public void close() {
        if (isClosed()) {
            logger.warn("No need to close connection to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " " + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion() + ", cause: the client status is closed.");
            return;
        }

        connectLock.lock();
        try {
            if (isClosed()) {
                logger.warn("No need to close connection to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " " + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion() + ", cause: the client status is closed.");
                return;
            }

            try {
                super.close();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }

            try {
                disconnect();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }

            try {
                doClose();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }

        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void close(int timeout) {
        close();
    }

    @Override
    public String toString() {
        return getClass().getName() + " [" + getLocalAddress() + " -> " + getRemoteAddress() + "]";
    }

    /**
     * Open client.
     *
     * @throws Throwable
     */
    protected abstract void doOpen() throws Throwable;

    /**
     * Close client.
     *
     * @throws Throwable
     */
    protected abstract void doClose() throws Throwable;

    /**
     * Connect to server.
     *
     * @throws Throwable
     */
    protected abstract void doConnect() throws Throwable;

    /**
     * disConnect to server.
     *
     * @throws Throwable
     */
    protected abstract void doDisConnect() throws Throwable;

    /**
     * Get the connected channel.
     *
     * @return channel
     */
    protected abstract Channel getChannel();
}
