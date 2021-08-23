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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;


/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    /**
     * Dubbo 默认端口
     */
    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static volatile DubboProtocol INSTANCE;
    private static Object MONITOR = new Object();

    /**
     * <host:port,Exchanger>
     * {@link Map<String, List<ReferenceCountExchangeClient>}
     */
    private final Map<String, Object> referenceClientMap = new ConcurrentHashMap<>();
    private static final Object PENDING_OBJECT = new Object();
    /**
     * 保存用户的 {@link SerializationOptimizer} 序列化优化器对象
     *
     * 通过优化器返回的对象将使用指定的序列化方式，例如你可以指定 `kryo`，这样可以让这些对象的序列化的性能的得到大大的提升
     * Dubbo 已经自动将 JDK 中的常用类进行了注册，提供 {@link SerializationOptimizer} 接口来让用户注册
     *
     * 参考 https://dubbo.apache.org/zh/docs/v3.0/references/serializations/serialization/
     */
    private final Set<String> optimizers = new ConcurrentHashSet<>();

    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            // Dubbo 服务之间传输消息都是以 Invocation 进行传输
            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            Invocation inv = (Invocation) message;
            // 从 `exporterMap` 本地缓存中根据服务的键找到对应的 Exporter 对象
            // 然后获取其内部的 Invoker 对象
            Invoker<?> invoker = getInvoker(channel, inv);
            // need to consider backward-compatibility if it's a callback
            // 如果是参数回调，校验消费者执行的方法是否存在
            if (Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get(METHODS_KEY);
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(COMMA_SEPARATOR)) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(COMMA_SEPARATOR);
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
            // 由 Invoker 对象执行对应的方法
            // 当然，这是一条 Invoker 链，前面可能有一系列的 Filter 过滤器
            Result result = invoker.invoke(inv);
            // 返回执行结果
            return result.thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            // 接收到远程的请求消息
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(method, url.getParameter(INTERFACE_KEY), "", new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getParameter(GROUP_KEY));
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getParameter(VERSION_KEY));
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
    }

    public static DubboProtocol getDubboProtocol() {
        if (null == INSTANCE) {
            synchronized (MONITOR) {
                if (null == INSTANCE) {
                    INSTANCE = (DubboProtocol) ExtensionLoader.getExtensionLoader(Protocol.class).getOriginalInstance(DubboProtocol.NAME);
                }
            }
        }
        return INSTANCE;
    }

    @Override
    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = (String) inv.getObjectAttachments().get(PATH_KEY);

        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }

        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getObjectAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getObjectAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(
                port,
                path,
                (String) inv.getObjectAttachments().get(VERSION_KEY),
                (String) inv.getObjectAttachments().get(GROUP_KEY)
        );
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel,
                    "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                            ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() +
                            ", message:" + getInvocationWithoutData(inv));
        }

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 获取 Registry 注册中心的 URL 对象
        // 里面有一个 `export` 参数包括了 Dubbo 服务对应的 URL 对象（编码后的字符串）
        URL url = invoker.getUrl();

        // export service.
        // 创建一个 Dubbo 服务的键，参考：`分组名称/接口名称:版本号:端口号`
        String key = serviceKey(url);
        // 创建一个 DubboExporter 对象
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 将其保存至 `exporterMap` 中
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            }
        }

        // 启动服务器，例如使用 netty 监听指定端口，当接收到消费者的请求包时，执行对应服务的对应方法
        openServer(url);
        // 保存用户配置的 SerializationOptimizer 序列化优化器
        optimizeSerialization(url);

        return exporter;
    }

    private void openServer(URL url) {
        // find server.
        // 获取注册中心的地址
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        // 该配置项不存在了，忽略
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (isServer) {
            // 从缓存中获取该注册中心对应通信服务器，保证不重复创建
            ProtocolServer server = serverMap.get(key);
            if (server == null) {
                // 缓存未命中，加锁判断，保证线程安全
                synchronized (this) {
                    server = serverMap.get(key);
                    // 还是未命中，则创建一个通信服务器
                    if (server == null) {
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // server supports reset, use together with override
                // 已存在，则重置通信服务器上面该 URL 的相关属性
                server.reset(url);
            }
        }
    }

    private ProtocolServer createServer(URL url) {
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                // 默认开启 Server 关闭时发送 READ_ONLY 事件
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default
                // 默认开启心跳检测，60s
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))
                // 设置编码器为 Dubbo
                .addParameter(CODEC_KEY, DubboCodec.NAME)
                .build();
        // 获取 `server` 参数，也就是该协议的服务端实现类型，默认为 netty
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);

        // 根据 Transporter 的扩展点实现类判断是否支持 `server`
        // dubbo 协议支持 grizzly、mina、netty3 和 netty4（netty）这几种
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            /**
             * 启动服务器
             * 例如使用 netty 在这里会通过 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter} 创建一个 {@link org.apache.dubbo.remoting.transport.netty4.NettyServer} 对象
             * 1. 创建过程会使用根据 `url` 信息初始化一个线程池，用来处理远程发过来的请求
             * 2. 启动 netty 服务，监听本地指定的 dubbo 端口，当服务消费者发送数据过来请求服务提供者时，会被这里监听到
             * 3. 到最后会由 {@link this#requestHandler} 处理发送过来的消息包，也就是执行对应服务的对应方法
             */
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        // 如果通过 `client` 指定了 dubbo 协议的客户端实现类型，则判断是否支持
        str = url.getParameter(CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        return new DubboProtocolServer(server);
    }

    private void optimizeSerialization(URL url) throws RpcException {
        // 获取 `optimizer` 参数
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            //
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException(
                        "The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        }
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        // 保存用户配置的 SerializationOptimizer 序列化优化器
        optimizeSerialization(url);

        // create rpc invoker.
        // 首先获取与服务提供者的通信客户端集合，默认初始化一个通信客户端，与服务提供者建立连接（使用 netty 通信）
        // 然后将入参信息封装 DubboInvoker 对象
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);

        return invoker;
    }

    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        // 获取用户配置的 `connections` 参数
        // 对每个提供者的最大连接数，dubbo 等长连接协表示建立的长连接个数
        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {
            /*
             * The xml configuration should have a higher priority than properties.
             * 允许 XML 配置 `shareconnections` 更高级别参数
             */
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            // 默认情况下消费者 JVM 和提供者 JVM 共享一个 TCP 长连接
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            // 初始化 connections 数量的通信客户端，与服务提供者建立连接（默认使用 netty 通信）
            // 共享，可以一同使用
            return getSharedClient(url, connections).toArray(new ExchangeClient[0]);
        // 否则，用户配置了一定数量连接数
        } else {
            ExchangeClient[] clients = new ExchangeClient[connections];
            for (int i = 0; i < clients.length; i++) {
                // 初始化一个通信客户端，与服务提供者建立连接（默认使用 netty 通信）
                clients[i] = initClient(url);
            }
            return clients;
        }

    }

    /**
     * Get shared connection
     *
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    @SuppressWarnings("unchecked")
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        // 获取提供者地址：`host:port`
        String key = url.getAddress();

        // 尝试从缓存中获取
        Object clients = referenceClientMap.get(key);
        if (clients instanceof List) {
            List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;
            if (checkClientCanUse(typedClients)) {
                batchClientRefIncr(typedClients);
                return typedClients;
            }
        }

        List<ReferenceCountExchangeClient> typedClients = null;

        // 缓存中没有数据，则加锁对这个缓存对象进行更新
        synchronized (referenceClientMap) {
            for (; ; ) {
                // 再次尝试从缓存中获取
                clients = referenceClientMap.get(key);

                if (clients instanceof List) {
                    typedClients = (List<ReferenceCountExchangeClient>) clients;
                    if (checkClientCanUse(typedClients)) {
                        batchClientRefIncr(typedClients);
                        return typedClients;
                    } else {
                        referenceClientMap.put(key, PENDING_OBJECT);
                        break;
                    }
                } else if (clients == PENDING_OBJECT) {
                    try {
                        referenceClientMap.wait();
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    referenceClientMap.put(key, PENDING_OBJECT);
                    break;
                }
            }
        }

        try {
            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            // 如果没有通信客户端，则需要初始化
            if (CollectionUtils.isEmpty(typedClients)) {
                // 初始化 connectNum 数量的通信客户端，与服务提供者建立连接（默认使用 netty 通信）
                typedClients = buildReferenceCountExchangeClientList(url, connectNum);
            // 否则，可以直接新增对应通信客户端的引用数量
            } else {
                for (int i = 0; i < typedClients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = typedClients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    // 如果这个通信客户端被关闭了，这里重新创建一个
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        typedClients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    // 引用数量加一
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }
        } finally {
            // 加锁，更新缓存
            synchronized (referenceClientMap) {
                if (typedClients == null) {
                    referenceClientMap.remove(key);
                } else {
                    referenceClientMap.put(key, typedClients);
                }
                referenceClientMap.notifyAll();
            }
        }
        return typedClients;

    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.getCount() <= 0 ||
                    referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        // 初始化一个通信客户端，与服务提供者建立连接（默认使用 netty 通信）
        ExchangeClient exchangeClient = initClient(url);

        // 封装成 ReferenceCountExchangeClient 对象，记录引用数量
        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * Create new connection
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        // 获取协议通讯层的实现类型，优先使用 `client` 客户端配置，在使用 `server` 服务端配置，默认为 netty
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        // 设置协议编码方式为 dubbo
        url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        // 启动心跳，默认一分钟
        url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        // 根据 Transporter 的扩展点实现类判断是否支持协议通讯层的实现类型
        // dubbo 协议支持 grizzly、mina、netty3 和 netty4（netty）这几种
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " +
                    StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            // 是否延迟创建连接，默认不需要
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {
                /**
                 * 创建一个客户端，与服务提供者进行连接
                 * 例如使用 `netty`，对应 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter} 传输层对象，由它创建一个 {@link org.apache.dubbo.remoting.transport.netty4.NettyClient} 客户端对象
                 * 创建过程会启动 netty 服务，与远程服务提供者建立连接，当消费方调用远程服务是，则通过该连接来发送请求至服务提供方
                 */
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }

            RemotingServer server = protocolServer.getRemotingServer();

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            Object clients = referenceClientMap.remove(key);
            if (clients instanceof List) {
                List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;

                if (CollectionUtils.isEmpty(typedClients)) {
                    continue;
                }

                for (ReferenceCountExchangeClient client : typedClients) {
                    closeReferenceCountExchangeClient(client);
                }
            }
        }

        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /*
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation
     * @return
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}
