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

import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.protocol.AbstractExporter;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.util.Map;

/**
 * InjvmExporter
 */
class InjvmExporter<T> extends AbstractExporter<T> {

    /**
     * Dubbo 服务的键
     */
    private final String key;

    /**
     * injvm 协议的 Exporter 集合
     *
     * @see AbstractProtocol#exporterMap
     */
    private final Map<String, Exporter<?>> exporterMap;

    InjvmExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
        // 将当前 Export 对象添加至 `exporterMap` 中
        exporterMap.put(key, this);
    }

    @Override
    public void afterUnExport() {
        // 从 `exporterMap` 移除对应的 Exporter 对象
        exporterMap.remove(key);
    }

}
