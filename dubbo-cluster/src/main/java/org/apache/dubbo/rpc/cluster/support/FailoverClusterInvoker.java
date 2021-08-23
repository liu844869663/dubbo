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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_RETRIES;
import static org.apache.dubbo.common.constants.CommonConstants.RETRIES_KEY;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 *
 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailoverClusterInvoker.class);

    public FailoverClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        List<Invoker<T>> copyInvokers = invokers;
        // 如果 copyInvokers 为空则抛出异常，表示没有服务提供者
        checkInvokers(copyInvokers, invocation);
        String methodName = RpcUtils.getMethodName(invocation);
        // 计算出这个方法调用的最大次数，重试次数+1，默认也就是 2+1
        int len = calculateInvokeTimes(methodName);

        // retry loop.
        // 记录上一次 RPC 调用发生的异常
        RpcException le = null; // last exception.
        // 保存执行过的 Invoker 对象
        List<Invoker<T>> invoked = new ArrayList<Invoker<T>>(copyInvokers.size()); // invoked invokers.
        // 保存调用过的远程网络地址
        Set<String> providers = new HashSet<String>(len);
        // 尝试调用该方法，最多可调用 `len` 次，默认也就是 3 次，有一次成功则直接返回执行结果
        for (int i = 0; i < len; i++) {
            //Reselect before retry to avoid a change of candidate `invokers`.
            //NOTE: if `invokers` changed, then `invoked` also lose accuracy.
            if (i > 0) { // 不是第一次调用，表示前面的调用出现了异常
                // 检查是否已经被销毁
                checkWhetherDestroyed();
                // 重新从 Diretory 字典中获取该服务所有符合路由规则的 Invoker 对象
                copyInvokers = list(invocation);
                // check again
                // 重新检查 copyInvokers 是否为空
                checkInvokers(copyInvokers, invocation);
            }
            // 从候选的 Invoker 集合中根据负载均衡策略选择一个最终调用的 Invoker 对象
            Invoker<T> invoker = select(loadbalance, invocation, copyInvokers, invoked);
            // 将这个 Invoker 对象保存至 `invoked` 集合中，记录执行过
            invoked.add(invoker);
            // 往上下文中将这些服务提供者的 URL 对象保存起来
            RpcContext.getContext().setInvokers((List) invoked);
            try {
                /**
                 * PRC 远程调用，得到 Result 结果
                 *
                 * 参考 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker#doInvoke} 方法
                 */
                Result result = invoker.invoke(invocation);
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn("Although retry the method " + methodName
                            + " in the service " + getInterface().getName()
                            + " was successful by the provider " + invoker.getUrl().getAddress()
                            + ", but there have been failed providers " + providers
                            + " (" + providers.size() + "/" + copyInvokers.size()
                            + ") from the registry " + directory.getUrl().getAddress()
                            + " on the consumer " + NetUtils.getLocalHost()
                            + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                            + le.getMessage(), le);
                }
                // 返回 PRC 执行结果
                return result;
            } catch (RpcException e) {
                // 如果是业务性质的异常，不再重试，直接抛出
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                providers.add(invoker.getUrl().getAddress());
            }
        }
        // 执行了 `len` 次 RPC 调用，都发生了异常，则在这里抛出异常
        throw new RpcException(le.getCode(), "Failed to invoke the method "
                + methodName + " in the service " + getInterface().getName()
                + ". Tried " + len + " times of the providers " + providers
                + " (" + providers.size() + "/" + copyInvokers.size()
                + ") from the registry " + directory.getUrl().getAddress()
                + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                + Version.getVersion() + ". Last error is: "
                + le.getMessage(), le.getCause() != null ? le.getCause() : le);
    }

    private int calculateInvokeTimes(String methodName) {
        // 获取调用该方法的最大次数，重试次数+1，默认也就是 2+1
        int len = getUrl().getMethodParameter(methodName, RETRIES_KEY, DEFAULT_RETRIES) + 1;
        RpcContext rpcContext = RpcContext.getContext();
        Object retry = rpcContext.getObjectAttachment(RETRIES_KEY);
        if (null != retry && retry instanceof Number) {
            len = ((Number) retry).intValue() + 1;
            rpcContext.removeAttachment(RETRIES_KEY);
        }
        if (len <= 0) {
            len = 1;
        }

        return len;
    }

}
