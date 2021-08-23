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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory;
import org.apache.dubbo.common.extension.factory.SpiExtensionFactory;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.Collections.sort;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * Dubbo SPI 的实现主要查看以下几个方法：
 *  1. {@link #getExtensionLoader(Class)} 方法，获取指定扩展接口的 ExtensionLoader 对象
 *  2. {@link #getAdaptiveExtension()} 方法，获取扩展点实现类的自适应对象，在其重写的方法中再根据 URL 加载出对应的扩展点实现类，进行处理
 *  3. {@link #getExtension(String name)} 方法，获取指定名称的扩展点实现类，默认支持 Wrapper 包装类对其进行包装处理，说明参考 {@link this#cachedWrapperClasses} 注释说明
 *  4. {@link #getActivateExtension(URL url, String key, String group)} 方法，获得符合自动激活条件的拓展点实现类数组
 *  --- 加载扩展点实现类的过程 ---
 *  1> {@link #getExtensionClasses()} 方法，获取当前扩展接口的所有扩展点实现类信息，从缓存中获取
 *  2> {@link #loadExtensionClasses()} 方法，加载出当前扩展接口的所有扩展点实现类信息，缓存起来
 *  3> {@link this#loadDirectory} 方法，加载扩展接口名称的文件，解析出扩展点实现类并缓存
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    /**
     * 逗号分隔符的正则表达式
     */
    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /**
     * 缓存每个扩展接口对应的 ExtensionLoader 对象
     * key：扩展接口
     * value：扩展类对应的 ExtensionLoader 对象
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>(64);

    /**
     * 缓存扩展点实现类
     * key：扩展点实现类的 Class 对象
     * value：扩展点实现类实例对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>(64);

    /**
     * 需要扩展的接口
     */
    private final Class<?> type;

    /**
     * {@link AdaptiveExtensionFactory} 扩展类工厂对象
     * 用于往扩展点实现类的实例对象中注入相关属性
     * {@link SpiExtensionFactory} 支持注入其他 Dubbo 扩展点实现类
     * {@link org.apache.dubbo.config.spring.extension.SpringExtensionFactory} 支持注入 Spring 应用上下文中的其他 Bean
     *
     */
    private final ExtensionFactory objectFactory;

    /**
     * 缓存的扩展点实现类信息
     * key：扩展点实现类 Class 对象
     * value：扩展点实现类的名称
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    /**
     * 缓存当前扩展接口的所有实现类信息，持有的 Map：
     * key：扩展点实现类的名称
     * value：扩展点实现类
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    /**
     * 缓存的 {@link Activate} 注解信息，该注解可以标注这个扩展点实现类能够应用的场景
     * 例如，可以指定 group=provider，表示只在暴露服务提供者时使用，可以参考 Filter 的实现类
     *
     * key：扩展点实现类的名称
     * value：扩展点实现类的 {@link Activate} 注解信息
     */
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();
    /**
     * 缓存扩展点实现类的实例对象，用 Holder 封装
     * key：扩展点实现类的名称
     * value：扩展点实现类的实例对象，Holder 封装
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    /**
     * 缓存扩展点实现类的自适应对象
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    /**
     * 缓存扩展点实现类的自适应对象的 Class 对象
     * 1. 在扩展点实现类上可通过标注 `@Adaptive` 注解将其作为自适应扩展对象
     * 2. 没有的话，则尝试加载 `扩展接口$Adaptive` 这个类
     * 3. 还没有的话，则尝试通过 Javassist（默认）动态生成一个实现类，在其重写方法的时候会再通过 Dubbo SPI 加载根据参数加载出对应实现类，然后进行处理
     *
     * 所以在 Dubbo 内部通过 ExtensionLoader 获取扩展接口的实现类时，是获取其对应的自适应对象，在自适应对象重写的方法中再根据 URL 加载出对应的扩展点实现类
     * 例如 Protocol 对应 Protocol$Adaptive
     */
    private volatile Class<?> cachedAdaptiveClass = null;
    /**
     * 缓存扩展点实现类的默认名称
     */
    private String cachedDefaultName;
    /**
     * 缓存创建缓存扩展点实现类的自适应对象过程中抛出的异常
     */
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * 缓存扩展点实现类的 Wrapper 包装类，用于包装扩展点实现类的实例对象
     *
     * 例如 {@link org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper} 可以对 {@link org.apache.dubbo.rpc.Protocol} 的实现类进行包装
     * 在 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol} 上面插入一条 {@link org.apache.dubbo.rpc.Filter} 过滤器链路
     */
    private Set<Class<?>> cachedWrapperClasses;

    /**
     * 缓存加载扩展点实现类过程出现的异常
     * key：准备加载的扩展点实现类
     * value：异常信息
     */
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    /**
     * Record all unacceptable exceptions when using SPI
     *
     * 记录出现重复的扩展点实现类的名称，也就是出现冲突了
     * 例如 {@link org.apache.dubbo.rpc.Protocol} 你指定两个 dubbo 扩展点实现类，那么这里将 dubbo 保存起来
     */
    private Set<String> unacceptableExceptions = new ConcurrentHashSet<>();

    /**
     * 通过 Java SPI 机制加载出 Dubbo SPI 机制的加载策略，默认有三种：
     *  1. META-INF/dubbo/internal/
     *  2. META-INF/dubbo/
     *  3. META-INF/services/
     * 依次从上面三种路径下加载扩展的接口
     */
    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * 通过 Java SPI 加载出 {@link LoadingStrategy} 的实现类，Dubbo 默认配置了三种实现类：
     * @see DubboInternalLoadingStrategy
     * @see DubboLoadingStrategy
     * @see ServicesLoadingStrategy
     *
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        return stream(load(LoadingStrategy.class).spliterator(), false)
                .sorted()
                .toArray(LoadingStrategy[]::new);
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    private ExtensionLoader(Class<?> type) {
        // 设置扩展接口
        this.type = type;
        /**
         * 设置扩展类工厂对象，如果本身就是扩展工厂接口，则为空
         * 否则，通过 Dubbo SPI 获取扩展工厂接口的自适应扩展对象，Dubbo 配置的是 {@link AdaptiveExtensionFactory} 对象
         * 里面包含了 {@link SpiExtensionFactory} 和 {@link org.apache.dubbo.config.spring.extension.SpringExtensionFactory} 两个扩展类工厂
         */
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        // 只能扩展接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        // 需要扩展的接口必须添加 @SPI 注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 尝试从缓存中获取该扩展接口对应的 ExtensionLoader 对象
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        // 缓存未命中则创建一个对应的 ExtensionLoader 对象放入缓存中
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    @Deprecated
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    // only for unit test
    @Deprecated
    public static void destroyAll() {
        EXTENSION_INSTANCES.forEach((_type, instance) -> {
            if (instance instanceof Lifecycle) {
                Lifecycle lifecycle = (Lifecycle) instance;
                try {
                    lifecycle.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + lifecycle, e);
                }
            }
        });
    }

    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * 获得符合自动激活条件的拓展点实现类数组
     *
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        /**
         * 从 url 中获取指定 key 的参数值
         * 例如我们可以配置 filter=a,b 指定需要 Filter 们
         * 这样在 {@link org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper} 中就可以在 Protocol 实现类上面形成一条指定的 Filter 链
         */
        String value = url.getParameter(key);
        // 获取需要激活的扩展点实现类们
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        // 保存需要激活的扩展点实现类
        List<T> activateExtensions = new ArrayList<>();
        // solve the bug of using @SPI's wrapper method to report a null pointer exception.
        TreeMap<Class, T> activateExtensionsMap = new TreeMap<>(ActivateComparator.COMPARATOR);
        Set<String> loadedNames = new HashSet<>();
        Set<String> names = CollectionUtils.ofSet(values);
        // 如果名称中没有 `-default`，则表示需要加载默认的
        // 例如设置了 `filter=-default`，表示移除所有默认的过滤器
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            // 加载的所有扩展点实现类并缓存起来，已加载则不做处理
            getExtensionClasses();

            // 遍历这个扩展接口的所有扩展点实现类的 @Activate 注解信息
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                // @Activate 注解所标注的扩展点实现类名称
                String name = entry.getKey();
                // @Activate 注解信息
                Object activate = entry.getValue();

                // 或者这个注解的 group 和 value 属性
                String[] activateGroup, activateValue;

                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                if (isMatchGroup(group, activateGroup) // group 是否匹配
                        && !names.contains(name) // 没有指定这个名称
                        && !names.contains(REMOVE_VALUE_PREFIX + name) // 没有指定移除这个名称（添加 - 表示移除）
                        && isActive(activateValue, url) // 有效状态
                        && !loadedNames.contains(name)) // 没有加载
                {
                    // 获取指定名称的扩展点实现类的 Class 对象和实例对象
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                    // 将这个名称标记为已加载
                    loadedNames.add(name);
                }
            }
            if (!activateExtensionsMap.isEmpty()) {
                activateExtensions.addAll(activateExtensionsMap.values());
            }
        }
        List<T> loadedExtensions = new ArrayList<>();
        // 如果指定了使用哪些名称的扩展点实现类，则遍历依次加载
        // 例如你指定了 `filter=demo`，表示需要将 demo 对应的 Filter 扩展点实现类加入进来
        for (String name : names) {
            // 如果不是排除这个名称
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                // 如果该名称还没有加载
                if (!loadedNames.contains(name)) {
                    // 如果是 default
                    if (DEFAULT_KEY.equals(name)) {
                        if (!loadedExtensions.isEmpty()) {
                            activateExtensions.addAll(0, loadedExtensions);
                            loadedExtensions.clear();
                        }
                    // 否则，加载这个名称的扩展点实现类的实例对象
                    } else {
                        loadedExtensions.add(getExtension(name));
                    }
                    loadedNames.add(name);
                } else {
                    // If getExtension(name) exists, getExtensionClass(name) must exist, so there is no null pointer processing here.
                    String simpleName = getExtensionClass(name).getSimpleName();
                    logger.warn("Catch duplicated filter, ExtensionLoader will ignore one of them. Please check. Filter Name: " + name +
                            ". Ignored Class Name: " + simpleName);
                }
            }
        }
        // 将指定名称的扩展点实现类全部放入需要激活的集合中
        if (!loadedExtensions.isEmpty()) {
            activateExtensions.addAll(loadedExtensions);
        }
        return activateExtensions;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            // @Active(value="key1:value1, key2:value2")
            String keyValue = null;
            if (key.contains(":")) {
                String[] arr = key.split(":");
                key = arr[0];
                keyValue = arr[1];
            }

            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ((keyValue != null && keyValue.equals(v)) || (keyValue == null && ConfigUtils.isNotEmpty(v)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public List<T> getLoadedExtensionInstances() {
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        // 获取指定名称的扩展点实现类
        // 支持 Wrapper 包装类对其进行包装处理
        return getExtension(name, true);
    }

    public T getExtension(String name, boolean wrap) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        // 如果名称为 true，则使用默认的扩展点实现类
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        // 从缓存中获取该名称对应的 Holder 对象（没有则创建一个）
        final Holder<Object> holder = getOrCreateHolder(name);
        // 尝试获取这个 Holder 封装的扩展点实现类
        Object instance = holder.get();
        if (instance == null) {
            // 如果没有获取到，则加锁再次获取，保证线程安全
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 还是没有对应的扩展点实现类的实例对象，则创建一个
                    instance = createExtension(name, wrap);
                    // 将实例对象设置到这个 Holder 对象中
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * get the original type.
     * @param name
     * @return
     */
    public T getOriginalInstance(String name) {
        getExtension(name);
        Class<?> clazz = getExtensionClasses().get(name);
        return (T) EXTENSION_INSTANCES.get(clazz);
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        // 加载的所有扩展点实现类并缓存起来，已加载则不做处理
        getExtensionClasses();
        // 没有缓存扩展点实现类的默认名称，则返回空对象
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        // 获取默认名称的扩展点实现类
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        sort(instances, Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 尝试从缓存中扩展点实现类的自适应对象
        Object instance = cachedAdaptiveInstance.get();
        // 缓存未命中
        if (instance == null) {
            // 如果之前创建的过程中抛出了异常，那么这里直接抛出异常，无需继续往下执行
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                        createAdaptiveInstanceError.toString(),
                        createAdaptiveInstanceError);
            }

            // 再次尝试从缓存中获取，加锁，保证线程安全
            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                // 缓存再次未命中
                if (instance == null) {
                    try {
                        // 创建一个扩展点实现类的自适应对象，会先加载的所有扩展点实现类并缓存起来，已加载则不做处理
                        instance = createAdaptiveExtension();
                        // 将这个对象缓存起来
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        // 过程中抛出的异常也缓存起来
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);

        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(name.toLowerCase())) {
                if (i == 1) {
                    buf.append(", possible causes: ");
                }
                buf.append("\r\n(");
                buf.append(i++);
                buf.append(") ");
                buf.append(entry.getKey());
                buf.append(":\r\n");
                buf.append(StringUtils.toString(entry.getValue()));
            }
        }

        if (i == 1) {
            buf.append(", no related exception was found, please check whether related SPI module is missing.");
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        // 先加载的所有扩展点实现类并缓存起来，已加载则不做处理
        // 然后获取该名称对应的扩展点实现类
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null || unacceptableExceptions.contains(name)) {
            throw findException(name);
        }
        try {
            // 尝试从缓存中获取该扩展点实现类的实例对象
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            // 如果缓存未命中，则构建一个实例对象并放入缓存中
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.getDeclaredConstructor().newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 往这个实例对象中注入相关属性（如果有必要）
            injectExtension(instance);


            // 如果支持包装
            if (wrap) {

                // 从缓存中获取所有扩展点实现类的 Wrapper 包装类
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    // 将 Wrapper 包装类翻转，例如对于 Protocol 的包装类有 Filter 和 Listener 两种
                    // 得到的顺序就是 Listener Filter，那么通过下面进行包装后，Filter 在最外层
                    // 你可以理解为剥洋葱，执行某个服务的方法时，先经过一条 Filter 链，然后执行方法
                    Collections.reverse(wrapperClassesList);
                }

                // 如果该扩展接口有对应的 Wrapper 包装类，则遍历依次对这个实例对象进行包装
                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        // 获取这个 Wrapper 包装类的 `@Wrapper` 注解（该注解可以指定包装哪些名称的实现类）
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        // 没有该注解，或者需要包装
                        if (wrapper == null
                                || (ArrayUtils.contains(wrapper.matches(), name) && !ArrayUtils.contains(wrapper.mismatches(), name))) {
                            // 则将这个实例对象包装成这个 Wrapper 包装类
                            instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                        }
                    }
                }
            }

            // 如果扩展点实现类还实现了 Lifecycle 接口，则调用其 initialize() 方法做一些初始化工作
            initExtension(instance);
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    private T injectExtension(T instance) {

        if (objectFactory == null) {
            return instance;
        }

        try {
            for (Method method : instance.getClass().getMethods()) {
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 */
                if (method.getAnnotation(DisableInject.class) != null) {
                    continue;
                }
                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    String property = getSetterProperty(method);
                    Object object = objectFactory.getExtension(pt, property);
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName()
                            + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    private Map<String, Class<?>> getExtensionClasses() {
        // 尝试从缓存中获取当前扩展接口的所有实现类
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            // 缓存未命中，则加载再次尝试从缓存中获取，确保线程安全
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    // 缓存再次未命中，则尝试加载出所有的扩展点实现类
                    // 类似 Java SPI 机制去加载 META-INF/dubbo/internal/、META-INF/dubbo/、META-INF/service/
                    // 路径下扩展接口名为的文件，然后进行解析
                    classes = loadExtensionClasses();
                    // 将加载出来的所有扩展点实现类缓存起来
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        // 缓存通过 `@SPI` 注解指定的扩展点实现类的默认名称，例如 `@SPI("dubbo")` 的默认协议就是 dubbo
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        /*
         * 根据不同的策略加载扩展接口的实现类，也就是依次从下面三个路径：
         *  1. META-INF/dubbo/internal/
         *  2. META-INF/dubbo/
         *  3. META-INF/service/
         * 找到所有扩展接口名称的文件进行解析
         */
        for (LoadingStrategy strategy : strategies) {
            // 根据加载策略加载扩展点实现类，也就是从某个路径下找到扩展点接口名称的文件，然后解析里面的内容，将里面定义的扩展点实现类进行加载并缓存起来
            loadDirectory(extensionClasses, strategy.directory(), type.getName(), strategy.preferExtensionClassLoader(),
                    strategy.overridden(), strategy.excludedPackages());
            // 兼容老版本，将扩展接口的名称前缀缓存老版本的 `com.alibaba`
            loadDirectory(extensionClasses, strategy.directory(), type.getName().replace("org.apache", "com.alibaba"),
                    strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
        }

        // 返回扩展接口的所有扩展点实现类，key 为名称，value 为扩展点实现类
        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        loadDirectory(extensionClasses, dir, type, false, false);
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type,
                               boolean extensionLoaderClassLoaderFirst, boolean overridden, String... excludedPackages) {
        // 需要加载的文件名，例如：
        // META-INF/dubbo/internal/扩展的接口名称、META-INF/dubbo/扩展的接口名称、META-INF/service/扩展的接口名称
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls = null;
            // 获取到当前线程的类加载器
            ClassLoader classLoader = findClassLoader();

            // try to load from ExtensionLoader's ClassLoader first
            // 尝试使用加载 ExtensionLoader 的类加载器找到所有的 `fileName` 文件路径，加载策略默认为 `false`
            if (extensionLoaderClassLoaderFirst) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    urls = extensionLoaderClassLoader.getResources(fileName);
                }
            }

            // 如果上一步没有加载，那么这里使用当前线程的类加载器到所有的 `fileName` 文件路径
            if (urls == null || !urls.hasMoreElements()) {
                if (classLoader != null) {
                    urls = classLoader.getResources(fileName);
                } else {
                    urls = ClassLoader.getSystemResources(fileName);
                }
            }

            // 如果存在 `fileName` 文件
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    // 对该文件进行加载，将扩展点实现类的相关信息缓存起来
                    loadResource(extensionClasses, classLoader, resourceURL, overridden, excludedPackages);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader,
                              java.net.URL resourceURL, boolean overridden, String... excludedPackages) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                String clazz = null;
                // 读取每一行数据
                while ((line = reader.readLine()) != null) {
                    // 将 # 注释去除
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    // 如果这行有数据
                    if (line.length() > 0) {
                        try {
                            // 解析出扩展类的名称和具体扩展类
                            // 例如 dubbo=org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                clazz = line.substring(i + 1).trim();
                            } else {
                                clazz = line;
                            }
                            if (StringUtils.isNotEmpty(clazz) && !isExcluded(clazz, excludedPackages)) {
                                // 加载这个扩展点实现类，会将其相关信息缓存
                                loadClass(extensionClasses, resourceURL, Class.forName(clazz, true, classLoader), name, overridden);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException(
                                    "Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL +
                                            ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name,
                           boolean overridden) throws NoSuchMethodException {
        // 如果加载到的扩展类不是扩展接口的实现类，则抛出异常
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        // 如果扩展类有 `@Adaptive` 注解
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz, overridden);
        // 否则，如果扩展类是 Wrapper 包装类，持有真正的扩展点实现类
        } else if (isWrapperClass(clazz)) {
            // 将这个包装里放入 `cachedWrapperClasses` 集合中
            cacheWrapperClass(clazz);
        // 否则，默认情况
        } else {
            clazz.getConstructor();
            // 如果扩展点实现类没有名称，则尝试获取其 `@Extension` 注解指定的名称
            // 还没有指定的话，使用扩展点实现类名称的前缀，例如 XxxProtocol 取 xxx 作为其名称
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException(
                            "No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 缓存 `@Activate` 注解信息
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    // 缓存扩展点实现类信息
                    cacheName(clazz, n);
                    // 将扩展点实现类保存至 `extensionClasses` 集合中，并检查是否出现相同的扩展点名称
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name, boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        if (c == null || overridden) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // duplicate implementation is unacceptable
            unacceptableExceptions.add(name);
            String duplicateMsg =
                    "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate =
                    clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getName()
                    + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 先加载的所有扩展点实现类并缓存起来，已加载则不做处理
            // 然后获取自适应扩展对象，不存在的话动态创建一个
            // 往这个自适应扩展对象中注入相关属性（如果有必要）
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    private Class<?> getAdaptiveExtensionClass() {
        // 加载的所有扩展点实现类并缓存起来，已加载则不做处理
        getExtensionClasses();
        // 如果缓存的自适应扩展对象的类不为空，则直接返回
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 动态创建一个自适应扩展对象，会先尝试获取 `扩展接口名$Adaptive` 自适应扩展类
        // 例如 `org.apache.dubbo.rpc.Protocol$Adaptive` 扩展点实现类
        // 内部通过解析入参 URL 使用的协议，由 Dubbo SPI 加载出该协议对应的 XxxProtocol 实现类，再由该实现类去执行逻辑处理
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    private Class<?> createAdaptiveExtensionClass() {
        // 获取加载 ExtensionLoader 的类加载器
        ClassLoader classLoader = findClassLoader();
        try {
            // 尝试加载 `扩展接口名$Adaptive`
            return classLoader.loadClass(type.getName() + "$Adaptive");
        } catch (ClassNotFoundException e) {
            //ignore
        }
        /**
         * 不存在上面名称的自适应扩展对象的类，则通过 Javassist（默认）动态生成一个类
         * 生成的实现类所实现的方法默认抛出 UnsupportedOperationException 异常，空实现
         * 如果扩展接口的方法上面添加了 `@Adaptive` 注解，则在重写该方法的时候会再通过 Dubbo SPI 加载根据参数加载出对应实现类，然后进行处理
         * 参考 {@link AdaptiveClassCodeGenerator}
         */
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        org.apache.dubbo.common.compiler.Compiler compiler =
                ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }


    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
