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
package org.apache.dubbo.remoting.exchange;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * Response
 */
public class Response {

    /**
     * ok.
     */
    public static final byte OK = 20;

    /**
     * 客户端超时
     * <p>
     * client side timeout.
     */
    public static final byte CLIENT_TIMEOUT = 30;

    /**
     * 服务端超时
     * <p>
     * server side timeout.
     */
    public static final byte SERVER_TIMEOUT = 31;

    /**
     * 通道无效，返回未完成的请求
     * <p>
     * channel inactive, directly return the unfinished requests.
     */
    public static final byte CHANNEL_INACTIVE = 35;

    /**
     * 请求错误
     * <p>
     * request format error.
     */
    public static final byte BAD_REQUEST = 40;

    /**
     * 响应错误
     * <p>
     * response format error.
     */
    public static final byte BAD_RESPONSE = 50;

    /**
     * 服务没找到
     * <p>
     * service not found.
     */
    public static final byte SERVICE_NOT_FOUND = 60;

    /**
     * 服务出现异常
     * <p>
     * service error.
     */
    public static final byte SERVICE_ERROR = 70;

    /**
     * 服务端服务器内部异常
     * <p>
     * internal server error.
     */
    public static final byte SERVER_ERROR = 80;

    /**
     * 客户端服务器内部异常
     * <p>
     * internal server error.
     */
    public static final byte CLIENT_ERROR = 90;

    /**
     * 服务端的线程池已耗尽
     * <p>
     * server side threadpool exhausted and quick return.
     */
    public static final byte SERVER_THREADPOOL_EXHAUSTED_ERROR = 100;

    /**
     * 响应编号
     */
    private long mId = 0;

    /**
     * Dubbo PRC 版本
     */
    private String mVersion;

    /**
     * 响应状态
     */
    private byte mStatus = OK;

    /**
     * 是否是事件
     */
    private boolean mEvent = false;

    /**
     * 错误消息
     */
    private String mErrorMsg;

    /**
     * 结果
     */
    private Object mResult;

    public Response() {
    }

    public Response(long id) {
        mId = id;
    }

    public Response(long id, String version) {
        mId = id;
        mVersion = version;
    }

    public long getId() {
        return mId;
    }

    public void setId(long id) {
        mId = id;
    }

    public String getVersion() {
        return mVersion;
    }

    public void setVersion(String version) {
        mVersion = version;
    }

    public byte getStatus() {
        return mStatus;
    }

    public void setStatus(byte status) {
        mStatus = status;
    }

    public boolean isEvent() {
        return mEvent;
    }

    public void setEvent(String event) {
        mEvent = true;
        mResult = event;
    }

    public void setEvent(boolean mEvent) {
        this.mEvent = mEvent;
    }

    public boolean isHeartbeat() {
        return mEvent && HEARTBEAT_EVENT == mResult;
    }

    @Deprecated
    public void setHeartbeat(boolean isHeartbeat) {
        if (isHeartbeat) {
            setEvent(HEARTBEAT_EVENT);
        }
    }

    public Object getResult() {
        return mResult;
    }

    public void setResult(Object msg) {
        mResult = msg;
    }

    public String getErrorMessage() {
        return mErrorMsg;
    }

    public void setErrorMessage(String msg) {
        mErrorMsg = msg;
    }

    @Override
    public String toString() {
        return "Response [id=" + mId + ", version=" + mVersion + ", status=" + mStatus + ", event=" + mEvent
                + ", error=" + mErrorMsg + ", result=" + (mResult == this ? "this" : mResult) + "]";
    }
}