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

import org.apache.dubbo.common.Parameters;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;

/**
 * Proxy 协议的抽象类，为 HTTP、REST 等协议的实现提供公用的服务逻辑
 * <p>
 * AbstractProxyProtocol
 */
public abstract class AbstractProxyProtocol extends AbstractProtocol {

    /**
     * 需要抛出的异常类集合
     */
    private final List<Class<?>> rpcExceptions = new CopyOnWriteArrayList<Class<?>>();

    protected ProxyFactory proxyFactory;

    public AbstractProxyProtocol() {
    }

    public AbstractProxyProtocol(Class<?>... exceptions) {
        for (Class<?> exception : exceptions) {
            addRpcException(exception);
        }
    }

    public void addRpcException(Class<?> exception) {
        this.rpcExceptions.add(exception);
    }

    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        // 获得服务键
        final String uri = serviceKey(invoker.getUrl());
        // 从缓存中获取 Exporter 对象，若已经暴露，则直接返回
        Exporter<T> exporter = (Exporter<T>) exporterMap.get(uri);
        if (exporter != null) {
            // When modifying the configuration through override, you need to re-expose the newly modified service.
            if (Objects.equals(exporter.getInvoker().getUrl(), invoker.getUrl())) {
                return exporter;
            }
        }
        /**
         * 执行暴露服务
         *
         * 1. 先创建一个动态代理对象
         * 2. 创建一个 Servlet 容器
         */
        final Runnable runnable = doExport(proxyFactory.getProxy(invoker, true), invoker.getInterface(), invoker.getUrl());
        exporter = new AbstractExporter<T>(invoker) {
            @Override
            public void afterUnExport() {
                // 从缓存中移除
                exporterMap.remove(uri);
                // 执行取消暴露的回调
                if (runnable != null) {
                    try {
                        runnable.run();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            }
        };
        // 放入缓存中
        exporterMap.put(uri, exporter);
        return exporter;
    }

    @Override
    protected <T> Invoker<T> protocolBindingRefer(final Class<T> type, final URL url) throws RpcException {
        /**
         * 执行引用服务
         *
         * 1. 先获取一个调用远程服务的 Service 对象
         * 2. 创建一个 Invoker 对象
         */
        final Invoker<T> target = proxyFactory.getInvoker(doRefer(type, url), type, url);
        // 创建 Invoker 对象
        Invoker<T> invoker = new AbstractInvoker<T>(type, url) {
            @Override
            protected Result doInvoke(Invocation invocation) throws Throwable {
                try {
                    // 执行调用
                    Result result = target.invoke(invocation);
                    // FIXME result is an AsyncRpcResult instance.
                    // 若返回结果带有异常，并且需要抛出，则抛出异常。
                    Throwable e = result.getException();
                    if (e != null) {
                        for (Class<?> rpcException : rpcExceptions) {
                            if (rpcException.isAssignableFrom(e.getClass())) {
                                throw getRpcException(type, url, invocation, e);
                            }
                        }
                    }
                    return result;
                } catch (RpcException e) {
                    // 若是未知异常，获得异常对应的错误码
                    if (e.getCode() == RpcException.UNKNOWN_EXCEPTION) {
                        e.setCode(getErrorCode(e.getCause()));
                    }
                    throw e;
                } catch (Throwable e) {
                    // 抛出 RpcException 异常
                    throw getRpcException(type, url, invocation, e);
                }
            }

            @Override
            public void destroy() {
                super.destroy();
                target.destroy();
                invokers.remove(this);
            }
        };
        // 添加到 Invoker 集合
        invokers.add(invoker);
        return invoker;
    }

    protected RpcException getRpcException(Class<?> type, URL url, Invocation invocation, Throwable e) {
        RpcException re = new RpcException("Failed to invoke remote service: " + type + ", method: "
                + invocation.getMethodName() + ", cause: " + e.getMessage(), e);
        re.setCode(getErrorCode(e));
        return re;
    }

    protected String getAddr(URL url) {
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        if (url.getParameter(ANYHOST_KEY, false)) {
            bindIp = ANYHOST_VALUE;
        }
        return NetUtils.getIpByHost(bindIp) + ":" + url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
    }

    protected int getErrorCode(Throwable e) {
        return RpcException.UNKNOWN_EXCEPTION;
    }

    /**
     * 执行暴露，并返回取消暴露的回调 Runnable
     *
     * @param impl 服务 Proxy 对象
     * @param type 服务接口
     * @param url  URL
     * @param <T>  服务接口
     * @return 消暴露的回调 Runnable
     * @throws RpcException 当发生异常
     */
    protected abstract <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException;

    /**
     * 执行引用，并返回调用远程服务的 Service 对象
     *
     * @param type 服务接口
     * @param url  URL
     * @param <T>  服务接口
     * @return 调用远程服务的 Service 对象
     * @throws RpcException 当发生异常
     */
    protected abstract <T> T doRefer(Class<T> type, URL url) throws RpcException;

    protected class ProxyProtocolServer implements ProtocolServer {

        private RemotingServer server;
        private String address;

        public ProxyProtocolServer(RemotingServer server) {
            this.server = server;
        }

        @Override
        public RemotingServer getRemotingServer() {
            return server;
        }

        @Override
        public String getAddress() {
            return StringUtils.isNotEmpty(address) ? address : server.getUrl().getAddress();
        }

        @Override
        public void setAddress(String address) {
            this.address = address;
        }

        @Override
        public URL getUrl() {
            return server.getUrl();
        }

        @Override
        public void close() {
            server.close();
        }
    }

    protected abstract class RemotingServerAdapter implements RemotingServer {

        public abstract Object getDelegateServer();

        /**
         * @return
         */
        @Override
        public boolean isBound() {
            return false;
        }

        @Override
        public Collection<Channel> getChannels() {
            return null;
        }

        @Override
        public Channel getChannel(InetSocketAddress remoteAddress) {
            return null;
        }

        @Override
        public void reset(Parameters parameters) {

        }

        @Override
        public void reset(URL url) {

        }

        @Override
        public URL getUrl() {
            return null;
        }

        @Override
        public ChannelHandler getChannelHandler() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public void send(Object message) throws RemotingException {

        }

        @Override
        public void send(Object message, boolean sent) throws RemotingException {

        }

        @Override
        public void close() {

        }

        @Override
        public void close(int timeout) {

        }

        @Override
        public void startClose() {

        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }


}
