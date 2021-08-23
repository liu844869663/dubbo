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
package org.apache.dubbo.rpc.model;

import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.context.FrameworkExt;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.context.ConfigManager;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ExtensionLoader}, {@code DubboBootstrap} and this class are at present designed to be
 * singleton or static (by itself totally static or uses some static fields). So the instances
 * returned from them are of process scope. If you want to support multiple dubbo servers in one
 * single process, you may need to refactor those three classes.
 *
 * Represent a application which is using Dubbo and store basic metadata info for using
 * during the processing of RPC invoking.
 * <p>
 * ApplicationModel includes many ProviderModel which is about published services
 * and many Consumer Model which is about subscribed services.
 * <p>
 *
 */
public class ApplicationModel {
    protected static final Logger LOGGER = LoggerFactory.getLogger(ApplicationModel.class);
    public static final String NAME = "application";

    private static AtomicBoolean INIT_FLAG = new AtomicBoolean(false);

    public static void init() {
        if (INIT_FLAG.compareAndSet(false, true)) {
            ExtensionLoader<ApplicationInitListener> extensionLoader = ExtensionLoader.getExtensionLoader(ApplicationInitListener.class);
            Set<String> listenerNames = extensionLoader.getSupportedExtensions();
            for (String listenerName : listenerNames) {
                extensionLoader.getExtension(listenerName).init();
            }
        }
    }

    public static Collection<ConsumerModel> allConsumerModels() {
        return getServiceRepository().getReferredServices();
    }

    public static Collection<ProviderModel> allProviderModels() {
        return getServiceRepository().getExportedServices();
    }

    public static ProviderModel getProviderModel(String serviceKey) {
        return getServiceRepository().lookupExportedService(serviceKey);
    }

    public static ConsumerModel getConsumerModel(String serviceKey) {
        return getServiceRepository().lookupReferredService(serviceKey);
    }

    /**
     * FrameworkExt 扩展点实现类的加载器
     */
    private static final ExtensionLoader<FrameworkExt> LOADER = ExtensionLoader.getExtensionLoader(FrameworkExt.class);

    public static void initFrameworkExts() {
        /**
         * Dubbo SPI 获取所有的 FrameworkExt 框架扩展类
         *
         * @see ConfigManager 配置管理器
         * @see Environment 环境信息
         * @see ServiceRepository 服务仓储
         */
        Set<FrameworkExt> exts = ExtensionLoader.getExtensionLoader(FrameworkExt.class).getSupportedExtensionInstances();
        for (FrameworkExt ext : exts) {
            // 初始化 Environment，读取配置中心的配置
            ext.initialize();
        }
    }

    public static Environment getEnvironment() {
        // 获取 `environment` 名称的 FrameworkExt 扩展点实现类，也就是 Environment 对象
        return (Environment) LOADER.getExtension(Environment.NAME);
    }

    public static ConfigManager getConfigManager() {
        // 获取 `config` 名称的 FrameworkExt 扩展点实现类，也就是 ConfigManager 对象
        return (ConfigManager) LOADER.getExtension(ConfigManager.NAME);
    }

    public static ServiceRepository getServiceRepository() {
        // 获取 `repository` 名称的 FrameworkExt 扩展点实现类，也就是 ServiceRepository 对象
        return (ServiceRepository) LOADER.getExtension(ServiceRepository.NAME);
    }

    public static ApplicationConfig getApplicationConfig() {
        return getConfigManager().getApplicationOrElseThrow();
    }

    public static String getName() {
        return getApplicationConfig().getName();
    }

    @Deprecated
    private static String application;

    @Deprecated
    public static String getApplication() {
        return application == null ? getName() : application;
    }

    // Currently used by UT.
    @Deprecated
    public static void setApplication(String application) {
        ApplicationModel.application = application;
    }

    // only for unit test
    @Deprecated
    public static void reset() {
        getServiceRepository().destroy();
        getConfigManager().destroy();
        getEnvironment().destroy();
    }

}
