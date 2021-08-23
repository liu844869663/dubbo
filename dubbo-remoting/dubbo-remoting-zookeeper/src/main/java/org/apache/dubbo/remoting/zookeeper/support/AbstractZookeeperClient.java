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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

/**
 * zk 客户端的抽象类，实现通用逻辑
 *
 * @param <TargetDataListener>  节点数据监听器具体对象
 * @param <TargetChildListener> 子节点监听器具体对象
 */
public abstract class AbstractZookeeperClient<TargetDataListener, TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    /**
     * 默认的连接 zk 超时时间 5s
     */
    protected int DEFAULT_CONNECTION_TIMEOUT_MS = 5 * 1000;
    /**
     * 默认的 zk 连接 Session 失效时间 60s
     */
    protected int DEFAULT_SESSION_TIMEOUT_MS = 60 * 1000;

    /**
     * 注册中心 URL
     */
    private final URL url;

    /**
     * zk 连接状态监听器的集合
     */
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();

    /**
     * ChildListener 子节点监听器的集合
     * key1：节点路径
     * key2：ChildListener 对象
     * value：监听器具体对象。不同 Zookeeper 客户端，实现会不同。
     */
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();

    private final ConcurrentMap<String, ConcurrentMap<DataListener, TargetDataListener>> listeners = new ConcurrentHashMap<String, ConcurrentMap<DataListener, TargetDataListener>>();

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 保存 zk 中已创建的永久节点对应的路径，避免重复创建
     */
    private final Set<String> persistentExistNodePath = new ConcurrentHashSet<>();

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void delete(String path) {
        //never mind if ephemeral
        persistentExistNodePath.remove(path);
        deletePath(path);
    }


    @Override
    public void create(String path, boolean ephemeral) {
        // 如果是永久节点，则进行下面判断
        if (!ephemeral) {
            // 该永久节点在缓存中，表示已创建，则直接返回
            if (persistentExistNodePath.contains(path)) {
                return;
            }
            // 检查 zk 中是否已存在这个节点，抽象方法
            if (checkExists(path)) {
                // 如果已存在，则将其添加缓存中，然后直接返回
                persistentExistNodePath.add(path);
                return;
            }
        }
        // zookeeper 创建节点只能一级一级的创建
        // 例如 `/dubbo/org.apache.dubbo.demo.service.StudyService/providers/服务提供者信息`
        // 除了最后一个服务者提供者信息节点，其他节点先一个一个创建，且都是永久节点
        int i = path.lastIndexOf('/');
        // 如果 / 不仅仅是在最前面，表示多级路径，则需要先创建前面的路径
        if (i > 0) {
            // 循环调用这个方法，永久节点
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            // 创建临时节点，当与 zk 连接断开是该节点消失，抽象方法
            createEphemeral(path);
        } else {
            // 创建永久节点，抽象方法
            createPersistent(path);
            // 将永久节点保存起来，避免
            persistentExistNodePath.add(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        // 获得该节点下的监听器数组
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.computeIfAbsent(path, k -> new ConcurrentHashMap<>());
        // 获取监听器具体对象，没有的话创建一个，抽象方法
        TargetChildListener targetListener = listeners.computeIfAbsent(listener, k -> createTargetChildListener(path, k));
        // 向 zk 设置这个监听器对象
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void addDataListener(String path, DataListener listener) {
        this.addDataListener(path, listener, null);
    }

    @Override
    public void addDataListener(String path, DataListener listener, Executor executor) {
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.computeIfAbsent(path, k -> new ConcurrentHashMap<>());
        TargetDataListener targetListener = dataListenerMap.computeIfAbsent(listener, k -> createTargetDataListener(path, k));
        addTargetDataListener(path, targetListener, executor);
    }

    @Override
    public void removeDataListener(String path, DataListener listener) {
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap != null) {
            TargetDataListener targetListener = dataListenerMap.remove(listener);
            if (targetListener != null) {
                removeTargetDataListener(path, targetListener);
            }
        }
    }

    @Override
    public void removeChildListener(String path, ChildListener listener) {
        // 获得该节点下的监听器数组
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            // 先从本地移除这个 ChildListener 监听器
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                // 从 zk 移除这个监听器对象
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    /**
     * 回调 zk 连接状态监听器数组
     *
     * @param state 新的 zk 连接状态
     */
    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // 关闭连接，抽象方法
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void create(String path, String content, boolean ephemeral) {
        // 检查这个路径是否已存在，抽象方法
        if (checkExists(path)) {
            // 已存在的话则删除
            delete(path);
        }
        // zookeeper 创建节点只能一级一级的创建
        // 例如 `/dubbo/org.apache.dubbo.demo.service.StudyService/providers/服务提供者信息`
        // 除了最后一个服务者提供者信息节点，其他节点先一个一个创建，且都是永久节点
        int i = path.lastIndexOf('/');
        // 如果 / 不仅仅是在最前面，表示多级路径，则需要先创建前面的路径
        if (i > 0) {
            // 循环调用这个方法，永久节点
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            // 创建临时节点并设置内容，当与 zk 连接断开是该节点消失，抽象方法
            createEphemeral(path, content);
        } else {
            // 创建永久节点并设置内容，抽象方法
            createPersistent(path, content);
        }
    }

    @Override
    public String getContent(String path) {
        if (!checkExists(path)) {
            return null;
        }
        return doGetContent(path);
    }

    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract void createPersistent(String path, String data);

    protected abstract void createEphemeral(String path, String data);

    public abstract boolean checkExists(String path);

    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    protected abstract TargetDataListener createTargetDataListener(String path, DataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener, Executor executor);

    protected abstract void removeTargetDataListener(String path, TargetDataListener listener);

    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

    protected abstract String doGetContent(String path);

    /**
     * we invoke the zookeeper client to delete the node
     *
     * @param path the node path
     */
    protected abstract void deletePath(String path);

}
