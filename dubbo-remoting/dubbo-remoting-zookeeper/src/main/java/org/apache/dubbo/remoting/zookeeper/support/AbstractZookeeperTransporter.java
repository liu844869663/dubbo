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
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperTransporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * ZookeeperTransporter 的抽象类，提供通用逻辑
 *
 * AbstractZookeeperTransporter is abstract implements of ZookeeperTransporter.
 * <p>
 * If you want to extends this, implements createZookeeperClient.
 */
public abstract class AbstractZookeeperTransporter implements ZookeeperTransporter {
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperTransporter.class);
    /**
     * 缓存 Zookeeper 客户端
     * key：注册中心地址
     * value：注册中心客户端
     */
    private final Map<String, ZookeeperClient> zookeeperClientMap = new ConcurrentHashMap<>();

    /**
     * share connect for registry, metadata, etc..
     * <p>
     * Make sure the connection is connected.
     *
     * @param url
     * @return
     */
    @Override
    public ZookeeperClient connect(URL url) {
        ZookeeperClient zookeeperClient;
        // address format: {[username:password@]address}
        // 获取注册中心的地址，集群的话返回多个
        List<String> addressList = getURLBackupAddress(url);
        // The field define the zookeeper server , including protocol, host, port, username, password
        // 从缓存中获取该注册中心地址对应的 Zookeeper 客户端
        if ((zookeeperClient = fetchAndUpdateZookeeperClientCache(addressList)) != null && zookeeperClient.isConnected()) {
            logger.info("find valid zookeeper client from the cache for address: " + url);
            return zookeeperClient;
        }
        // avoid creating too many connections， so add lock
        // 加锁
        synchronized (zookeeperClientMap) {
            // 再次从缓存中获取该注册中心地址对应的 Zookeeper 客户端
            if ((zookeeperClient = fetchAndUpdateZookeeperClientCache(addressList)) != null && zookeeperClient.isConnected()) {
                logger.info("find valid zookeeper client from the cache for address: " + url);
                return zookeeperClient;
            }
            /**
             * 缓存中没有，则创建一个 Zookeeper 客户端
             * 在子类 {@link CuratorZookeeperTransporter} 中返回一个 {@link CuratorZookeeperClient} 对象
             * 在创建该对象的时候会通过 curator 构建一个 CuratorFrameworkImpl 对象，并启动它，直至启动成功，有与 zk 可用的连接（或者 5s 超时），否则抛出异常
             * 同时会设置 zk 连接状态监听器，最终交由 {@link CuratorZookeeperClient#stateChanged(int)} 方法来处理
             */
            zookeeperClient = createZookeeperClient(url);
            logger.info("No valid zookeeper client found from cache, therefore create a new client for url. " + url);
            // 放入缓存中
            writeToClientMap(addressList, zookeeperClient);
        }
        return zookeeperClient;
    }

    /**
     * @param url the url that will create zookeeper connection .
     *            The url in AbstractZookeeperTransporter#connect parameter is rewritten by this one.
     *            such as: zookeeper://127.0.0.1:2181/org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter
     * @return
     */
    protected abstract ZookeeperClient createZookeeperClient(URL url);

    /**
     * get the ZookeeperClient from cache, the ZookeeperClient must be connected.
     * <p>
     * It is not private method for unit test.
     *
     * @param addressList
     * @return ZookeeperClient 客户端对象
     */
    ZookeeperClient fetchAndUpdateZookeeperClientCache(List<String> addressList) {

        ZookeeperClient zookeeperClient = null;
        for (String address : addressList) {
            if ((zookeeperClient = zookeeperClientMap.get(address)) != null && zookeeperClient.isConnected()) {
                break;
            }
        }
        if (zookeeperClient != null && zookeeperClient.isConnected()) {
            writeToClientMap(addressList, zookeeperClient);
        }
        return zookeeperClient;
    }

    /**
     * get all zookeeper urls (such as :zookeeper://127.0.0.1:2181?127.0.0.1:8989,127.0.0.1:9999)
     *
     * @param url such as:zookeeper://127.0.0.1:2181?127.0.0.1:8989,127.0.0.1:9999
     * @return such as 127.0.0.1:2181,127.0.0.1:8989,127.0.0.1:9999
     */
    List<String> getURLBackupAddress(URL url) {
        List<String> addressList = new ArrayList<String>();
        // 注册中心的地址
        addressList.add(url.getAddress());
        // 从 URL 中获取 `backup` 参数，也作为注册中心的地址
        addressList.addAll(url.getParameter(RemotingConstants.BACKUP_KEY, Collections.EMPTY_LIST));

        // 如果配置了用户名密码，则在每个注册中心地址前面添加 `用户名:密码@` 前缀
        String authPrefix = null;
        if (StringUtils.isNotEmpty(url.getUsername())) {
            StringBuilder buf = new StringBuilder();
            buf.append(url.getUsername());
            if (StringUtils.isNotEmpty(url.getPassword())) {
                buf.append(":");
                buf.append(url.getPassword());
            }
            buf.append("@");
            authPrefix = buf.toString();
        }

        if (StringUtils.isNotEmpty(authPrefix)) {
            List<String> authedAddressList = new ArrayList<>(addressList.size());
            for (String addr : addressList) {
                authedAddressList.add(authPrefix + addr);
            }
            return authedAddressList;
        }
        return addressList;
    }

    /**
     * write address-ZookeeperClient relationship to Map
     *
     * @param addressList
     * @param zookeeperClient
     */
    void writeToClientMap(List<String> addressList, ZookeeperClient zookeeperClient) {
        for (String address : addressList) {
            zookeeperClientMap.put(address, zookeeperClient);
        }
    }

    /**
     * redefine the url for zookeeper. just keep protocol, username, password, host, port, and individual parameter.
     *
     * @param url
     * @return
     */
    URL toClientURL(URL url) {
        Map<String, String> parameterMap = new HashMap<>();
        // for CuratorZookeeperClient
        if (url.getParameter(TIMEOUT_KEY) != null) {
            parameterMap.put(TIMEOUT_KEY, url.getParameter(TIMEOUT_KEY));
        }
        if (url.getParameter(RemotingConstants.BACKUP_KEY) != null) {
            parameterMap.put(RemotingConstants.BACKUP_KEY, url.getParameter(RemotingConstants.BACKUP_KEY));
        }

        return new URL(url.getProtocol(), url.getUsername(), url.getPassword(), url.getHost(), url.getPort(),
                ZookeeperTransporter.class.getName(), parameterMap);
    }

    /**
     * for unit test
     *
     * @return
     */
    Map<String, ZookeeperClient> getZookeeperClientMap() {
        return zookeeperClientMap;
    }
}
