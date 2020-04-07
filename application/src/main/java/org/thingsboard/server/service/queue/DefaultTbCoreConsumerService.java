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
package org.thingsboard.server.service.queue;

import akka.actor.ActorRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.rule.engine.api.RpcError;
import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.TbActorMsg;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TbMsgCallback;
import org.thingsboard.server.gen.transport.TransportProtos.DeviceStateServiceMsgProto;
import org.thingsboard.server.gen.transport.TransportProtos.FromDeviceRPCResponseProto;
import org.thingsboard.server.gen.transport.TransportProtos.LocalSubscriptionServiceMsgProto;
import org.thingsboard.server.gen.transport.TransportProtos.SubscriptionMgrMsgProto;
import org.thingsboard.server.gen.transport.TransportProtos.TbAttributeUpdateProto;
import org.thingsboard.server.gen.transport.TransportProtos.TbSubscriptionCloseProto;
import org.thingsboard.server.gen.transport.TransportProtos.TbTimeSeriesUpdateProto;
import org.thingsboard.server.gen.transport.TransportProtos.ToCoreMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToCoreNotificationMsg;
import org.thingsboard.server.gen.transport.TransportProtos.TransportToDeviceActorMsg;
import org.thingsboard.server.queue.TbQueueConsumer;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.discovery.PartitionChangeEvent;
import org.thingsboard.server.queue.provider.TbCoreQueueFactory;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.encoding.DataDecodingEncodingService;
import org.thingsboard.server.service.queue.processing.AbstractConsumerService;
import org.thingsboard.server.service.rpc.FromDeviceRpcResponse;
import org.thingsboard.server.service.rpc.TbCoreDeviceRpcService;
import org.thingsboard.server.service.state.DeviceStateService;
import org.thingsboard.server.service.subscription.SubscriptionManagerService;
import org.thingsboard.server.service.subscription.TbLocalSubscriptionService;
import org.thingsboard.server.service.subscription.TbSubscriptionUtils;
import org.thingsboard.server.service.transport.msg.TransportToDeviceActorMsgWrapper;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@TbCoreComponent
@Slf4j
public class DefaultTbCoreConsumerService extends AbstractConsumerService<ToCoreNotificationMsg> implements TbCoreConsumerService {

    @Value("${queue.core.poll-interval}")
    private long pollDuration;
    @Value("${queue.core.pack-processing-timeout}")
    private long packProcessingTimeout;
    @Value("${queue.core.stats.enabled:false}")
    private boolean statsEnabled;

    private final TbQueueConsumer<TbProtoQueueMsg<ToCoreMsg>> mainConsumer;
    private final DeviceStateService stateService;
    private final TbLocalSubscriptionService localSubscriptionService;
    private final SubscriptionManagerService subscriptionManagerService;
    private final TbCoreDeviceRpcService tbCoreDeviceRpcService;
    private final TbCoreConsumerStats stats = new TbCoreConsumerStats();

    public DefaultTbCoreConsumerService(TbCoreQueueFactory tbCoreQueueFactory, ActorSystemContext actorContext,
                                        DeviceStateService stateService, TbLocalSubscriptionService localSubscriptionService,
                                        SubscriptionManagerService subscriptionManagerService, DataDecodingEncodingService encodingService,
                                        TbCoreDeviceRpcService tbCoreDeviceRpcService) {
        super(actorContext, encodingService, tbCoreQueueFactory.createToCoreNotificationsMsgConsumer());
        this.mainConsumer = tbCoreQueueFactory.createToCoreMsgConsumer();
        this.stateService = stateService;
        this.localSubscriptionService = localSubscriptionService;
        this.subscriptionManagerService = subscriptionManagerService;
        this.tbCoreDeviceRpcService = tbCoreDeviceRpcService;
    }

    @PostConstruct
    public void init() {
        super.init("tb-core-consumer", "tb-core-notifications-consumer");
    }

    @Override
    public void onApplicationEvent(PartitionChangeEvent partitionChangeEvent) {
        if (partitionChangeEvent.getServiceType().equals(getServiceType())) {
            log.info("Subscribing to partitions: {}", partitionChangeEvent.getPartitions());
            this.mainConsumer.subscribe(partitionChangeEvent.getPartitions());
        }
    }

    @Override
    protected void launchMainConsumers() {
        consumersExecutor.submit(() -> {
            while (!stopped) {
                try {
                    List<TbProtoQueueMsg<ToCoreMsg>> msgs = mainConsumer.poll(pollDuration);
                    if (msgs.isEmpty()) {
                        Thread.sleep(pollDuration);
                        continue;
                    }
                    ConcurrentMap<UUID, TbProtoQueueMsg<ToCoreMsg>> pendingMap = msgs.stream().collect(
                            Collectors.toConcurrentMap(s -> UUID.randomUUID(), Function.identity()));
                    ConcurrentMap<UUID, TbProtoQueueMsg<ToCoreMsg>> failedMap = new ConcurrentHashMap<>();
                    CountDownLatch processingTimeoutLatch = new CountDownLatch(1);
                    pendingMap.forEach((id, msg) -> {
                        log.info("[{}] Creating main callback for message: {}", id, msg.getValue());
                        TbMsgCallback callback = new MsgPackCallback<>(id, processingTimeoutLatch, pendingMap, new ConcurrentHashMap<>(), failedMap);
                        try {
                            ToCoreMsg toCoreMsg = msg.getValue();
                            if (toCoreMsg.hasToSubscriptionMgrMsg()) {
                                log.trace("[{}] Forwarding message to subscription manager service {}", id, toCoreMsg.getToSubscriptionMgrMsg());
                                forwardToSubMgrService(toCoreMsg.getToSubscriptionMgrMsg(), callback);
                            } else if (toCoreMsg.hasToDeviceActorMsg()) {
                                log.trace("[{}] Forwarding message to device actor {}", id, toCoreMsg.getToDeviceActorMsg());
                                forwardToDeviceActor(toCoreMsg.getToDeviceActorMsg(), callback);
                            } else if (toCoreMsg.hasDeviceStateServiceMsg()) {
                                log.trace("[{}] Forwarding message to state service {}", id, toCoreMsg.getDeviceStateServiceMsg());
                                forwardToStateService(toCoreMsg.getDeviceStateServiceMsg(), callback);
                            } else if (toCoreMsg.getToDeviceActorNotificationMsg() != null && !toCoreMsg.getToDeviceActorNotificationMsg().isEmpty()) {
                                Optional<TbActorMsg> actorMsg = encodingService.decode(toCoreMsg.getToDeviceActorNotificationMsg().toByteArray());
                                if (actorMsg.isPresent()) {
                                    log.trace("[{}] Forwarding message to App Actor {}", id, actorMsg.get());
                                    actorContext.getAppActor().tell(actorMsg.get(), ActorRef.noSender());
                                }
                                callback.onSuccess();
                            }
                        } catch (Throwable e) {
                            log.warn("[{}] Failed to process message: {}", id, msg, e);
                            callback.onFailure(e);
                        }
                    });
                    if (!processingTimeoutLatch.await(packProcessingTimeout, TimeUnit.MILLISECONDS)) {
                        pendingMap.forEach((id, msg) -> log.warn("[{}] Timeout to process message: {}", id, msg.getValue()));
                        failedMap.forEach((id, msg) -> log.warn("[{}] Failed to process message: {}", id, msg.getValue()));
                    }
                    mainConsumer.commit();
                } catch (Exception e) {
                    if (!stopped) {
                        log.warn("Failed to obtain messages from queue.", e);
                        try {
                            Thread.sleep(pollDuration);
                        } catch (InterruptedException e2) {
                            log.trace("Failed to wait until the server has capacity to handle new requests", e2);
                        }
                    }
                }
            }
            log.info("TB Core Consumer stopped.");
        });
    }

    @Override
    protected ServiceType getServiceType() {
        return ServiceType.TB_CORE;
    }

    @Override
    protected long getNotificationPollDuration() {
        return pollDuration;
    }

    @Override
    protected long getNotificationPackProcessingTimeout() {
        return packProcessingTimeout;
    }

    @Override
    protected void handleNotification(UUID id, TbProtoQueueMsg<ToCoreNotificationMsg> msg, TbMsgCallback callback) {
        ToCoreNotificationMsg toCoreMsg = msg.getValue();
        if (toCoreMsg.hasToLocalSubscriptionServiceMsg()) {
            log.trace("[{}] Forwarding message to local subscription service {}", id, toCoreMsg.getToLocalSubscriptionServiceMsg());
            forwardToLocalSubMgrService(toCoreMsg.getToLocalSubscriptionServiceMsg(), callback);
        } else if (toCoreMsg.hasFromDeviceRpcResponse()) {
            log.trace("[{}] Forwarding message to RPC service {}", id, toCoreMsg.getFromDeviceRpcResponse());
            forwardToCoreRpcService(toCoreMsg.getFromDeviceRpcResponse(), callback);
        } else if (toCoreMsg.getComponentLifecycleMsg() != null && !toCoreMsg.getComponentLifecycleMsg().isEmpty()) {
            Optional<TbActorMsg> actorMsg = encodingService.decode(toCoreMsg.getComponentLifecycleMsg().toByteArray());
            if (actorMsg.isPresent()) {
                log.trace("[{}] Forwarding message to App Actor {}", id, actorMsg.get());
                actorContext.getAppActor().tell(actorMsg.get(), ActorRef.noSender());
            }
            callback.onSuccess();
        }
    }

    private void forwardToCoreRpcService(FromDeviceRPCResponseProto proto, TbMsgCallback callback) {
        RpcError error = proto.getError() > 0 ? RpcError.values()[proto.getError()] : null;
        FromDeviceRpcResponse response = new FromDeviceRpcResponse(new UUID(proto.getRequestIdMSB(), proto.getRequestIdLSB())
                , proto.getResponse(), error);
        tbCoreDeviceRpcService.processRpcResponseFromRuleEngine(response);
        callback.onSuccess();
    }

    @Scheduled(fixedDelayString = "${queue.core.stats.print-interval-ms}")
    public void printStats() {
        if (statsEnabled) {
            stats.printStats();
        }
    }

    private void forwardToLocalSubMgrService(LocalSubscriptionServiceMsgProto msg, TbMsgCallback callback) {
        if (msg.hasSubUpdate()) {
            localSubscriptionService.onSubscriptionUpdate(msg.getSubUpdate().getSessionId(), TbSubscriptionUtils.fromProto(msg.getSubUpdate()), callback);
        } else {
            throwNotHandled(msg, callback);
        }
    }

    private void forwardToSubMgrService(SubscriptionMgrMsgProto msg, TbMsgCallback callback) {
        if (msg.hasAttributeSub()) {
            subscriptionManagerService.addSubscription(TbSubscriptionUtils.fromProto(msg.getAttributeSub()), callback);
        } else if (msg.hasTelemetrySub()) {
            subscriptionManagerService.addSubscription(TbSubscriptionUtils.fromProto(msg.getTelemetrySub()), callback);
        } else if (msg.hasSubClose()) {
            TbSubscriptionCloseProto closeProto = msg.getSubClose();
            subscriptionManagerService.cancelSubscription(closeProto.getSessionId(), closeProto.getSubscriptionId(), callback);
        } else if (msg.hasTsUpdate()) {
            TbTimeSeriesUpdateProto proto = msg.getTsUpdate();
            subscriptionManagerService.onTimeSeriesUpdate(
                    new TenantId(new UUID(proto.getTenantIdMSB(), proto.getTenantIdLSB())),
                    TbSubscriptionUtils.toEntityId(proto.getEntityType(), proto.getEntityIdMSB(), proto.getEntityIdLSB()),
                    TbSubscriptionUtils.toTsKvEntityList(proto.getDataList()), callback);
        } else if (msg.hasAttrUpdate()) {
            TbAttributeUpdateProto proto = msg.getAttrUpdate();
            subscriptionManagerService.onAttributesUpdate(
                    new TenantId(new UUID(proto.getTenantIdMSB(), proto.getTenantIdLSB())),
                    TbSubscriptionUtils.toEntityId(proto.getEntityType(), proto.getEntityIdMSB(), proto.getEntityIdLSB()),
                    proto.getScope(), TbSubscriptionUtils.toAttributeKvList(proto.getDataList()), callback);
        } else {
            throwNotHandled(msg, callback);
        }
    }

    private void forwardToStateService(DeviceStateServiceMsgProto deviceStateServiceMsg, TbMsgCallback callback) {
        if (statsEnabled) {
            stats.log(deviceStateServiceMsg);
        }
        stateService.onQueueMsg(deviceStateServiceMsg, callback);
    }

    private void forwardToDeviceActor(TransportToDeviceActorMsg toDeviceActorMsg, TbMsgCallback callback) {
        if (statsEnabled) {
            stats.log(toDeviceActorMsg);
        }
        actorContext.getAppActor().tell(new TransportToDeviceActorMsgWrapper(toDeviceActorMsg, callback), ActorRef.noSender());
    }

    private void throwNotHandled(Object msg, TbMsgCallback callback) {
        log.warn("Message not handled: {}", msg);
        callback.onFailure(new RuntimeException("Message not handled!"));
    }

    @Override
    protected void stopMainConsumers() {
        if (mainConsumer != null) {
            mainConsumer.unsubscribe();
        }
    }

}
