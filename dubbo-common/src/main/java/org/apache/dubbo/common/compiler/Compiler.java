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
package org.apache.dubbo.common.compiler;

import org.apache.dubbo.common.compiler.support.JavassistCompiler;
import org.apache.dubbo.common.extension.SPI;

/**
 * 编译器接口，默认使用 javassist 实现的编译器
 * <p>
 * Compiler. (SPI, Singleton, ThreadSafe)
 */
@SPI(JavassistCompiler.NAME)
public interface Compiler {

    /**
     * 编译 Java 代码字符串
     * <p>
     * Compile java source code.
     *
     * @param code        Java source code
     * @param classLoader classloader
     * @return Compiled class 编译后的 Class 对象
     */
    Class<?> compile(String code, ClassLoader classLoader);

}
