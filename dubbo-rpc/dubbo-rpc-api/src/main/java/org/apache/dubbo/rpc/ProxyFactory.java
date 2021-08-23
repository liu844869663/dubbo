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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import static org.apache.dubbo.rpc.Constants.PROXY_KEY;

/**
 * 代理工厂接口
 * <p>
 * ProxyFactory. (API/SPI, Singleton, ThreadSafe)
 */
@SPI("javassist")
public interface ProxyFactory {

    /**
     * 根据这个 Invoker 对象创建一个动态代理对象
     * <p>
     * create proxy.
     *
     * @param invoker Invoker 对象
     * @return proxy 动态代理对象
     */
    @Adaptive({PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker) throws RpcException;

    /**
     * 根据这个 Invoker 对象创建一个动态代理对象
     * 泛化引用，实现 {@link org.apache.dubbo.rpc.service.GenericService} 接口
     * 在没有服务端模型的时候可通过其 `$invoke` 方法执行目标方法
     * <p>
     * create proxy.
     *
     * @param invoker Invoker 对象
     * @param generic 是否泛化引用
     * @return proxy 动态代理对象
     */
    @Adaptive({PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException;

    /**
     * 创建一个 Invoker 对象
     * <p>
     * create invoker.
     *
     * @param <T>   具体的 Dubbo 服务实现类的类型
     * @param proxy 具体的 Dubbo 服务实现类
     * @param type  服务接口类型
     * @param url   服务端 URL 对象
     * @return invoker Invoker 对象
     */
    @Adaptive({PROXY_KEY})
    <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException;

}