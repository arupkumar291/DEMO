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
package org.thingsboard.server.queue.pubsub;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.thingsboard.server.queue.TbQueueAdmin;
import org.thingsboard.server.queue.TbQueueConsumer;
import org.thingsboard.server.queue.TbQueueMsg;
import org.thingsboard.server.queue.TbQueueMsgDecoder;
import org.thingsboard.server.queue.TbQueueMsgHeaders;
import org.thingsboard.server.queue.common.DefaultTbQueueMsg;
import org.thingsboard.server.queue.common.DefaultTbQueueMsgHeaders;
import org.thingsboard.server.queue.discovery.TopicPartitionInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
public class TbPubSubConsumerTemplate<T extends TbQueueMsg> implements TbQueueConsumer<T> {

    private final Gson gson = new Gson();
    private final TbQueueAdmin admin;
    private final String topic;
    private final TbQueueMsgDecoder<T> decoder;
    private final TbPubSubSettings pubSubSettings;

    private volatile boolean subscribed;
    private volatile Set<TopicPartitionInfo> partitions;
    private volatile Set<String> subscriptionNames;
    private final List<AcknowledgeRequest> acknowledgeRequests = new CopyOnWriteArrayList<>();

    private ExecutorService consumerExecutor;
    private final SubscriberStub subscriber;


    public TbPubSubConsumerTemplate(TbQueueAdmin admin, TbPubSubSettings pubSubSettings, String topic, TbQueueMsgDecoder<T> decoder) {
        this.admin = admin;
        this.pubSubSettings = pubSubSettings;
        this.topic = topic;
        this.decoder = decoder;

        try {
            SubscriberStubSettings subscriberStubSettings =
                    SubscriberStubSettings.newBuilder()
                            .setCredentialsProvider(pubSubSettings.getCredentialsProvider())
                            .setTransportChannelProvider(
                                    SubscriberStubSettings.defaultGrpcTransportProviderBuilder()
                                            .setMaxInboundMessageSize(20 << 20)// 20MB
                                            .build())
                            .build();

            this.subscriber = GrpcSubscriberStub.create(subscriberStubSettings);
        } catch (IOException e) {
            log.error("Failed to create subscriber.", e);
            throw new RuntimeException("Failed to create subscriber.", e);
        }
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public void subscribe() {
        partitions = Collections.singleton(new TopicPartitionInfo(topic, null, null));
        subscribed = false;
    }

    @Override
    public void subscribe(Set<TopicPartitionInfo> partitions) {
        this.partitions = partitions;
        subscribed = false;
    }

    @Override
    public void unsubscribe() {
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }

        if (subscriber != null) {
            subscriber.close();
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
                subscriptionNames = partitions.stream().map(TopicPartitionInfo::getFullTopicName).collect(Collectors.toSet());
                subscriptionNames.forEach(admin::createTopicIfNotExists);
                consumerExecutor = Executors.newFixedThreadPool(subscriptionNames.size());
                subscribed = true;
            }
            List<ReceivedMessage> messages;
            try {
                messages = receiveMessages();
            } catch (ExecutionException | InterruptedException e) {
                log.error("Failed to receive messages", e);
                throw new RuntimeException("Failed to receive messages.", e);
            }
            if (!messages.isEmpty()) {
                List<T> result = new ArrayList<>();
                messages.forEach(msg -> {
                    try {
                        result.add(decode(msg.getMessage()));
                    } catch (InvalidProtocolBufferException e) {
                        log.error("Failed decode record: [{}]", msg);
                    }
                });
                return result;
            }

        }
        return Collections.emptyList();
    }

    @Override
    public void commit() {
        acknowledgeRequests.forEach(subscriber.acknowledgeCallable()::futureCall);
        acknowledgeRequests.clear();
    }

    private List<ReceivedMessage> receiveMessages() throws ExecutionException, InterruptedException {
        List<ApiFuture<List<ReceivedMessage>>> result = subscriptionNames.stream().map(subscriptionId -> {
            String subscriptionName = ProjectSubscriptionName.format(pubSubSettings.getProjectId(), subscriptionId);
            PullRequest pullRequest =
                    PullRequest.newBuilder()
                            .setMaxMessages(1000)
//                            .setReturnImmediately(false) // return immediately if messages are not available
                            .setSubscription(subscriptionName)
                            .build();

            ApiFuture<PullResponse> pullResponseApiFuture = subscriber.pullCallable().futureCall(pullRequest);

            return ApiFutures.transform(pullResponseApiFuture, pullResponse -> {
                if (pullResponse != null && !pullResponse.getReceivedMessagesList().isEmpty()) {
                    List<String> ackIds = new ArrayList<>();
                    for (ReceivedMessage message : pullResponse.getReceivedMessagesList()) {
                        ackIds.add(message.getAckId());
                    }
                    AcknowledgeRequest acknowledgeRequest =
                            AcknowledgeRequest.newBuilder()
                                    .setSubscription(subscriptionName)
                                    .addAllAckIds(ackIds)
                                    .build();

                    acknowledgeRequests.add(acknowledgeRequest);
                    return pullResponse.getReceivedMessagesList();
                }
                return null;
            }, consumerExecutor);

        }).collect(Collectors.toList());

        ApiFuture<List<ReceivedMessage>> transform = ApiFutures.transform(ApiFutures.allAsList(result), listMessages -> {
            if (!CollectionUtils.isEmpty(listMessages)) {
                return listMessages.stream().filter(Objects::nonNull).flatMap(List::stream).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }, consumerExecutor);

        return transform.get();
    }

    public T decode(PubsubMessage message) throws InvalidProtocolBufferException {
        DefaultTbQueueMsg msg = gson.fromJson(message.getData().toStringUtf8(), DefaultTbQueueMsg.class);
        TbQueueMsgHeaders headers = new DefaultTbQueueMsgHeaders();
        Map<String, byte[]> headerMap = gson.fromJson(message.getAttributesMap().get("headers"), new TypeToken<Map<String, byte[]>>() {
        }.getType());
        headerMap.forEach(headers::put);
        msg.setHeaders(headers);
        return decoder.decode(msg);
    }

}
