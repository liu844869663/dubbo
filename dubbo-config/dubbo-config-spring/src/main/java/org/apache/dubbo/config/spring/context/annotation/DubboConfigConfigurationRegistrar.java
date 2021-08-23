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
package org.apache.dubbo.config.spring.context.annotation;

import org.apache.dubbo.config.AbstractConfig;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import static com.alibaba.spring.util.AnnotatedBeanDefinitionRegistryUtils.registerBeans;
import static org.apache.dubbo.config.spring.util.DubboBeanUtils.registerCommonBeans;

/**
 * Dubbo {@link AbstractConfig Config} {@link ImportBeanDefinitionRegistrar register}, which order can be configured
 *
 * @see EnableDubboConfig
 * @see DubboConfigConfiguration
 * @see Ordered
 * @since 2.5.8
 */
public class DubboConfigConfigurationRegistrar implements ImportBeanDefinitionRegistrar, ApplicationContextAware {

    private ConfigurableApplicationContext applicationContext;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {

        // 获取 `@EnableDubboConfig` 注解的信息
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfig.class.getName()));

        // 获取注解的 multiple 属性，默认为 true
        boolean multiple = attributes.getBoolean("multiple");

        // Single Config Bindings
        // 先注册一个 DubboConfigConfiguration.Single 对象
        registerBeans(registry, DubboConfigConfiguration.Single.class);

        if (multiple) { // Since 2.6.6 https://github.com/apache/dubbo/issues/3193
            // 如果开启 multiple，则再注册一个 DubboConfigConfiguration.Multiple 对象
            registerBeans(registry, DubboConfigConfiguration.Multiple.class);
        }
        // 上面的 Single 和 Multiple 都是借助于 `@EnableConfigurationBeanBinding` 注解将 Spring 中 dubbo 开头的配置解析成对应的 Dubbo 配置类（AbstractConfig）
        // 例如 dubbo.application 开头的配置设置到一个 ApplicationConfig 配置类中
        // dubbo.registry 开头的配置会设置到一个 RegistryConfig 配置类中
        // 目的就是将这些 Dubbo 相关的配置解析成对应的 Dubbo 配置类
        // Multiple 相比 Single 的区别在于它支持配置多个，例如 `dubbo.registries.a.address=注册中心A` 和 `dubbo.registries.b.address=注册中心B` 配置了两个注册中心
        // 那么在使用 `@DubboService` 注解的时候，可以通过其 `registry=a` 来指定使用注册中心A

        // Since 2.7.6
        // 注册几个公共的工具 Bean
        // 例如 ReferenceAnnotationBeanPostProcessor，用于处理 `@DubboReference` `@Reference` 标注的字段，解析出 ReferenceBean 注入对应的 Bean
        registerCommonBeans(registry);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            throw new IllegalArgumentException("The argument of ApplicationContext must be ConfigurableApplicationContext");
        }
        this.applicationContext = (ConfigurableApplicationContext) applicationContext;
    }
}
