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
package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.Destroyable;
import org.apache.dubbo.rpc.service.EchoService;
import org.apache.dubbo.rpc.service.GenericService;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.rpc.Constants.INTERFACES;

/**
 * 代理工厂抽象类，提供通用逻辑
 * <p>
 * AbstractProxyFactory
 */
public abstract class AbstractProxyFactory implements ProxyFactory {

    /**
     * 动态代理对象需要实现的接口
     */
    private static final Class<?>[] INTERNAL_INTERFACES = new Class<?>[]{
            // 用于回声测试，在消费者端引用时可强制转换成该对象，检测服务是否可用
            EchoService.class,
            // 用于销毁这个 Invoker 对象
            Destroyable.class
    };

    @Override
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        // 默认不用泛化引用
        return getProxy(invoker, false);
    }

    @Override
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
        // 需要代理的接口
        LinkedHashSet<Class<?>> interfaces = new LinkedHashSet<>();

        // 从 Invoker 的 URL 对象的 `interfaces` 参数中获取需要代理的接口，前面没注意到有这个参数，暂时忽略
        String config = invoker.getUrl().getParameter(INTERFACES);
        if (config != null && config.length() > 0) {
            String[] types = COMMA_SPLIT_PATTERN.split(config);
            for (String type : types) {
                // TODO can we load successfully for a different classloader?.
                interfaces.add(ReflectUtils.forName(type));
            }
        }

        // 如果需要泛化引用，那么 Invoker 的 URL 对象设置的接口类型为 GenericService.class 对象
        if (generic) {
            // 兼容老版本，添加一个 `com.alibaba.dubbo.rpc.service.GenericService.class` 接口
            if (GenericService.class.equals(invoker.getInterface()) || !GenericService.class.isAssignableFrom(invoker.getInterface())) {
                interfaces.add(com.alibaba.dubbo.rpc.service.GenericService.class);
            }

            try {
                // find the real interface from url
                // 如果是泛化引用，则根据 Invoker 的 URL 对象的 `interface` 参数拿到真正的引用接口名称
                String realInterface = invoker.getUrl().getParameter(Constants.INTERFACE);
                // 同时也添加到需要代理的接口中
                interfaces.add(ReflectUtils.forName(realInterface));
            } catch (Throwable e) {
                // ignore
            }
        }

        // 添加引用服务的类型，开启了泛化引用，这里的得到的就是 GenericService.class 对象
        interfaces.add(invoker.getInterface());
        // 添加内部的 EchoService 和 Destroyable 两个接口
        interfaces.addAll(Arrays.asList(INTERNAL_INTERFACES));

        // 创建一个动态代理对象，抽象方法
        return getProxy(invoker, interfaces.toArray(new Class<?>[0]));
    }

    public abstract <T> T getProxy(Invoker<T> invoker, Class<?>[] types);

}
