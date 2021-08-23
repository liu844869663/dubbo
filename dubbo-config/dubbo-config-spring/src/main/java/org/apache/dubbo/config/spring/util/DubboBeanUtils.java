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
package org.apache.dubbo.config.spring.util;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.config.spring.beans.factory.annotation.DubboConfigAliasPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.annotation.ReferenceAnnotationBeanPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.config.DubboConfigDefaultPropertyValueBeanPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.config.DubboConfigEarlyInitializationPostProcessor;
import org.apache.dubbo.config.spring.context.DubboApplicationListenerRegistrar;
import org.apache.dubbo.config.spring.context.DubboBootstrapApplicationListener;
import org.apache.dubbo.config.spring.context.DubboLifecycleComponentApplicationListener;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.alibaba.spring.util.BeanRegistrar.registerInfrastructureBean;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static org.springframework.util.ObjectUtils.isEmpty;

/**
 * Dubbo Bean utilities class
 *
 * @since 2.7.6
 */
public abstract class DubboBeanUtils {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanUtils.class);

    /**
     * Register the common beans
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @see ReferenceAnnotationBeanPostProcessor
     * @see DubboConfigDefaultPropertyValueBeanPostProcessor
     * @see DubboConfigAliasPostProcessor
     * @see DubboLifecycleComponentApplicationListener
     * @see DubboBootstrapApplicationListener
     */
    public static void registerCommonBeans(BeanDefinitionRegistry registry) {

        // Since 2.5.7 Register @Reference Annotation Bean Processor as an infrastructure Bean
        // 注册一个 ReferenceAnnotationBeanPostProcessor 对象（内部角色），InstantiationAwareBeanPostProcessor 的实现类
        // 用于处理 `@DubboReference` `@Reference` 标注的字段，注入对应的 Bean
        // 过程大致就是先根据注解等 Dubbo 配置生成一个 ReferenceBean，然后调用其 get() 方法创建一个动态代理对象，然后进行注入
        registerInfrastructureBean(registry, ReferenceAnnotationBeanPostProcessor.BEAN_NAME,
                ReferenceAnnotationBeanPostProcessor.class);

        // Since 2.7.4 [Feature] https://github.com/apache/dubbo/issues/5093
        // 注册一个 DubboConfigAliasPostProcessor 对象（内部角色），BeanPostProcessor 的实现类
        // 用于处理 Dubbo 配置类（AbstractConfig）的 id，有的话将其作为这个 Bean 的别名，这样就可通过 id 获取对应的配置类了
        registerInfrastructureBean(registry, DubboConfigAliasPostProcessor.BEAN_NAME,
                DubboConfigAliasPostProcessor.class);

        // Since 2.7.9 Register DubboApplicationListenerRegister as an infrastructure Bean
        // https://github.com/apache/dubbo/issues/6559

        // Since 2.7.5 Register DubboLifecycleComponentApplicationListener as an infrastructure Bean
        // registerInfrastructureBean(registry, DubboLifecycleComponentApplicationListener.BEAN_NAME,
        //        DubboLifecycleComponentApplicationListener.class);

        // Since 2.7.4 Register DubboBootstrapApplicationListener as an infrastructure Bean
        // registerInfrastructureBean(registry, DubboBootstrapApplicationListener.BEAN_NAME,
        //        DubboBootstrapApplication istrar 对象（内部角色），ApplicationContextAware 的实现类
        // 目的就是注册 DubboBootstrapApplicationListener 和 DubboLifecycleComponentApplicationListener 两个监听器
        // 都是监听 Spring 应用上下文的刷新和关闭事件，前者用于启动和关闭 DubboBootstrap 启动器，后者用于启动和关闭 Dubbo 的 Lifecycle 生命周期组件
        registerInfrastructureBean(registry, DubboApplicationListenerRegistrar.BEAN_NAME,
                DubboApplicationListenerRegistrar.class);

        // Since 2.7.6 Register DubboConfigDefaultPropertyValueBeanPostProcessor as an infrastructure Bean
        // 注册一个 DubboConfigDefaultPropertyValueBeanPostProcessor 对象（内部角色），BeanPostProcessor 的实现类
        // 目的就是默认设置 Bean 的 `id` 和 `name` 为 Bean 的名称，如果是协议配置类的话，`name` 设置为 `dubbo`，默认为 Dubbo 协议
        registerInfrastructureBean(registry, DubboConfigDefaultPropertyValueBeanPostProcessor.BEAN_NAME,
                DubboConfigDefaultPropertyValueBeanPostProcessor.class);

        // Since 2.7.9 Register DubboConfigEarlyInitializationPostProcessor as an infrastructure Bean
        // 注册一个 DubboConfigEarlyInitializationPostProcessor 对象（内部角色），BeanDefinitionRegistryPostProcessor 的实现类
        // 目的就是防止 Dubbo 的相关配置类过早的初始化，导致没有添加到 ConfigManager 管理器中
        registerInfrastructureBean(registry, DubboConfigEarlyInitializationPostProcessor.BEAN_NAME,
                DubboConfigEarlyInitializationPostProcessor.class);
    }

    /**
     * Get optional bean by name and type if beanName is not null, or else find by type
     *
     * @param beanFactory
     * @param beanName
     * @param beanType
     * @param <T>
     * @return
     */
    public static <T> T getOptionalBean(ListableBeanFactory beanFactory, String beanName, Class<T> beanType) throws BeansException {
        if (beanName == null) {
            return getOptionalBeanByType(beanFactory, beanType);
        }

        T bean = null;
        try {
            bean = beanFactory.getBean(beanName, beanType);
        } catch (NoSuchBeanDefinitionException e) {
            // ignore NoSuchBeanDefinitionException
        } catch (BeanNotOfRequiredTypeException e) {
            // ignore BeanNotOfRequiredTypeException
            logger.warn(String.format("bean type not match, name: %s, expected type: %s, actual type: %s",
                    beanName, beanType.getName(), e.getActualType().getName()));
        }
        return bean;
    }

    private static <T> T getOptionalBeanByType(ListableBeanFactory beanFactory, Class<T> beanType) {
        // Issue : https://github.com/alibaba/spring-context-support/issues/20
        String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, beanType, true, false);
        if (beanNames == null || beanNames.length == 0) {
            return null;
        } else if (beanNames.length > 1){
            throw new NoUniqueBeanDefinitionException(beanType, Arrays.asList(beanNames));
        }
        return (T) beanFactory.getBean(beanNames[0]);
    }

    public static <T> T getBean(ListableBeanFactory beanFactory, String beanName, Class<T> beanType) throws BeansException {
        return beanFactory.getBean(beanName, beanType);
    }

    /**
     * Get beans by names and type
     *
     * @param beanFactory
     * @param beanNames
     * @param beanType
     * @param <T>
     * @return
     */
    public static <T> List<T> getBeans(ListableBeanFactory beanFactory, String[] beanNames, Class<T> beanType) throws BeansException {
        if (isEmpty(beanNames)) {
            return emptyList();
        }
        List<T> beans = new ArrayList<T>(beanNames.length);
        for (String beanName : beanNames) {
            T bean = getBean(beanFactory, beanName, beanType);
            if (bean != null) {
                beans.add(bean);
            }
        }
        return unmodifiableList(beans);
    }
}
