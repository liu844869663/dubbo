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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.UnsafeByteArrayInputStream;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.codec.ExchangeCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcInvocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.encodeInvocationArgument;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DECODE_IN_IO_THREAD_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_DECODE_IN_IO_THREAD;

/**
 * Dubbo 编码器实现类
 * <p>
 * Dubbo codec.
 */
public class DubboCodec extends ExchangeCodec {

    /**
     * 编码器名称
     */
    public static final String NAME = "dubbo";
    /**
     * Dubbo RPC 版本
     */
    public static final String DUBBO_VERSION = Version.getProtocolVersion();
    /**
     * 响应 - 异常
     */
    public static final byte RESPONSE_WITH_EXCEPTION = 0;
    /**
     * 响应 - 正常（有返回）
     */
    public static final byte RESPONSE_VALUE = 1;
    /**
     * 响应 - 正常（空返回）
     */
    public static final byte RESPONSE_NULL_VALUE = 2;
    /**
     * 响应 - 异常（可以携带隐式参数）
     */
    public static final byte RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS = 3;
    /**
     * 响应 - 正常（有返回，可以携带隐式参数）
     */
    public static final byte RESPONSE_VALUE_WITH_ATTACHMENTS = 4;
    /**
     * 响应 - 正常（空返回，可以携带隐式参数）
     */
    public static final byte RESPONSE_NULL_VALUE_WITH_ATTACHMENTS = 5;

    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    public static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];
    private static final Logger log = LoggerFactory.getLogger(DubboCodec.class);

    @Override
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // 获取请求体标记，Serialization 对象
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        // 获取请求或者响应编号
        long id = Bytes.bytes2long(header, 4);
        if ((flag & FLAG_REQUEST) == 0) { // 消费端解码响应数据
            // decode response.
            Response res = new Response(id);
            // 心跳事件，设置响应为事件响应
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(true);
            }
            // get status.
            // 设置状态码
            byte status = header[3];
            res.setStatus(status);
            try {
                if (status == Response.OK) { // 正常响应
                    Object data;
                    if (res.isEvent()) { // 事件
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        if (CodecSupport.isHeartBeat(eventPayload, proto)) { // 心跳事件
                            // heart beat response data is always null;
                            data = null;
                        } else { // 其他事件
                            ObjectInput in = CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto);
                            data = decodeEventData(channel, in, eventPayload);
                        }
                    } else { // 普通响应
                        DecodeableRpcResult result;
                        // 在通信框架（例如，Netty）的 IO 线程，解码
                        if (channel.getUrl().getParameter(DECODE_IN_IO_THREAD_KEY, DEFAULT_DECODE_IN_IO_THREAD)) {
                            result = new DecodeableRpcResult(channel, res, is,
                                    (Invocation) getRequestData(id), proto);
                            result.decode();
                        } else { // 在 Dubbo ThreadPool 线程，解码，使用 DecodeHandler
                            result = new DecodeableRpcResult(channel, res,
                                    new UnsafeByteArrayInputStream(readMessageData(is)),
                                    (Invocation) getRequestData(id), proto);
                        }
                        data = result;
                    }
                    // 设置结果
                    res.setResult(data);
                } else { // 异常响应
                    // 设置异常信息
                    ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode response failed: " + t.getMessage(), t);
                }
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else { // 服务端解码请求数据
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            // 心跳事件，设置请求为事件请求
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(true);
            }
            try {
                Object data;
                if (req.isEvent()) { // 事件
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    if (CodecSupport.isHeartBeat(eventPayload, proto)) { // 心跳事件
                        // heart beat response data is always null;
                        data = null;
                    } else { // 其他事件
                        ObjectInput in = CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto);
                        data = decodeEventData(channel, in, eventPayload);
                    }
                } else { // 普通请求
                    DecodeableRpcInvocation inv;
                    // 在通信框架（例如，Netty）的 IO 线程，解码
                    if (channel.getUrl().getParameter(DECODE_IN_IO_THREAD_KEY, DEFAULT_DECODE_IN_IO_THREAD)) {
                        inv = new DecodeableRpcInvocation(channel, req, is, proto);
                        inv.decode();
                    } else { // 在 Dubbo ThreadPool 线程，解码，使用 DecodeHandler
                        inv = new DecodeableRpcInvocation(channel, req,
                                new UnsafeByteArrayInputStream(readMessageData(is)), proto);
                    }
                    data = inv;
                }
                // 设置请求数据
                req.setData(data);
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode request failed: " + t.getMessage(), t);
                }
                // bad request
                req.setBroken(true);
                req.setData(t);
            }

            return req;
        }
    }

    private byte[] readMessageData(InputStream is) throws IOException {
        if (is.available() > 0) {
            byte[] result = new byte[is.available()];
            is.read(result);
            return result;
        }
        return new byte[]{};
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        // 消费端编码请求数据
        encodeRequestData(channel, out, data, DUBBO_VERSION);
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        // 服务端编码响应数据
        encodeResponseData(channel, out, data, DUBBO_VERSION);
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        RpcInvocation inv = (RpcInvocation) data;

        // 写入 Dubbo RPC 版本
        out.writeUTF(version);
        // https://github.com/apache/dubbo/issues/6138
        String serviceName = inv.getAttachment(INTERFACE_KEY);
        if (serviceName == null) {
            serviceName = inv.getAttachment(PATH_KEY);
        }
        // 写入接口名称
        out.writeUTF(serviceName);
        // 写入版本号
        out.writeUTF(inv.getAttachment(VERSION_KEY));
        // 写入方法名称
        out.writeUTF(inv.getMethodName());
        // 写入方法参数描述
        out.writeUTF(inv.getParameterTypesDesc());
        // 将方法入参依次写入
        Object[] args = inv.getArguments();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                out.writeObject(encodeInvocationArgument(channel, inv, i));
            }
        }
        // 写入隐式参数集合
        out.writeAttachments(inv.getObjectAttachments());
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        Result result = (Result) data;
        // currently, the version value in Response records the version of Request
        // 根据 Dubbo RPC 版本，判断是否可以携带隐式参数
        boolean attach = Version.isSupportResponseAttachment(version);
        Throwable th = result.getException();
        if (th == null) { // 没有异常
            // 获取方法执行结果
            Object ret = result.getValue();
            if (ret == null) {
                // 写入一个结果，表示空数据，区别在于是否可以添加隐式参数
                out.writeByte(attach ? RESPONSE_NULL_VALUE_WITH_ATTACHMENTS : RESPONSE_NULL_VALUE);
            } else {
                // 写入一个结果，表示正常数据，区别在于是否可以添加隐式参数
                out.writeByte(attach ? RESPONSE_VALUE_WITH_ATTACHMENTS : RESPONSE_VALUE);
                // 写入返回结果
                out.writeObject(ret);
            }
        } else { // 异常
            // 写入一个结果，表示出现异常，区别在于是否可以添加隐式参数
            out.writeByte(attach ? RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS : RESPONSE_WITH_EXCEPTION);
            // 写入异常
            out.writeThrowable(th);
        }

        if (attach) {
            // returns current version of Response to consumer side.
            // 往结果的隐式参数集合中添加 `dubbo -> 2.0.2` 参数
            result.getObjectAttachments().put(DUBBO_VERSION_KEY, Version.getProtocolVersion());
            // 写入隐式参数
            out.writeAttachments(result.getObjectAttachments());
        }
    }

    @Override
    protected Serialization getSerialization(Channel channel, Request req) {
        if (!(req.getData() instanceof Invocation)) {
            return super.getSerialization(channel, req);
        }
        return DubboCodecSupport.getRequestSerialization(channel.getUrl(), (Invocation) req.getData());
    }

    @Override
    protected Serialization getSerialization(Channel channel, Response res) {
        if (!(res.getResult() instanceof AppResponse)) {
            return super.getSerialization(channel, res);
        }
        return DubboCodecSupport.getResponseSerialization(channel.getUrl(), (AppResponse) res.getResult());
    }

}
