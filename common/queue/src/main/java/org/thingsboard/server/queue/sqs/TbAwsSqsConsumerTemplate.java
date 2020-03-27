/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.queue.sqs;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.queue.TbQueueAdmin;
import org.thingsboard.server.queue.TbQueueConsumer;
import org.thingsboard.server.queue.TbQueueMsg;
import org.thingsboard.server.queue.TbQueueMsgDecoder;
import org.thingsboard.server.queue.TbQueueMsgHeaders;
import org.thingsboard.server.queue.common.DefaultTbQueueMsgHeaders;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
public class TbAwsSqsConsumerTemplate<T extends TbQueueMsg> implements TbQueueConsumer<T> {

    private final Gson gson = new Gson();
    private final TbQueueAdmin admin;
    private final AmazonSQSAsync sqsClient;
    private final String topic;
    private final TbQueueMsgDecoder<T> decoder;

    private final List<AwsSqsMsgWrapper> messageList = new CopyOnWriteArrayList<>();
    private volatile boolean subscribed;
    private volatile Set<String> queueUrls;
    private volatile Set<TopicPartitionInfo> partitions;
    private ListeningExecutorService consumerExecutor;

    public TbAwsSqsConsumerTemplate(TbQueueAdmin admin, TbAwsSqsSettings sqsSettings, String topic, TbQueueMsgDecoder<T> decoder) {
        this.admin = admin;
        this.decoder = decoder;
        this.topic = topic;

        AWSCredentials awsCredentials = new BasicAWSCredentials(sqsSettings.getAccessKeyId(), sqsSettings.getSecretAccessKey());
        AWSStaticCredentialsProvider credProvider = new AWSStaticCredentialsProvider(awsCredentials);

        this.sqsClient = AmazonSQSAsyncClientBuilder.standard()
                .withCredentials(credProvider)
                .withRegion(sqsSettings.getRegion())
                .build();
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public void subscribe() {
        partitions = Collections.singleton(new TopicPartitionInfo(topic, null, null, true));
        subscribed = false;
    }

    @Override
    public void subscribe(Set<TopicPartitionInfo> partitions) {
        this.partitions = partitions;
        subscribed = false;
    }

    @Override
    public void unsubscribe() {
        if (sqsClient != null) {
            sqsClient.shutdown();
        }
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }
    }

    @Override
    public List<T> poll(long durationInMillis) {
        if (!subscribed && partitions == null) {
            try {
                Thread.sleep(durationInMillis);
            } catch (InterruptedException e) {
                log.debug("Failed to await subscription", e);
            }
        } else {
            if (!subscribed) {
                List<String> topicNames = partitions.stream().map(TopicPartitionInfo::getFullTopicName).collect(Collectors.toList());
                queueUrls = topicNames.stream().map(topic -> {
                            admin.createTopicIfNotExists(topic);
                            return getQueueUrl(topic);
                        }
                ).collect(Collectors.toSet());
                consumerExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(queueUrls.size()));
                subscribed = true;
            }

            if (!messageList.isEmpty()) {
                log.warn("Present {} non committed messages.", messageList.size());
                return Collections.emptyList();
            }

            List<ListenableFuture<AwsSqsMsgWrapper>> futureList = queueUrls.stream().map(url -> consumerExecutor.submit(() -> {
                ReceiveMessageRequest request = new ReceiveMessageRequest();
                request
                        .withWaitTimeSeconds((int) (durationInMillis / 1000))
                        .withMessageAttributeNames("headers")
                        .withQueueUrl(url)
                        .withMaxNumberOfMessages(10);
                return new AwsSqsMsgWrapper(url, sqsClient.receiveMessage(request).getMessages());
            })).collect(Collectors.toList());
            ListenableFuture<List<AwsSqsMsgWrapper>> futureResult = Futures.allAsList(futureList);
            try {
                List<AwsSqsMsgWrapper> messages =
                        futureResult.get().stream()
                                .filter(msg -> !msg.getMessages().isEmpty())
                                .collect(Collectors.toList());
                if (messages.size() > 0) {
                    messageList.addAll(messages);
                    return messages.stream()
                            .flatMap(msg -> msg.getMessages().stream())
                            .map(msg -> {
                                try {
                                    return decode(msg);
                                } catch (IOException e) {
                                    log.error("Failed to decode message: [{}]", msg);
                                    return null;
                                }
                            }).filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
            } catch (InterruptedException | ExecutionException e) {
                log.error("Failed to pool messages.", e);
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void commit() {
        messageList.forEach(msg ->
                consumerExecutor.submit(() -> {
                    List<DeleteMessageBatchRequestEntry> entries = msg.getMessages()
                            .stream()
                            .map(message -> new DeleteMessageBatchRequestEntry(message.getMessageId(), message.getReceiptHandle()))
                            .collect(Collectors.toList());
                    sqsClient.deleteMessageBatch(msg.getUrl(), entries);
                }));

        messageList.clear();
    }

    public T decode(Message message) throws InvalidProtocolBufferException {
        TbAwsSqsMsg msg = gson.fromJson(message.getBody(), TbAwsSqsMsg.class);
        TbQueueMsgHeaders headers = new DefaultTbQueueMsgHeaders();
        Map<String, byte[]> headerMap = gson.fromJson(message.getMessageAttributes().get("headers").getStringValue(), new TypeToken<Map<String, byte[]>>() {
        }.getType());
        headerMap.forEach(headers::put);
        msg.setHeaders(headers);
        return decoder.decode(msg);
    }

    @Data
    private static class AwsSqsMsgWrapper {
        private final String url;
        private final List<Message> messages;

        public AwsSqsMsgWrapper(String url, List<Message> messages) {
            this.url = url;
            this.messages = messages;
        }
    }

    private String getQueueUrl(String topic) {
        return sqsClient.getQueueUrl(topic.replaceAll("\\.", "_") + ".fifo").getQueueUrl();
    }
}
