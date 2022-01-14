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
package org.apache.rocketmq.client.producer;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.protocol.NamespaceUtil;
import org.apache.rocketmq.remoting.RPCHook;

import java.util.concurrent.ExecutorService;

public class TransactionMQProducer extends DefaultMQProducer {
    private int checkThreadPoolMinSize = 1;
    private int checkThreadPoolMaxSize = 1;
    private int checkRequestHoldMax = 2000;

    private ExecutorService executorService;

    private TransactionListener transactionListener;

    public TransactionMQProducer() {
    }

    public TransactionMQProducer(final String producerGroup) {
        this(null, producerGroup, null);
    }

    public TransactionMQProducer(final String namespace, final String producerGroup, RPCHook rpcHook) {
        super(namespace, producerGroup, rpcHook);
    }

    public TransactionMQProducer(final String namespace, final String producerGroup, RPCHook rpcHook, boolean enableMsgTrace, final String customizedTraceTopic) {
        super(namespace, producerGroup, rpcHook, enableMsgTrace, customizedTraceTopic);
    }

    @Override
    public void start() throws MQClientException {
        this.defaultMQProducerImpl.initTransactionEnv();
        super.start();
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.defaultMQProducerImpl.destroyTransactionEnv();
    }

    /**
     * 发送事务消息
     *
     * @param msg Transactional message to send.
     * @param arg Argument used along with local transaction executor.
     * @return 发送的结果对象
     * @throws MQClientException
     */
    @Override
    public TransactionSendResult sendMessageInTransaction(final Message msg, final Object arg) throws MQClientException {
        // 本地事务处理逻辑必须要先定义好
        if (null == this.transactionListener) {
            throw new MQClientException("TransactionListener is null", null);
        }

        // 对topic进行包装,附带延迟、重试标记
        msg.setTopic(NamespaceUtil.wrapNamespace(this.getNamespace(), msg.getTopic()));
        return this.defaultMQProducerImpl.sendMessageInTransaction(msg, null, arg);
    }

    public int getCheckThreadPoolMinSize() {
        return checkThreadPoolMinSize;
    }

    public int getCheckThreadPoolMaxSize() {
        return checkThreadPoolMaxSize;
    }

    public int getCheckRequestHoldMax() {
        return checkRequestHoldMax;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public TransactionListener getTransactionListener() {
        return transactionListener;
    }

    public void setTransactionListener(TransactionListener transactionListener) {
        this.transactionListener = transactionListener;
    }
}
