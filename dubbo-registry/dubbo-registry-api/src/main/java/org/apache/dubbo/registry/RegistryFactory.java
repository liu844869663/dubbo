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
package org.apache.dubbo.registry;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * 注册中心工厂对象，可通过 Dubbo SPI 进行扩展，默认为 `dubbo`
 * <p>
 * RegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.support.AbstractRegistryFactory
 */
@SPI("dubbo")
public interface RegistryFactory {

    /**
     * 获取一个注册中心对象，同时会与注册中心建立连接
     * <p>
     * 连接注册中心需处理契约：<br>
     * 1. 当设置 check=false 时表示不检查连接，否则在连接不上会抛出异常<br>
     * 2. 支持 URL上 的 username:password 权限认证<br>
     * 3. 支持 backup=zk集群地址 备选注册中心集群地址<br>
     * 4. 支持 file=registry.cache 本地磁盘文件缓存<br>
     * 5. 支持 timeout=1000 连接超时设置<br>
     * 6. 支持 session=60000 会话过期设置<br>
     *
     * @param url 注册中心 URL 对象，不允许为空
     * @return 注册中心的引用对象，不会返回空
     */
    @Adaptive({"protocol"})
    Registry getRegistry(URL url);

}