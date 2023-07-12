/**
 * Copyright © 2016-2023 The Thingsboard Authors
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
package org.thingsboard.server.edge.imitator;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.AbstractMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.thingsboard.edge.rpc.EdgeGrpcClient;
import org.thingsboard.edge.rpc.EdgeRpcClient;
import org.thingsboard.server.gen.edge.v1.AdminSettingsUpdateMsg;
import org.thingsboard.server.gen.edge.v1.AlarmUpdateMsg;
import org.thingsboard.server.gen.edge.v1.AssetProfileUpdateMsg;
import org.thingsboard.server.gen.edge.v1.AssetUpdateMsg;
import org.thingsboard.server.gen.edge.v1.CustomerUpdateMsg;
import org.thingsboard.server.gen.edge.v1.DashboardUpdateMsg;
import org.thingsboard.server.gen.edge.v1.DeviceCredentialsRequestMsg;
import org.thingsboard.server.gen.edge.v1.DeviceCredentialsUpdateMsg;
import org.thingsboard.server.gen.edge.v1.DeviceProfileUpdateMsg;
import org.thingsboard.server.gen.edge.v1.DeviceRpcCallMsg;
import org.thingsboard.server.gen.edge.v1.DeviceUpdateMsg;
import org.thingsboard.server.gen.edge.v1.DownlinkMsg;
import org.thingsboard.server.gen.edge.v1.DownlinkResponseMsg;
import org.thingsboard.server.gen.edge.v1.EdgeConfiguration;
import org.thingsboard.server.gen.edge.v1.EntityDataProto;
import org.thingsboard.server.gen.edge.v1.EntityViewUpdateMsg;
import org.thingsboard.server.gen.edge.v1.OtaPackageUpdateMsg;
import org.thingsboard.server.gen.edge.v1.QueueUpdateMsg;
import org.thingsboard.server.gen.edge.v1.RelationUpdateMsg;
import org.thingsboard.server.gen.edge.v1.RuleChainMetadataUpdateMsg;
import org.thingsboard.server.gen.edge.v1.RuleChainUpdateMsg;
import org.thingsboard.server.gen.edge.v1.TenantProfileUpdateMsg;
import org.thingsboard.server.gen.edge.v1.TenantUpdateMsg;
import org.thingsboard.server.gen.edge.v1.UplinkMsg;
import org.thingsboard.server.gen.edge.v1.UplinkResponseMsg;
import org.thingsboard.server.gen.edge.v1.UserCredentialsUpdateMsg;
import org.thingsboard.server.gen.edge.v1.UserUpdateMsg;
import org.thingsboard.server.gen.edge.v1.WidgetTypeUpdateMsg;
import org.thingsboard.server.gen.edge.v1.WidgetsBundleUpdateMsg;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
public class EdgeImitator {

    public static final int TIMEOUT_IN_SECONDS = 30;

    private String routingKey;
    private String routingSecret;

    private EdgeRpcClient edgeRpcClient;

    private final Lock lock = new ReentrantLock();

    private CountDownLatch messagesLatch;
    private CountDownLatch responsesLatch;
    private List<Class<? extends AbstractMessage>> ignoredTypes;

    @Setter
    private boolean randomFailuresOnTimeseriesDownlink = false;
    @Setter
    private double failureProbability = 0.0;

    @Getter
    private EdgeConfiguration configuration;
    @Getter
    private List<AbstractMessage> downlinkMsgs;

    @Getter
    private UplinkResponseMsg latestResponseMsg;

    public EdgeImitator(String host, int port, String routingKey, String routingSecret) throws NoSuchFieldException, IllegalAccessException {
        edgeRpcClient = new EdgeGrpcClient();
        messagesLatch = new CountDownLatch(0);
        responsesLatch = new CountDownLatch(0);
        downlinkMsgs = new ArrayList<>();
        ignoredTypes = new ArrayList<>();
        this.routingKey = routingKey;
        this.routingSecret = routingSecret;
        updateEdgeClientFields("rpcHost", host);
        updateEdgeClientFields("rpcPort", port);
        updateEdgeClientFields("timeoutSecs", 3);
        updateEdgeClientFields("keepAliveTimeSec", 300);
        updateEdgeClientFields("keepAliveTimeoutSec", 5);
        updateEdgeClientFields("maxInboundMessageSize", 4194304);
    }

    private void updateEdgeClientFields(String fieldName, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field fieldToSet = edgeRpcClient.getClass().getDeclaredField(fieldName);
        fieldToSet.setAccessible(true);
        fieldToSet.set(edgeRpcClient, value);
        fieldToSet.setAccessible(false);
    }

    public void connect() {
        edgeRpcClient.connect(routingKey, routingSecret,
                this::onUplinkResponse,
                this::onEdgeUpdate,
                this::onDownlink,
                this::onClose);

        edgeRpcClient.sendSyncRequestMsg(true);
    }

    public void disconnect() throws InterruptedException {
        edgeRpcClient.disconnect(false);
    }

    public void sendUplinkMsg(UplinkMsg uplinkMsg) {
        edgeRpcClient.sendUplinkMsg(uplinkMsg);
    }

    private void onUplinkResponse(UplinkResponseMsg msg) {
        log.info("onUplinkResponse: {}", msg);
        latestResponseMsg = msg;
        responsesLatch.countDown();
    }

    private void onEdgeUpdate(EdgeConfiguration edgeConfiguration) {
        this.configuration = edgeConfiguration;
    }

    private void onDownlink(DownlinkMsg downlinkMsg) {
        ListenableFuture<List<Void>> future = processDownlinkMsg(downlinkMsg);
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable List<Void> result) {
                DownlinkResponseMsg downlinkResponseMsg = DownlinkResponseMsg.newBuilder()
                        .setDownlinkMsgId(downlinkMsg.getDownlinkMsgId())
                        .setSuccess(true).build();
                edgeRpcClient.sendDownlinkResponseMsg(downlinkResponseMsg);
            }

            @Override
            public void onFailure(Throwable t) {
                DownlinkResponseMsg downlinkResponseMsg = DownlinkResponseMsg.newBuilder()
                        .setDownlinkMsgId(downlinkMsg.getDownlinkMsgId())
                        .setSuccess(false).setErrorMsg(t.getMessage()).build();
                edgeRpcClient.sendDownlinkResponseMsg(downlinkResponseMsg);
            }
        }, MoreExecutors.directExecutor());
    }

    private void onClose(Exception e) {
        log.info("onClose: {}", e.getMessage());
    }

    private ListenableFuture<List<Void>> processDownlinkMsg(DownlinkMsg downlinkMsg) {
        List<ListenableFuture<Void>> result = new ArrayList<>();
        if (downlinkMsg.getAdminSettingsUpdateMsgCount() > 0) {
            for (AdminSettingsUpdateMsg adminSettingsUpdateMsg : downlinkMsg.getAdminSettingsUpdateMsgList()) {
                result.add(saveDownlinkMsg(adminSettingsUpdateMsg));
            }
        }
        if (downlinkMsg.getDeviceProfileUpdateMsgCount() > 0) {
            for (DeviceProfileUpdateMsg deviceProfileUpdateMsg : downlinkMsg.getDeviceProfileUpdateMsgList()) {
                result.add(saveDownlinkMsg(deviceProfileUpdateMsg));
            }
        }
        if (downlinkMsg.getDeviceUpdateMsgCount() > 0) {
            for (DeviceUpdateMsg deviceUpdateMsg : downlinkMsg.getDeviceUpdateMsgList()) {
                result.add(saveDownlinkMsg(deviceUpdateMsg));
            }
        }
        if (downlinkMsg.getDeviceCredentialsUpdateMsgCount() > 0) {
            for (DeviceCredentialsUpdateMsg deviceCredentialsUpdateMsg : downlinkMsg.getDeviceCredentialsUpdateMsgList()) {
                result.add(saveDownlinkMsg(deviceCredentialsUpdateMsg));
            }
        }
        if (downlinkMsg.getAssetProfileUpdateMsgCount() > 0) {
            for (AssetProfileUpdateMsg assetProfileUpdateMsg : downlinkMsg.getAssetProfileUpdateMsgList()) {
                result.add(saveDownlinkMsg(assetProfileUpdateMsg));
            }
        }
        if (downlinkMsg.getAssetUpdateMsgCount() > 0) {
            for (AssetUpdateMsg assetUpdateMsg : downlinkMsg.getAssetUpdateMsgList()) {
                result.add(saveDownlinkMsg(assetUpdateMsg));
            }
        }
        if (downlinkMsg.getRuleChainUpdateMsgCount() > 0) {
            for (RuleChainUpdateMsg ruleChainUpdateMsg : downlinkMsg.getRuleChainUpdateMsgList()) {
                result.add(saveDownlinkMsg(ruleChainUpdateMsg));
            }
        }
        if (downlinkMsg.getRuleChainMetadataUpdateMsgCount() > 0) {
            for (RuleChainMetadataUpdateMsg ruleChainMetadataUpdateMsg : downlinkMsg.getRuleChainMetadataUpdateMsgList()) {
                result.add(saveDownlinkMsg(ruleChainMetadataUpdateMsg));
            }
        }
        if (downlinkMsg.getDashboardUpdateMsgCount() > 0) {
            for (DashboardUpdateMsg dashboardUpdateMsg : downlinkMsg.getDashboardUpdateMsgList()) {
                result.add(saveDownlinkMsg(dashboardUpdateMsg));
            }
        }
        if (downlinkMsg.getRelationUpdateMsgCount() > 0) {
            for (RelationUpdateMsg relationUpdateMsg : downlinkMsg.getRelationUpdateMsgList()) {
                result.add(saveDownlinkMsg(relationUpdateMsg));
            }
        }
        if (downlinkMsg.getAlarmUpdateMsgCount() > 0) {
            for (AlarmUpdateMsg alarmUpdateMsg : downlinkMsg.getAlarmUpdateMsgList()) {
                result.add(saveDownlinkMsg(alarmUpdateMsg));
            }
        }
        if (downlinkMsg.getEntityDataCount() > 0) {
            for (EntityDataProto entityData : downlinkMsg.getEntityDataList()) {
                if (randomFailuresOnTimeseriesDownlink) {
                    if (getRandomBoolean()) {
                        result.add(Futures.immediateFailedFuture(new RuntimeException("Random failure. This is expected error for edge test")));
                    } else {
                        result.add(saveDownlinkMsg(entityData));
                    }
                } else {
                    result.add(saveDownlinkMsg(entityData));
                }
            }
        }
        if (downlinkMsg.getEntityViewUpdateMsgCount() > 0) {
            for (EntityViewUpdateMsg entityViewUpdateMsg : downlinkMsg.getEntityViewUpdateMsgList()) {
                result.add(saveDownlinkMsg(entityViewUpdateMsg));
            }
        }
        if (downlinkMsg.getCustomerUpdateMsgCount() > 0) {
            for (CustomerUpdateMsg customerUpdateMsg : downlinkMsg.getCustomerUpdateMsgList()) {
                result.add(saveDownlinkMsg(customerUpdateMsg));
            }
        }
        if (downlinkMsg.getWidgetsBundleUpdateMsgCount() > 0) {
            for (WidgetsBundleUpdateMsg widgetsBundleUpdateMsg : downlinkMsg.getWidgetsBundleUpdateMsgList()) {
                result.add(saveDownlinkMsg(widgetsBundleUpdateMsg));
            }
        }
        if (downlinkMsg.getWidgetTypeUpdateMsgCount() > 0) {
            for (WidgetTypeUpdateMsg widgetTypeUpdateMsg : downlinkMsg.getWidgetTypeUpdateMsgList()) {
                result.add(saveDownlinkMsg(widgetTypeUpdateMsg));
            }
        }
        if (downlinkMsg.getUserUpdateMsgCount() > 0) {
            for (UserUpdateMsg userUpdateMsg : downlinkMsg.getUserUpdateMsgList()) {
                result.add(saveDownlinkMsg(userUpdateMsg));
            }
        }
        if (downlinkMsg.getUserCredentialsUpdateMsgCount() > 0) {
            for (UserCredentialsUpdateMsg userCredentialsUpdateMsg : downlinkMsg.getUserCredentialsUpdateMsgList()) {
                result.add(saveDownlinkMsg(userCredentialsUpdateMsg));
            }
        }
        if (downlinkMsg.getDeviceRpcCallMsgCount() > 0) {
            for (DeviceRpcCallMsg deviceRpcCallMsg : downlinkMsg.getDeviceRpcCallMsgList()) {
                result.add(saveDownlinkMsg(deviceRpcCallMsg));
            }
        }
        if (downlinkMsg.getDeviceCredentialsRequestMsgCount() > 0) {
            for (DeviceCredentialsRequestMsg deviceCredentialsRequestMsg : downlinkMsg.getDeviceCredentialsRequestMsgList()) {
                result.add(saveDownlinkMsg(deviceCredentialsRequestMsg));
            }
        }
        if (downlinkMsg.getOtaPackageUpdateMsgCount() > 0) {
            for (OtaPackageUpdateMsg otaPackageUpdateMsg : downlinkMsg.getOtaPackageUpdateMsgList()) {
                result.add(saveDownlinkMsg(otaPackageUpdateMsg));
            }
        }
        if (downlinkMsg.getQueueUpdateMsgCount() > 0) {
            for (QueueUpdateMsg queueUpdateMsg : downlinkMsg.getQueueUpdateMsgList()) {
                result.add(saveDownlinkMsg(queueUpdateMsg));
            }
        }
        if (downlinkMsg.getTenantUpdateMsgCount() > 0) {
            for (TenantUpdateMsg tenantUpdateMsg : downlinkMsg.getTenantUpdateMsgList()) {
                result.add(saveDownlinkMsg(tenantUpdateMsg));
            }
        }
        if (downlinkMsg.getTenantProfileUpdateMsgCount() > 0) {
            for (TenantProfileUpdateMsg tenantProfileUpdateMsg : downlinkMsg.getTenantProfileUpdateMsgList()) {
                result.add(saveDownlinkMsg(tenantProfileUpdateMsg));
            }
        }
        if (downlinkMsg.hasEdgeConfiguration()) {
            result.add(saveDownlinkMsg(downlinkMsg.getEdgeConfiguration()));
        }

        return Futures.allAsList(result);
    }

    private boolean getRandomBoolean() {
        double randomValue = ThreadLocalRandom.current().nextDouble() * 100;
        return randomValue <= this.failureProbability;
    }

    private ListenableFuture<Void> saveDownlinkMsg(AbstractMessage message) {
        if (!ignoredTypes.contains(message.getClass())) {
            lock.lock();
            try {
                downlinkMsgs.add(message);
            } finally {
                lock.unlock();
            }
            messagesLatch.countDown();
        }
        return Futures.immediateFuture(null);
    }

    public boolean waitForMessages() throws InterruptedException {
        return waitForMessages(TIMEOUT_IN_SECONDS);
    }

    public boolean waitForMessages(int timeoutInSeconds) throws InterruptedException {
        return messagesLatch.await(timeoutInSeconds, TimeUnit.SECONDS);
    }

    public void expectMessageAmount(int messageAmount) {
        // clear downlinks
        downlinkMsgs.clear();

        messagesLatch = new CountDownLatch(messageAmount);
    }

    public boolean waitForResponses() throws InterruptedException {
        return responsesLatch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
    }

    public void expectResponsesAmount(int messageAmount) {
        responsesLatch = new CountDownLatch(messageAmount);
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractMessage> Optional<T> findMessageByType(Class<T> tClass) {
        Optional<T> result;
        lock.lock();
        try {
            result = (Optional<T>) downlinkMsgs.stream().filter(downlinkMsg -> downlinkMsg.getClass().isAssignableFrom(tClass)).findAny();
        } finally {
            lock.unlock();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractMessage> List<T> findAllMessagesByType(Class<T> tClass) {
        List<T> result;
        lock.lock();
        try {
            result = (List<T>) downlinkMsgs.stream().filter(downlinkMsg -> downlinkMsg.getClass().isAssignableFrom(tClass)).collect(Collectors.toList());
        } finally {
            lock.unlock();
        }
        return result;
    }

    public AbstractMessage getLatestMessage() {
        return downlinkMsgs.get(downlinkMsgs.size() - 1);
    }

    public void ignoreType(Class<? extends AbstractMessage> type) {
        ignoredTypes.add(type);
    }

    public void allowIgnoredTypes() {
        ignoredTypes.clear();
    }

}
