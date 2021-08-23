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
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.beans.factory.annotation.ReferenceAnnotationBeanPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.annotation.ServiceAnnotationBeanPostProcessor;

import org.apache.dubbo.config.spring.context.DubboApplicationListenerRegistrar;
import org.apache.dubbo.config.spring.context.DubboBootstrapApplicationListener;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;

import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;
import static org.springframework.util.StringUtils.trimArrayElements;

/**
 * @link BeanDefinitionParser}
 * @see ServiceAnnotationBeanPostProcessor
 * @see ReferenceAnnotationBeanPostProcessor
 * @since 2.5.9
 */
public class AnnotationBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {

    /**
     * parse
     * <prev>
     * &lt;dubbo:annotation package="" /&gt;
     * </prev>
     *
     * @param element
     * @param parserContext
     * @param builder
     */
    @Override
    protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {

        // 获取 `<dubbo:annotation package="..."/>` 配置的需要扫描的包路径
        String packageToScan = element.getAttribute("package");

        String[] packagesToScan = trimArrayElements(commaDelimitedListToStringArray(packageToScan));

        // 将这个包路径作为下面 BeanDefinitionRegistryPostProcessor 的构造器入参
        builder.addConstructorArgValue(packagesToScan);

        // 设置为内部角色
        builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);

        /**
         * 这一步本来会注册几个公共的工具 Bean
         * 例如 {@link ReferenceAnnotationBeanPostProcessor} 用于处理 {@link DubboReference} 和 {@link Reference} 标注的字段，解析出 {@link ReferenceBean} 注入对应的 Bean
         * 例如 {@link DubboApplicationListenerRegistrar} 会注册 {@link DubboBootstrapApplicationListener} 监听器，
         * 在 Spring 应用上下文刷新后执行 Dubbo 启动器，进行初始化工作，会暴露服务提供者，向注册中心进行注册
         *
         * 2.7.8 移入 {@link DubboNamespaceHandler#parse(Element, ParserContext)} 方法中
         *
         * @since 2.7.6 Register the common beans
         * @since 2.7.8 comment this code line, and migrated to
         * @see DubboNamespaceHandler#parse(Element, ParserContext)
         * @see https://github.com/apache/dubbo/issues/6174
         */
        // registerCommonBeans(parserContext.getRegistry());
    }

    @Override
    protected boolean shouldGenerateIdAsFallback() {
        return true;
    }

    @Override
    protected Class<?> getBeanClass(Element element) {
        // 返回一个 BeanDefinitionRegistryPostProcessor 的 Class 对象
        //
        return ServiceAnnotationBeanPostProcessor.class;
    }

}
