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
package org.apache.dubbo.rpc.cluster.support.wrapper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.interceptor.ClusterInterceptor;
import org.apache.dubbo.rpc.cluster.interceptor.ConsumerContextClusterInterceptor;
import org.apache.dubbo.rpc.cluster.interceptor.ZoneAwareClusterInterceptor;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.FailoverClusterInvoker;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_INTERCEPTOR_KEY;

public abstract class AbstractCluster implements Cluster {

    private <T> Invoker<T> buildClusterInterceptors(AbstractClusterInvoker<T> clusterInvoker, String key) {
        AbstractClusterInvoker<T> last = clusterInvoker;
        List<ClusterInterceptor> interceptors = ExtensionLoader.getExtensionLoader(ClusterInterceptor.class).getActivateExtension(clusterInvoker.getUrl(), key);

        if (!interceptors.isEmpty()) {
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                final ClusterInterceptor interceptor = interceptors.get(i);
                final AbstractClusterInvoker<T> next = last;
                last = new InterceptorInvokerNode<>(clusterInvoker, interceptor, next);
            }
        }
        return last;
    }

    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        /**
         * 1. 创建一个 Invoker 对象，例如 {@link FailoverClusterInvoker}，都会封装 `directory` 对象
         * 2. 构建一条 Invoker 链，在其前面添加一系列 ClusterInterceptor 拦截器
         *    默认会有 {@link ConsumerContextClusterInterceptor} 拦截器
         *    `cluster=zone-aware` 会有 {@link ZoneAwareClusterInterceptor} 拦截器
         */
        return buildClusterInterceptors(doJoin(directory), directory.getUrl().getParameter(REFERENCE_INTERCEPTOR_KEY));
    }

    protected abstract <T> AbstractClusterInvoker<T> doJoin(Directory<T> directory) throws RpcException;

    protected class InterceptorInvokerNode<T> extends AbstractClusterInvoker<T> {

        private AbstractClusterInvoker<T> clusterInvoker;
        private ClusterInterceptor interceptor;
        private AbstractClusterInvoker<T> next;

        public InterceptorInvokerNode(AbstractClusterInvoker<T> clusterInvoker,
                                      ClusterInterceptor interceptor,
                                      AbstractClusterInvoker<T> next) {
            this.clusterInvoker = clusterInvoker;
            this.interceptor = interceptor;
            this.next = next;
        }

        @Override
        public Class<T> getInterface() {
            return clusterInvoker.getInterface();
        }

        @Override
        public URL getUrl() {
            return clusterInvoker.getUrl();
        }

        @Override
        public boolean isAvailable() {
            return clusterInvoker.isAvailable();
        }

        @Override
        public Result invoke(Invocation invocation) throws RpcException {
            Result asyncResult;
            try {
                interceptor.before(next, invocation);
                asyncResult = interceptor.intercept(next, invocation);
            } catch (Exception e) {
                // onError callback
                if (interceptor instanceof ClusterInterceptor.Listener) {
                    ClusterInterceptor.Listener listener = (ClusterInterceptor.Listener) interceptor;
                    listener.onError(e, clusterInvoker, invocation);
                }
                throw e;
            } finally {
                interceptor.after(next, invocation);
            }
            return asyncResult.whenCompleteWithContext((r, t) -> {
                // onResponse callback
                if (interceptor instanceof ClusterInterceptor.Listener) {
                    ClusterInterceptor.Listener listener = (ClusterInterceptor.Listener) interceptor;
                    if (t == null) {
                        listener.onMessage(r, clusterInvoker, invocation);
                    } else {
                        listener.onError(t, clusterInvoker, invocation);
                    }
                }
            });
        }

        @Override
        public void destroy() {
            clusterInvoker.destroy();
        }

        @Override
        public String toString() {
            return clusterInvoker.toString();
        }

        @Override
        protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
            // The only purpose is to build an interceptor chain, so the cluster related logic doesn't matter.
            return null;
        }
    }
}
