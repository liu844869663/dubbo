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
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

public class ExecutorUtil {
    private static final Logger logger = LoggerFactory.getLogger(ExecutorUtil.class);
    private static final ThreadPoolExecutor SHUTDOWN_EXECUTOR = new ThreadPoolExecutor(0, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(100),
            new NamedThreadFactory("Close-ExecutorService-Timer", true));

    public static boolean isTerminated(Executor executor) {
        if (executor instanceof ExecutorService) {
            if (((ExecutorService) executor).isTerminated()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 优雅关闭，禁止新的任务提交，将原有任务执行完
     *
     * Use the shutdown pattern from:
     * https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html
     *
     * @param executor the Executor to shutdown
     * @param timeout  the timeout in milliseconds before termination
     */
    public static void gracefulShutdown(Executor executor, int timeout) {
        // 忽略，若不是 ExecutorService ，或者已经关闭
        if (!(executor instanceof ExecutorService) || isTerminated(executor)) {
            return;
        }
        // 关闭，禁止新的任务提交，将原有任务执行完
        final ExecutorService es = (ExecutorService) executor;
        try {
            // Disable new tasks from being submitted
            es.shutdown();
        } catch (SecurityException | NullPointerException ex2) {
            return;
        }
        try {
            // Wait a while for existing tasks to terminate
            // 等待原有任务执行完。若等待超时，强制结束所有任务
            if (!es.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                es.shutdownNow();
            }
        } catch (InterruptedException ex) {
            // 发生 InterruptedException 异常，也强制结束所有任务
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (!isTerminated(es)) {
            // 若未关闭成功，新开线程去关闭
            newThreadToCloseExecutor(es);
        }
    }

    // 强制关闭，包括打断原有执行中的任务
    public static void shutdownNow(Executor executor, final int timeout) {
        // 忽略，若不是 ExecutorService ，或者已经关闭
        if (!(executor instanceof ExecutorService) || isTerminated(executor)) {
            return;
        }
        // 立即关闭，包括原有任务也打断
        final ExecutorService es = (ExecutorService) executor;
        try {
            es.shutdownNow();
        } catch (SecurityException | NullPointerException ex2) {
            return;
        }
        try {
            // 等待原有任务被打断完成
            es.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        // 若未关闭成功，新开线程去关闭
        if (!isTerminated(es)) {
            newThreadToCloseExecutor(es);
        }
    }

    private static void newThreadToCloseExecutor(final ExecutorService es) {
        if (!isTerminated(es)) {
            SHUTDOWN_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 循环 1000 次，不断强制结束线程池
                        for (int i = 0; i < 1000; i++) {
                            // 立即关闭，包括原有任务也打断
                            es.shutdownNow();
                            // 等待原有任务被打断完成
                            if (es.awaitTermination(10, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            });
        }
    }

    /**
     * append thread name with url address
     *
     * @return new url with updated thread name
     */
    public static URL setThreadName(URL url, String defaultName) {
        String name = url.getParameter(THREAD_NAME_KEY, defaultName);
        name = name + "-" + url.getAddress();
        url = url.addParameter(THREAD_NAME_KEY, name);
        return url;
    }

    public static void cancelScheduledFuture(ScheduledFuture<?> scheduledFuture) {
        ScheduledFuture<?> future = scheduledFuture;
        if (future != null && !future.isCancelled()) {
            future.cancel(true);
        }
    }
}
