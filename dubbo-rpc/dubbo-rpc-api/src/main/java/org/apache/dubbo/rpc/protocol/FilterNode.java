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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * 用于封装 Invoker 的 Filter 节点，形成一条 Filter 链
 *
 * @see org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper
 */
class FilterNode<T> implements Invoker<T>{

    /**
     * 整条 Filter 链路所关联的
     */
    private final Invoker<T> invoker;
    /**
     * 这个 Filter 的下一个 Invoker 对象
     * 不是最后一个节点就还是一个 FilterNode 节点对象
     * 最后一个节点就是关联的 Invoker 对象
     */
    private final Invoker<T> next;
    /**
     * 当前 Filter 对象
     */
    private final Filter filter;
    
    public FilterNode(final Invoker<T> invoker, final Invoker<T> next, final Filter filter) {
        this.invoker = invoker;
        this.next = next;
        this.filter = filter;
    }

    @Override
    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    @Override
    public URL getUrl() {
        return invoker.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        Result asyncResult;
        try {
            // 获取执行结果，在这个 `filter` 中进行相关处理，最终会调用 `next` 的 invoke 方法
            // 例如 GenericImplFilter
            asyncResult = filter.invoke(next, invocation);
        } catch (Exception e) {
            if (filter instanceof ListenableFilter) { // 如果这个 Filter 是一个 ListenableFilter 对象
                ListenableFilter listenableFilter = ((ListenableFilter) filter);
                try {
                    // 获取这个请求对应的 Listener 监听器，对这个错误进行处理
                    Filter.Listener listener = listenableFilter.listener(invocation);
                    if (listener != null) {
                        listener.onError(e, invoker, invocation);
                    }
                } finally {
                    // 移除这个请求对应的 Listener 监听器
                    listenableFilter.removeListener(invocation);
                }
            } else if (filter instanceof Filter.Listener) { // 如果这个 Filter 还是一个 Listener 监听器
                // 例如 FutureFilter
                Filter.Listener listener = (Filter.Listener) filter;
                // 对这个错误进行处理
                listener.onError(e, invoker, invocation);
            }
            throw e;
        } finally {

        }
        // 执行结果的回调处理
        return asyncResult.whenCompleteWithContext((r, t) -> {
            if (filter instanceof ListenableFilter) {
                ListenableFilter listenableFilter = ((ListenableFilter) filter);
                Filter.Listener listener = listenableFilter.listener(invocation);
                try {
                    if (listener != null) {
                        if (t == null) {
                            listener.onResponse(r, invoker, invocation);
                        } else {
                            listener.onError(t, invoker, invocation);
                        }
                    }
                } finally {
                    listenableFilter.removeListener(invocation);
                }
            } else if (filter instanceof Filter.Listener) {  // 如果这个 Filter 还是一个 Listener 监听器
                // 例如 FutureFilter
                Filter.Listener listener = (Filter.Listener) filter;
                if (t == null) {
                    listener.onResponse(r, invoker, invocation);
                } else {
                    listener.onError(t, invoker, invocation);
                }
            }
        });
    }

    @Override
    public void destroy() {
        invoker.destroy();
    }

    @Override
    public String toString() {
        return invoker.toString();
    }

}
