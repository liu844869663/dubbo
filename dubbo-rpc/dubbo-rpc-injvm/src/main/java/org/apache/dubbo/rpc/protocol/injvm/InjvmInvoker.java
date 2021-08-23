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
package org.apache.dubbo.rpc.protocol.injvm;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.FutureContext;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.InvokeMode;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.rpc.Constants.ASYNC_KEY;

/**
 * InjvmInvoker
 */
class InjvmInvoker<T> extends AbstractInvoker<T> {

    private final String key;

    private final Map<String, Exporter<?>> exporterMap;

    private final ExecutorRepository executorRepository = ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();

    InjvmInvoker(Class<T> type, URL url, String key, Map<String, Exporter<?>> exporterMap) {
        super(type, url);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    @Override
    public boolean isAvailable() {
        // 判断是否有对应的 Exporter 对象
        InjvmExporter<?> exporter = (InjvmExporter<?>) exporterMap.get(key);
        if (exporter == null) {
            return false;
        } else {
            // 调用父类的是否可用方法，默认为 true
            return super.isAvailable();
        }
    }

    @Override
    public Result doInvoke(Invocation invocation) throws Throwable {
        // 从缓存中根据 URL 对象获取对应的 Exporter 对象，接口名称，分组名称和版本名称相同
        Exporter<?> exporter = InjvmProtocol.getExporter(exporterMap, getUrl());
        if (exporter == null) {
            throw new RpcException("Service [" + key + "] not found.");
        }
        // 设置服务提供者地址为本地
        RpcContext.getContext().setRemoteAddress(LOCALHOST_VALUE, 0);
        // Solve local exposure, the server opens the token, and the client call fails.
        // 从服务端本地获取对应的 URL 对象，因为是 injvm 调用，客户端不需要配置 token
        URL serverURL = exporter.getInvoker().getUrl();
        // 如果需要 token 验证，则设置 token 为服务端配置的 token
        boolean serverHasToken = serverURL.hasParameter(Constants.TOKEN_KEY);
        if (serverHasToken) {
            invocation.setAttachment(Constants.TOKEN_KEY, serverURL.getParameter(Constants.TOKEN_KEY));
        }

        // 判断是否开启了异步处理，消费者的配置服务提供者的配置
        if (isAsync(exporter.getInvoker().getUrl(), getUrl())) {
            // 设置执行模式为异步
            ((RpcInvocation) invocation).setInvokeMode(InvokeMode.ASYNC);
            // use consumer executor
            // 获取线程池
            ExecutorService executor = executorRepository.createExecutorIfAbsent(getUrl());
            // 创建一个 Future 对象，通过这个线程池去执行这个 Invoker
            CompletableFuture<AppResponse> appResponseFuture = CompletableFuture.supplyAsync(() -> {
                /**
                 * 获取 Export 内部的 Invoker 对象，执行本次请求，得到 Result 结果
                 * 这个 Invoker 也就会执行服务提供者的方法，参考 {@link JavassistProxyFactory} 中创建的 {@link AbstractProxyInvoker} 对象
                 */
                Result result = exporter.getInvoker().invoke(invocation);
                if (result.hasException()) {
                    // 抛出了异常，则将这个异常作为返回结果，封装成 AppResponse 对象
                    return new AppResponse(result.getException());
                } else {
                    // 将执行结果封装成 AppResponse 对象
                    return new AppResponse(result.getValue());
                }
            }, executor);
            // save for 2.6.x compatibility, for example, TraceFilter in Zipkin uses com.alibaba.xxx.FutureAdapter
            // 将这个 Future 对象保存至当前 PRC 上下文中，对之前的版本保持兼容
            FutureContext.getContext().setCompatibleFuture(appResponseFuture);
            // 将这个结果 Future 和 Invocation 封装成 AsyncRpcResult 对象
            AsyncRpcResult result = new AsyncRpcResult(appResponseFuture, invocation);
            // 设置线程池
            result.setExecutor(executor);
            // 返回结果
            return result;
        } else {
            /**
             * 获取 Export 内部的 Invoker 对象，执行本次请求，得到 Result 结果，并返回
             * 这个 Invoker 也就会执行服务提供者的方法，参考 {@link JavassistProxyFactory} 中创建的 {@link AbstractProxyInvoker} 对象
             */
            return exporter.getInvoker().invoke(invocation);
        }
    }

    private boolean isAsync(URL remoteUrl, URL localUrl) {
        if (localUrl.hasParameter(ASYNC_KEY)) {
            return localUrl.getParameter(ASYNC_KEY, false);
        }
        return remoteUrl.getParameter(ASYNC_KEY, false);
    }
}
