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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;

import java.lang.reflect.Method;


/**
 * ExceptionInvokerFilter
 * <p>
 * Functions:
 * <ol>
 *
 * 不期望的异常打 ERROR 日志( Provider端 )。不期望的日志即是，没有的接口上声明的 Unchecked 异常
 * <li>unexpected exception will be logged in ERROR level on provider side. Unexpected exception are unchecked exception not declared on the interface</li>
 *
 * 异常不在 API 包中，则 Wrap 一层 RuntimeException，RPC 对于第一层异常会直接序列化传输( Cause 异常会 String 化) ，避免异常在 Client 出不能反序列化问题
 * <li>Wrap the exception not introduced in API package into RuntimeException. Framework will serialize the outer exception but stringnize its cause in order to avoid of possible serialization problem on client side</li>
 *
 * </ol>
 */
@Activate(group = CommonConstants.PROVIDER)
public class ExceptionFilter implements Filter, Filter.Listener {
    private Logger logger = LoggerFactory.getLogger(ExceptionFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        // 有异常，并且非泛化调用
        if (appResponse.hasException() && GenericService.class != invoker.getInterface()) {
            try {
                Throwable exception = appResponse.getException();

                // directly throw if it's checked exception
                // 如果是 checked 异常，直接抛出
                if (!(exception instanceof RuntimeException) && (exception instanceof Exception)) {
                    return;
                }
                // directly throw if the exception appears in the signature
                try {
                    // 在方法签名上有声明，直接抛出
                    Method method = invoker.getInterface().getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                    Class<?>[] exceptionClasses = method.getExceptionTypes();
                    for (Class<?> exceptionClass : exceptionClasses) {
                        if (exception.getClass().equals(exceptionClass)) {
                            return;
                        }
                    }
                } catch (NoSuchMethodException e) {
                    return;
                }

                // for the exception not found in method's signature, print ERROR message in server's log.
                // 未在方法签名上定义的异常，在服务器端打印 ERROR 日志
                logger.error("Got unchecked and undeclared exception which called by " + RpcContext.getContext().getRemoteHost() + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName() + ", exception: " + exception.getClass().getName() + ": " + exception.getMessage(), exception);

                // directly throw if exception class and interface class are in the same jar file.
                // 异常类和接口类在同一 jar 包里，直接抛出
                String serviceFile = ReflectUtils.getCodeBase(invoker.getInterface());
                String exceptionFile = ReflectUtils.getCodeBase(exception.getClass());
                if (serviceFile == null || exceptionFile == null || serviceFile.equals(exceptionFile)) {
                    return;
                }
                // directly throw if it's JDK exception
                // 是JDK自带的异常，直接抛出
                String className = exception.getClass().getName();
                if (className.startsWith("java.") || className.startsWith("javax.")) {
                    return;
                }
                // directly throw if it's dubbo exception
                // 是Dubbo本身的异常，直接抛出
                if (exception instanceof RpcException) {
                    return;
                }

                // otherwise, wrap with RuntimeException and throw back to the client
                // 否则，包装成 RuntimeException 抛给客户端
                appResponse.setException(new RuntimeException(StringUtils.toString(exception)));
            } catch (Throwable e) {
                logger.warn("Fail to ExceptionFilter when called by " + RpcContext.getContext().getRemoteHost() + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName() + ", exception: " + e.getClass().getName() + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void onError(Throwable e, Invoker<?> invoker, Invocation invocation) {
        logger.error("Got unchecked and undeclared exception which called by " + RpcContext.getContext().getRemoteHost() + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName() + ", exception: " + e.getClass().getName() + ": " + e.getMessage(), e);
    }

    // For test purpose
    public void setLogger(Logger logger) {
        this.logger = logger;
    }
}

