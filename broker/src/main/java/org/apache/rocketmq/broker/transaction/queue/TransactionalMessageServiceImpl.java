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
package org.apache.rocketmq.broker.transaction.queue;

import org.apache.rocketmq.broker.transaction.AbstractTransactionalMessageCheckListener;
import org.apache.rocketmq.broker.transaction.OperationResult;
import org.apache.rocketmq.broker.transaction.TransactionalMessageService;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.EndTransactionRequestHeader;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class TransactionalMessageServiceImpl implements TransactionalMessageService {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.TRANSACTION_LOGGER_NAME);

    private TransactionalMessageBridge transactionalMessageBridge;

    private static final int PULL_MSG_RETRY_NUMBER = 1;

    private static final int MAX_PROCESS_TIME_LIMIT = 60000;

    private static final int MAX_RETRY_COUNT_WHEN_HALF_NULL = 1;

    public TransactionalMessageServiceImpl(TransactionalMessageBridge transactionBridge) {
        this.transactionalMessageBridge = transactionBridge;
    }

    private ConcurrentHashMap<MessageQueue, MessageQueue> opQueueMap = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<PutMessageResult> asyncPrepareMessage(MessageExtBrokerInner messageInner) {
        return transactionalMessageBridge.asyncPutHalfMessage(messageInner);
    }

    @Override
    public PutMessageResult prepareMessage(MessageExtBrokerInner messageInner) {
        return transactionalMessageBridge.putHalfMessage(messageInner);
    }

    /**
     * 是否需要（吞没、丢弃、不处理）
     * discard的依据：该消息回查的次数超过允许的最大回查次数，默认最大回查次数为5
     */
    private boolean needDiscard(MessageExt msgExt, int transactionCheckMax) {
        String checkTimes = msgExt.getProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES);
        int checkTime = 1;
        if (null != checkTimes) {
            checkTime = getInt(checkTimes);
            if (checkTime >= transactionCheckMax) {
                return true;
            } else {
                checkTime++;
            }
        }
        msgExt.putUserProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES, String.valueOf(checkTime));
        return false;
    }

    /**
     * 是否需要跳过
     * 判断依据：消息超过文件的过期时间，默认72消息
     */
    private boolean needSkip(MessageExt msgExt) {
        long valueOfCurrentMinusBorn = System.currentTimeMillis() - msgExt.getBornTimestamp();
        if (valueOfCurrentMinusBorn > transactionalMessageBridge.getBrokerController().getMessageStoreConfig().getFileReservedTime() * 3600L * 1000) {
            log.info("Half message exceed file reserved time ,so skip it.messageId {},bornTime {}", msgExt.getMsgId(), msgExt.getBornTimestamp());
            return true;
        }
        return false;
    }

    /**
     * 再次将消息存储到commitlog中，然后更新消息属性
     */
    private boolean putBackHalfMsgQueue(MessageExt msgExt, long offset) {
        // 再次将消息存储到commitlog中
        PutMessageResult putMessageResult = putBackToHalfQueueReturnResult(msgExt);
        // 存储成功修改消息的属性
        if (putMessageResult != null && putMessageResult.getPutMessageStatus() == PutMessageStatus.PUT_OK) {
            // 设置新的消费队列逻辑offset
            msgExt.setQueueOffset(putMessageResult.getAppendMessageResult().getLogicsOffset());
            // 设置新的commitlog的offset
            msgExt.setCommitLogOffset(putMessageResult.getAppendMessageResult().getWroteOffset());
            // 设置新的msgId
            msgExt.setMsgId(putMessageResult.getAppendMessageResult().getMsgId());
            log.debug("Send check message, the offset={} restored in queueOffset={} " + "commitLogOffset={} " + "newMsgId={} realMsgId={} topic={}", offset, msgExt.getQueueOffset(), msgExt.getCommitLogOffset(), msgExt.getMsgId(), msgExt.getUserProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX), msgExt.getTopic());
            return true;
        } else {
            log.error("PutBackToHalfQueueReturnResult write failed, topic: {}, queueId: {}, " + "msgId: {}", msgExt.getTopic(), msgExt.getQueueId(), msgExt.getMsgId());
            return false;
        }
    }

    /**
     * 检查事务
     *
     * @param transactionTimeout  事务的超时时间 The minimum time of the transactional message to be checked firstly, one message only
     *                            exceed this time interval that can be checked.
     * @param transactionCheckMax The maximum number of times the message was checked, if exceed this value, this
     *                            message will be discarded.
     * @param listener            When the message is considered to be checked or discarded, the relative method of this class will
     */
    @Override
    public void check(long transactionTimeout, int transactionCheckMax, AbstractTransactionalMessageCheckListener listener) {
        try {
            // 定义topic为"RMQ_SYS_TRANS_HALF_TOPIC"
            String topic = TopicValidator.RMQ_SYS_TRANS_HALF_TOPIC;
            // 获取half队列
            Set<MessageQueue> msgQueues = transactionalMessageBridge.fetchMessageQueues(topic);
            if (msgQueues == null || msgQueues.size() == 0) {
                log.warn("The queue of topic is empty :" + topic);
                return;
            }
            log.debug("Check topic={}, queues={}", topic, msgQueues);
            for (MessageQueue messageQueue : msgQueues) {
                long startTime = System.currentTimeMillis();
                // 获取opQueue
                MessageQueue opQueue = getOpQueue(messageQueue);
                // 获取half队列的消费offset
                long halfOffset = transactionalMessageBridge.fetchConsumeOffset(messageQueue);
                // 获取op队列的offset
                long opOffset = transactionalMessageBridge.fetchConsumeOffset(opQueue);
                log.info("Before check, the queue={} msgOffset={} opOffset={}", messageQueue, halfOffset, opOffset);
                if (halfOffset < 0 || opOffset < 0) {
                    log.error("MessageQueue: {} illegal offset read: {}, op offset: {},skip this queue", messageQueue, halfOffset, opOffset);
                    continue;
                }

                List<Long/*处理过得opQueue的offset*/> doneOpOffset = new ArrayList<>();
                HashMap<Long/*halfQueue offset*/, Long/*opQueue offset*/> removeMap = new HashMap<>();  //存储已经处理过,但还未添加到doneOpOffset中的消息
                // 将已处理但未更新的消息保存到removeMap中，后续进行判断时需要
                PullResult pullResult = fillOpRemoveMap(removeMap, opQueue, opOffset, halfOffset, doneOpOffset);
                if (null == pullResult) {
                    log.error("The queue={} check msgOffset={} with opOffset={} failed, pullResult is null", messageQueue, halfOffset, opOffset);
                    continue;
                }
                int getMessageNullCount = 1;  //获取空消息的次数
                long newOffset = halfOffset;  //当前处理half队列的最新进度
                long i = halfOffset; //当前处理消息的half队列偏移量
                while (true) {
                    // 检查当前任务的时间片是否用完
                    if (System.currentTimeMillis() - startTime > MAX_PROCESS_TIME_LIMIT) {
                        log.info("Queue={} process time reach max={}", messageQueue, MAX_PROCESS_TIME_LIMIT);
                        break;
                    }
                    // 如果half消息已经被处理过,继续处理下一条消息
                    if (removeMap.containsKey(i)) {
                        log.debug("Half offset {} has been committed/rolled back", i);
                        Long removedOpOffset = removeMap.remove(i);
                        doneOpOffset.add(removedOpOffset);
                    } else {
                        // 根据half队列消费offset获取消息
                        GetResult getResult = getHalfMsg(messageQueue, i);
                        MessageExt msgExt = getResult.getMsg();
                        if (msgExt == null) {
                            // 是否最大重试次数
                            if (getMessageNullCount++ > MAX_RETRY_COUNT_WHEN_HALF_NULL) {
                                break;
                            }
                            if (getResult.getPullResult().getPullStatus() == PullStatus.NO_NEW_MSG) {
                                log.debug("No new msg, the miss offset={} in={}, continue check={}, pull result={}", i, messageQueue, getMessageNullCount, getResult.getPullResult());
                                break;
                            } else {
                                log.info("Illegal offset, the miss offset={} in={}, continue check={}, pull result={}", i, messageQueue, getMessageNullCount, getResult.getPullResult());
                                i = getResult.getPullResult().getNextBeginOffset();
                                newOffset = i;
                                continue;
                            }
                        }

                        // 如果超过存储时间(默认3天)或者超过回查时间(默认15次)
                        if (needDiscard(msgExt, transactionCheckMax) || needSkip(msgExt)) {
                            listener.resolveDiscardMsg(msgExt);
                            newOffset = i + 1;
                            i++;
                            continue;
                        }

                        // 如果存储时间大于开始时间，不处理
                        if (msgExt.getStoreTimestamp() >= startTime) {
                            log.debug("Fresh stored. the miss offset={}, check it later, store={}", i, new Date(msgExt.getStoreTimestamp()));
                            break;
                        }

                        long valueOfCurrentMinusBorn = System.currentTimeMillis() - msgExt.getBornTimestamp();  //消息已存储时间
                        long checkImmunityTime = transactionTimeout; //立即检测事务消息的时间
                        String checkImmunityTimeStr = msgExt.getUserProperty(MessageConst.PROPERTY_CHECK_IMMUNITY_TIME_IN_SECONDS); //获取消息的最晚回查时间
                        if (null != checkImmunityTimeStr) {
                            checkImmunityTime = getImmunityTime(checkImmunityTimeStr, transactionTimeout);
                            // 如果未到回查时间，不处理
                            if (valueOfCurrentMinusBorn < checkImmunityTime) {
                                if (checkPrepareQueueOffset(removeMap, doneOpOffset, msgExt)) {
                                    newOffset = i + 1;
                                    i++;
                                    continue;
                                }
                            }
                        } else {
                            if ((0 <= valueOfCurrentMinusBorn) && (valueOfCurrentMinusBorn < checkImmunityTime)) {
                                log.debug("New arrived, the miss offset={}, check it later checkImmunity={}, born={}", i, checkImmunityTime, new Date(msgExt.getBornTimestamp()));
                                break;
                            }
                        }

                        // 获取消息列表
                        List<MessageExt> opMsg = pullResult.getMsgFoundList();
                        // 判断是否需要进行回查
                        boolean isNeedCheck = (opMsg == null && valueOfCurrentMinusBorn > checkImmunityTime) || (opMsg != null && (opMsg.get(opMsg.size() - 1).getBornTimestamp() - startTime > transactionTimeout)) || (valueOfCurrentMinusBorn <= -1);
                        if (isNeedCheck) {
                            // 消息重新put到halfQueue中
                            if (!putBackHalfMsgQueue(msgExt, i)) {
                                continue;
                            }
                            // 执行回查
                            listener.resolveHalfMsg(msgExt);
                        } else {
                            pullResult = fillOpRemoveMap(removeMap, opQueue, pullResult.getNextBeginOffset(), halfOffset, doneOpOffset);
                            log.debug("The miss offset:{} in messageQueue:{} need to get more opMsg, result is:{}", i, messageQueue, pullResult);
                            continue;
                        }
                    }
                    newOffset = i + 1;
                    i++;
                }
                if (newOffset != halfOffset) {
                    // 更新halfQueue消费进度
                    transactionalMessageBridge.updateConsumeOffset(messageQueue, newOffset);
                }
                long newOpOffset = calculateOpOffset(doneOpOffset, opOffset);
                if (newOpOffset != opOffset) {
                    // 更新opQueue消费进度
                    transactionalMessageBridge.updateConsumeOffset(opQueue, newOpOffset);
                }
            }
        } catch (Throwable e) {
            log.error("Check error", e);
        }

    }

    private long getImmunityTime(String checkImmunityTimeStr, long transactionTimeout) {
        long checkImmunityTime;

        checkImmunityTime = getLong(checkImmunityTimeStr);
        if (-1 == checkImmunityTime) {
            checkImmunityTime = transactionTimeout;
        } else {
            checkImmunityTime *= 1000;
        }
        return checkImmunityTime;
    }

    /**
     * 从当前处理进度依次拉取32条消息，过滤掉已经处理消息。
     *
     * @param removeMap      已处理过的消息
     * @param opQueue        opQueue Op message queue.
     * @param pullOffsetOfOp opQueue 的拉取位置
     * @param miniOffset     当前halfQueue的消费位置
     * @param doneOpOffset   opQueue中已处理过的消息
     * @return Op message result.
     */
    private PullResult fillOpRemoveMap(HashMap<Long, Long> removeMap, MessageQueue opQueue, long pullOffsetOfOp, long miniOffset, List<Long> doneOpOffset) {
        // step1 : 获取op消息
        PullResult pullResult = pullOpMsg(opQueue, pullOffsetOfOp, 32);
        if (null == pullResult) {
            return null;
        }
        // step2 : 判断状态
        if (pullResult.getPullStatus() == PullStatus.OFFSET_ILLEGAL || pullResult.getPullStatus() == PullStatus.NO_MATCHED_MSG) {
            log.warn("The miss op offset={} in queue={} is illegal, pullResult={}", pullOffsetOfOp, opQueue, pullResult);
            transactionalMessageBridge.updateConsumeOffset(opQueue, pullResult.getNextBeginOffset());
            return pullResult;
        } else if (pullResult.getPullStatus() == PullStatus.NO_NEW_MSG) { //没有新消息
            log.warn("The miss op offset={} in queue={} is NO_NEW_MSG, pullResult={}", pullOffsetOfOp, opQueue, pullResult);
            return pullResult;
        }
        List<MessageExt> opMsg = pullResult.getMsgFoundList();
        if (opMsg == null) {
            log.warn("The miss op offset={} in queue={} is empty, pullResult={}", pullOffsetOfOp, opQueue, pullResult);
            return pullResult;
        }
        // step3 : 遍历所有消息
        for (MessageExt opMessageExt : opMsg) {
            // 获取opQueue的offset
            Long queueOffset = getLong(new String(opMessageExt.getBody(), TransactionalMessageUtil.charset));
            log.debug("Topic: {} tags: {}, OpOffset: {}, HalfOffset: {}", opMessageExt.getTopic(), opMessageExt.getTags(), opMessageExt.getQueueOffset(), queueOffset);
            if (TransactionalMessageUtil.REMOVETAG.equals(opMessageExt.getTags())) {
                if (queueOffset < miniOffset) {
                    // 正常情况，opQueue里的消息在halfQueue中offset要小于当前halfQueue的offset
                    doneOpOffset.add(opMessageExt.getQueueOffset());
                } else {
                    // 消息已经存储到opQueue中，但是halfQueue中的offset还没有更新
                    // 保存到removeMap中，防止后续重复进行回查操作
                    removeMap.put(queueOffset, opMessageExt.getQueueOffset());
                }
            } else {
                log.error("Found a illegal tag in opMessageExt= {} ", opMessageExt);
            }
        }
        log.debug("Remove map: {}", removeMap);
        log.debug("Done op list: {}", doneOpOffset);
        return pullResult;
    }

    /**
     * If return true, skip this msg
     *
     * @param removeMap    Op message map to determine whether a half message was responded by producer.
     * @param doneOpOffset Op Message which has been checked.
     * @param msgExt       Half message
     * @return Return true if put success, otherwise return false.
     */
    private boolean checkPrepareQueueOffset(HashMap<Long, Long> removeMap, List<Long> doneOpOffset, MessageExt msgExt) {
        String prepareQueueOffsetStr = msgExt.getUserProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED_QUEUE_OFFSET);
        if (null == prepareQueueOffsetStr) {
            return putImmunityMsgBackToHalfQueue(msgExt);
        } else {
            long prepareQueueOffset = getLong(prepareQueueOffsetStr);
            if (-1 == prepareQueueOffset) {
                return false;
            } else {
                if (removeMap.containsKey(prepareQueueOffset)) {
                    long tmpOpOffset = removeMap.remove(prepareQueueOffset);
                    doneOpOffset.add(tmpOpOffset);
                    return true;
                } else {
                    return putImmunityMsgBackToHalfQueue(msgExt);
                }
            }
        }
    }

    /**
     * Write messageExt to Half topic again
     *
     * @param messageExt Message will be write back to queue
     * @return Put result can used to determine the specific results of storage.
     */
    private PutMessageResult putBackToHalfQueueReturnResult(MessageExt messageExt) {
        PutMessageResult putMessageResult = null;
        try {
            MessageExtBrokerInner msgInner = transactionalMessageBridge.renewHalfMessageInner(messageExt);
            putMessageResult = transactionalMessageBridge.putMessageReturnResult(msgInner);
        } catch (Exception e) {
            log.warn("PutBackToHalfQueueReturnResult error", e);
        }
        return putMessageResult;
    }

    private boolean putImmunityMsgBackToHalfQueue(MessageExt messageExt) {
        MessageExtBrokerInner msgInner = transactionalMessageBridge.renewImmunityHalfMessageInner(messageExt);
        return transactionalMessageBridge.putMessage(msgInner);
    }

    /**
     * Read half message from Half Topic
     *
     * @param mq     Target message queue, in this method, it means the half message queue.
     * @param offset Offset in the message queue.
     * @param nums   Pull message number.
     * @return Messages pulled from half message queue.
     */
    private PullResult pullHalfMsg(MessageQueue mq, long offset, int nums) {
        return transactionalMessageBridge.getHalfMessage(mq.getQueueId(), offset, nums);
    }

    /**
     * Read op message from Op Topic
     *
     * @param mq     Target Message Queue
     * @param offset Offset in the message queue
     * @param nums   Pull message number
     * @return Messages pulled from operate message queue.
     */
    private PullResult pullOpMsg(MessageQueue mq, long offset, int nums) {
        return transactionalMessageBridge.getOpMessage(mq.getQueueId(), offset, nums);
    }

    private Long getLong(String s) {
        long v = -1;
        try {
            v = Long.valueOf(s);
        } catch (Exception e) {
            log.error("GetLong error", e);
        }
        return v;

    }

    private Integer getInt(String s) {
        int v = -1;
        try {
            v = Integer.valueOf(s);
        } catch (Exception e) {
            log.error("GetInt error", e);
        }
        return v;

    }

    private long calculateOpOffset(List<Long> doneOffset, long oldOffset) {
        Collections.sort(doneOffset);
        long newOffset = oldOffset;
        for (int i = 0; i < doneOffset.size(); i++) {
            if (doneOffset.get(i) == newOffset) {
                newOffset++;
            } else {
                break;
            }
        }
        return newOffset;

    }

    private MessageQueue getOpQueue(MessageQueue messageQueue) {
        MessageQueue opQueue = opQueueMap.get(messageQueue);
        if (opQueue == null) {
            opQueue = new MessageQueue(TransactionalMessageUtil.buildOpTopic(), messageQueue.getBrokerName(), messageQueue.getQueueId());
            opQueueMap.put(messageQueue, opQueue);
        }
        return opQueue;

    }

    private GetResult getHalfMsg(MessageQueue messageQueue, long offset) {
        GetResult getResult = new GetResult();

        PullResult result = pullHalfMsg(messageQueue, offset, PULL_MSG_RETRY_NUMBER);
        getResult.setPullResult(result);
        List<MessageExt> messageExts = result.getMsgFoundList();
        if (messageExts == null) {
            return getResult;
        }
        getResult.setMsg(messageExts.get(0));
        return getResult;
    }

    private OperationResult getHalfMessageByOffset(long commitLogOffset) {
        OperationResult response = new OperationResult();
        MessageExt messageExt = this.transactionalMessageBridge.lookMessageByOffset(commitLogOffset);
        if (messageExt != null) {
            response.setPrepareMessage(messageExt);
            response.setResponseCode(ResponseCode.SUCCESS);
        } else {
            response.setResponseCode(ResponseCode.SYSTEM_ERROR);
            response.setResponseRemark("Find prepared transaction message failed");
        }
        return response;
    }

    @Override
    public boolean deletePrepareMessage(MessageExt msgExt) {
        if (this.transactionalMessageBridge.putOpMessage(msgExt, TransactionalMessageUtil.REMOVETAG)) {
            log.debug("Transaction op message write successfully. messageId={}, queueId={} msgExt:{}", msgExt.getMsgId(), msgExt.getQueueId(), msgExt);
            return true;
        } else {
            log.error("Transaction op message write failed. messageId is {}, queueId is {}", msgExt.getMsgId(), msgExt.getQueueId());
            return false;
        }
    }

    @Override
    public OperationResult commitMessage(EndTransactionRequestHeader requestHeader) {
        return getHalfMessageByOffset(requestHeader.getCommitLogOffset());
    }

    @Override
    public OperationResult rollbackMessage(EndTransactionRequestHeader requestHeader) {
        return getHalfMessageByOffset(requestHeader.getCommitLogOffset());
    }

    @Override
    public boolean open() {
        return true;
    }

    @Override
    public void close() {

    }

}
