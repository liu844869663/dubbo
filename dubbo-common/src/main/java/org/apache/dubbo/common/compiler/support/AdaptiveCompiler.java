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
package org.apache.dubbo.common.compiler.support;

import org.apache.dubbo.common.compiler.Compiler;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionLoader;

/**
 * Compiler 扩展点实现类的自适应对象，因为添加了 `@Adaptive` 注解
 *
 * AdaptiveCompiler. (SPI, Singleton, ThreadSafe)
 */
@Adaptive
public class AdaptiveCompiler implements Compiler {

    /**
     * 指定的编译器名称，可通过 `<dubbo:application compiler="javassist" />` 指定
     */
    private static volatile String DEFAULT_COMPILER;

    public static void setDefaultCompiler(String compiler) {
        DEFAULT_COMPILER = compiler;
    }

    @Override
    public Class<?> compile(String code, ClassLoader classLoader) {
        Compiler compiler;
        // 获取 Compiler 对应的 ExtensionLoader 对象
        ExtensionLoader<Compiler> loader = ExtensionLoader.getExtensionLoader(Compiler.class);
        String name = DEFAULT_COMPILER; // copy reference
        // 如果设置了编译器名称，则加载指定的 Compiler 编译器
        if (name != null && name.length() > 0) {
            compiler = loader.getExtension(name);
        } else {
            // 否则，使用默认的编译器，也就是基于 javassist 实现
            compiler = loader.getDefaultExtension();
        }
        // 使用编译器编译这个 Java 代码的字符串成 Class 对象
        return compiler.compile(code, classLoader);
    }

}
