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
package org.apache.dubbo.remoting.zookeeper.curator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.EventType;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.support.AbstractZookeeperClient;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

public class CuratorZookeeperClient extends AbstractZookeeperClient<CuratorZookeeperClient.NodeCacheListenerImpl, CuratorZookeeperClient.CuratorWatcherImpl> {

    protected static final Logger logger = LoggerFactory.getLogger(CuratorZookeeperClient.class);
    private static final String ZK_SESSION_EXPIRE_KEY = "zk.session.expire";

    /**
     * 节点内容的编码格式
     */
    static final Charset CHARSET = StandardCharsets.UTF_8;
    /**
     * zk 客户端对象
     */
    private final CuratorFramework client;
    /**
     * 缓存节点数据
     * key：节点路径
     * value：节点数据
     */
    private static Map<String, NodeCache> nodeCacheMap = new ConcurrentHashMap<>();

    public CuratorZookeeperClient(URL url) {
        // 设置注册中心 URL 对象
        super(url);
        try {
            // 连接 zk 的超时时间，默认 5s
            int timeout = url.getParameter(TIMEOUT_KEY, DEFAULT_CONNECTION_TIMEOUT_MS);
            // zk session 失效时间，默认 60s
            int sessionExpireMs = url.getParameter(ZK_SESSION_EXPIRE_KEY, DEFAULT_SESSION_TIMEOUT_MS);
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    // 注册中心地址，集群的话会从 `url` 的 `backup` 参数中解析出其他地址，以逗号分隔
                    .connectString(url.getBackupAddress())
                    // 重试策略，1次，间隔 1s
                    .retryPolicy(new RetryNTimes(1, 1000))
                    // 连接超时时间
                    .connectionTimeoutMs(timeout)
                    // Session 失效时间
                    .sessionTimeoutMs(sessionExpireMs);
            // 获取用户名密码
            String authority = url.getAuthority();
            if (authority != null && authority.length() > 0) {
                // 设置用户名密码
                builder = builder.authorization("digest", authority.getBytes());
            }
            // 构建一个 CuratorFrameworkImpl 对象
            client = builder.build();
            /**
             * 设置一个 {@link CuratorConnectionStateListener} 连接状态监听器
             * 当 zk 连接状态发生变更时，会交由 {@link this#stateChanged(int)} 方法来处理，也就让当前对象中的 {@link stateListeners} zk 连接状态监听器来处理
             */
            client.getConnectionStateListenable().addListener(new CuratorConnectionStateListener(url));
            // 启动 zk 客户端
            client.start();
            // 阻塞直到与 zk 的连接可用，或超过 5s
            boolean connected = client.blockUntilConnected(timeout, TimeUnit.MILLISECONDS);
            // 如果未连接，则抛出异常
            if (!connected) {
                throw new IllegalStateException("zookeeper not connected");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void createPersistent(String path) {
        try {
            // 创建节点
            client.create().forPath(path);
        } catch (NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists.", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void createEphemeral(String path) {
        try {
            // 创建节点，并设置模式为短暂的，也就是临时节点
            client.create().withMode(CreateMode.EPHEMERAL).forPath(path);
        } catch (NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists, since we will only try to recreate a node on a session expiration" +
                    ", this duplication might be caused by a delete delay from the zk server, which means the old expired session" +
                    " may still holds this ZNode and the server just hasn't got time to do the deletion. In this case, " +
                    "we can just try to delete and create again.", e);
            // 出现节点已存在的异常，则尝试删除掉再重新创建一次
            deletePath(path);
            createEphemeral(path);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected void createPersistent(String path, String data) {
        // 将节点内容转换成 byte 字节数组
        byte[] dataBytes = data.getBytes(CHARSET);
        try {
            // 创建节点并设置内容
            client.create().forPath(path, dataBytes);
        } catch (NodeExistsException e) {
            try {
                // 节点已存在则直接设置内容
                client.setData().forPath(path, dataBytes);
            } catch (Exception e1) {
                throw new IllegalStateException(e.getMessage(), e1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected void createEphemeral(String path, String data) {
        // 将节点内容转换成 byte 字节数组
        byte[] dataBytes = data.getBytes(CHARSET);
        try {
            // 创建节点，并设置模式为短暂的，也就是临时节点，同时设置内容
            client.create().withMode(CreateMode.EPHEMERAL).forPath(path, dataBytes);
        } catch (NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists, since we will only try to recreate a node on a session expiration" +
                    ", this duplication might be caused by a delete delay from the zk server, which means the old expired session" +
                    " may still holds this ZNode and the server just hasn't got time to do the deletion. In this case, " +
                    "we can just try to delete and create again.", e);
            // 出现节点已存在的异常，则尝试删除掉再重新创建一次
            deletePath(path);
            createEphemeral(path, data);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected void deletePath(String path) {
        try {
            // 删除在这个节点，同时删除会删除子节点
            client.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (NoNodeException ignored) {
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public List<String> getChildren(String path) {
        try {
            // 获取这个节点的子节点
            return client.getChildren().forPath(path);
        } catch (NoNodeException e) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public boolean checkExists(String path) {
        try {
            // 检查这个节点是否存在
            if (client.checkExists().forPath(path) != null) {
                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public boolean isConnected() {
        return client.getZookeeperClient().isConnected();
    }

    @Override
    public String doGetContent(String path) {
        try {
            byte[] dataBytes = client.getData().forPath(path);
            return (dataBytes == null || dataBytes.length == 0) ? null : new String(dataBytes, CHARSET);
        } catch (NoNodeException e) {
            // ignore NoNode Exception.
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public void doClose() {
        client.close();
    }

    @Override
    public CuratorZookeeperClient.CuratorWatcherImpl createTargetChildListener(String path, ChildListener listener) {
        return new CuratorZookeeperClient.CuratorWatcherImpl(client, listener, path);
    }

    @Override
    public List<String> addTargetChildListener(String path, CuratorWatcherImpl listener) {
        try {
            return client.getChildren().usingWatcher(listener).forPath(path);
        } catch (NoNodeException e) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected CuratorZookeeperClient.NodeCacheListenerImpl createTargetDataListener(String path, DataListener listener) {
        return new NodeCacheListenerImpl(client, listener, path);
    }

    @Override
    protected void addTargetDataListener(String path, CuratorZookeeperClient.NodeCacheListenerImpl nodeCacheListener) {
        this.addTargetDataListener(path, nodeCacheListener, null);
    }

    @Override
    protected void addTargetDataListener(String path, CuratorZookeeperClient.NodeCacheListenerImpl nodeCacheListener, Executor executor) {
        try {
            NodeCache nodeCache = new NodeCache(client, path);
            if (nodeCacheMap.putIfAbsent(path, nodeCache) != null) {
                return;
            }
            if (executor == null) {
                nodeCache.getListenable().addListener(nodeCacheListener);
            } else {
                nodeCache.getListenable().addListener(nodeCacheListener, executor);
            }

            nodeCache.start();
        } catch (Exception e) {
            throw new IllegalStateException("Add nodeCache listener for path:" + path, e);
        }
    }

    @Override
    protected void removeTargetDataListener(String path, CuratorZookeeperClient.NodeCacheListenerImpl nodeCacheListener) {
        NodeCache nodeCache = nodeCacheMap.get(path);
        if (nodeCache != null) {
            nodeCache.getListenable().removeListener(nodeCacheListener);
        }
        nodeCacheListener.dataListener = null;
    }

    @Override
    public void removeTargetChildListener(String path, CuratorWatcherImpl listener) {
        listener.unwatch();
    }

    static class NodeCacheListenerImpl implements NodeCacheListener {

        private CuratorFramework client;

        private volatile DataListener dataListener;

        private String path;

        protected NodeCacheListenerImpl() {
        }

        public NodeCacheListenerImpl(CuratorFramework client, DataListener dataListener, String path) {
            this.client = client;
            this.dataListener = dataListener;
            this.path = path;
        }

        @Override
        public void nodeChanged() throws Exception {
            ChildData childData = nodeCacheMap.get(path).getCurrentData();
            String content = null;
            EventType eventType;
            if (childData == null) {
                eventType = EventType.NodeDeleted;
            } else {
                content = new String(childData.getData(), CHARSET);
                eventType = EventType.NodeDataChanged;
            }
            dataListener.dataChanged(path, content, eventType);
        }
    }

    static class CuratorWatcherImpl implements CuratorWatcher {

        private CuratorFramework client;
        private volatile ChildListener childListener;
        private String path;

        public CuratorWatcherImpl(CuratorFramework client, ChildListener listener, String path) {
            this.client = client;
            this.childListener = listener;
            this.path = path;
        }

        protected CuratorWatcherImpl() {
        }

        public void unwatch() {
            this.childListener = null;
        }

        @Override
        public void process(WatchedEvent event) throws Exception {
            // if client connect or disconnect to server, zookeeper will queue
            // watched event(Watcher.Event.EventType.None, .., path = null).
            if (event.getType() == Watcher.Event.EventType.None) {
                return;
            }

            if (childListener != null) {
                childListener.childChanged(path, client.getChildren().usingWatcher(this).forPath(path));
            }
        }
    }

    private class CuratorConnectionStateListener implements ConnectionStateListener {
        /**
         * zk 连接的 Session 的未知 ID
         */
        private final long UNKNOWN_SESSION_ID = -1L;

        /**
         * 上一个 zk 连接的 Session 的 ID
         */
        private long lastSessionId;
        /**
         * 连接 zk 的超时时间，默认 5s
         */
        private int timeout;
        /**
         * zk 连接的 Session 失效时间，默认 60s
         */
        private int sessionExpireMs;

        public CuratorConnectionStateListener(URL url) {
            this.timeout = url.getParameter(TIMEOUT_KEY, DEFAULT_CONNECTION_TIMEOUT_MS);
            this.sessionExpireMs = url.getParameter(ZK_SESSION_EXPIRE_KEY, DEFAULT_SESSION_TIMEOUT_MS);
        }

        @Override
        public void stateChanged(CuratorFramework client, ConnectionState state) {
            // 先设置会话 ID 为 -1
            long sessionId = UNKNOWN_SESSION_ID;
            try {
                // 获取 zk 连接的会话 ID
                sessionId = client.getZookeeperClient().getZooKeeper().getSessionId();
            } catch (Exception e) {
                logger.warn("Curator client state changed, but failed to get the related zk session instance.");
            }

            // 根据监听到的变更后的 zk 连接状态作出相应处理
            if (state == ConnectionState.LOST) { // 丢失
                logger.warn("Curator zookeeper session " + Long.toHexString(lastSessionId) + " expired.");
                // 推送 Session 会话丢失事件
                CuratorZookeeperClient.this.stateChanged(StateListener.SESSION_LOST);
            } else if (state == ConnectionState.SUSPENDED) { // 挂起
                logger.warn("Curator zookeeper connection of session " + Long.toHexString(sessionId) + " timed out. " +
                        "connection timeout value is " + timeout + ", session expire timeout value is " + sessionExpireMs);
                // 推送 zk 连接被挂起事件
                CuratorZookeeperClient.this.stateChanged(StateListener.SUSPENDED);
            } else if (state == ConnectionState.CONNECTED) { // 连接
                lastSessionId = sessionId;
                logger.info("Curator zookeeper client instance initiated successfully, session id is " + Long.toHexString(sessionId));
                // 推送 zk 已连接事件
                CuratorZookeeperClient.this.stateChanged(StateListener.CONNECTED);
            } else if (state == ConnectionState.RECONNECTED) { // 重连
                // 重连后会话 ID 相同
                if (lastSessionId == sessionId && sessionId != UNKNOWN_SESSION_ID) {
                    logger.warn("Curator zookeeper connection recovered from connection lose, " +
                            "reuse the old session " + Long.toHexString(sessionId));
                    // 推送 zk 重连事件
                    CuratorZookeeperClient.this.stateChanged(StateListener.RECONNECTED);
                } else {
                    logger.warn("New session created after old session lost, " +
                            "old session " + Long.toHexString(lastSessionId) + ", new session " + Long.toHexString(sessionId));
                    // 记录本次会话 ID
                    lastSessionId = sessionId;
                    // 推送 zk 连接创建新的会话事件
                    CuratorZookeeperClient.this.stateChanged(StateListener.NEW_SESSION_CREATED);
                }
            }
        }

    }

    /**
     * just for unit test
     *
     * @return
     */
    CuratorFramework getClient() {
        return client;
    }
}
