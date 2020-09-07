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
package org.thingsboard.server.transport.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnAckVariableHeader;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttPubAckMessage;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.device.profile.MqttTopics;
import org.thingsboard.server.common.msg.EncryptionUtil;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.adaptor.AdaptorException;
import org.thingsboard.server.common.transport.auth.SessionInfoCreator;
import org.thingsboard.server.common.transport.auth.TransportDeviceInfo;
import org.thingsboard.server.common.transport.auth.ValidateDeviceCredentialsResponse;
import org.thingsboard.server.common.transport.service.DefaultTransportService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.SessionEvent;
import org.thingsboard.server.gen.transport.TransportProtos.SessionInfoProto;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceTokenRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceX509CertRequestMsg;
import org.thingsboard.server.transport.mqtt.adaptors.JsonV1MqttAdaptor;
import org.thingsboard.server.transport.mqtt.adaptors.JsonV2MqttAdaptor;
import org.thingsboard.server.transport.mqtt.adaptors.MqttTransportAdaptor;
import org.thingsboard.server.transport.mqtt.adaptors.ProtoMqttAdaptor;
import org.thingsboard.server.transport.mqtt.session.DeviceSessionCtx;
import org.thingsboard.server.transport.mqtt.session.GatewaySessionHandler;
import org.thingsboard.server.transport.mqtt.session.MqttTopicMatcher;
import org.thingsboard.server.transport.mqtt.util.SslUtil;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.CONNECTION_ACCEPTED;
import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD;
import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
import static io.netty.handler.codec.mqtt.MqttMessageType.CONNACK;
import static io.netty.handler.codec.mqtt.MqttMessageType.PINGRESP;
import static io.netty.handler.codec.mqtt.MqttMessageType.PUBACK;
import static io.netty.handler.codec.mqtt.MqttMessageType.SUBACK;
import static io.netty.handler.codec.mqtt.MqttMessageType.UNSUBACK;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_MOST_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.FAILURE;

/**
 * @author Andrew Shvayka
 */
@Slf4j
public class MqttTransportHandler extends ChannelInboundHandlerAdapter implements GenericFutureListener<Future<? super Void>>, SessionMsgListener {

    private static final MqttQoS MAX_SUPPORTED_QOS_LVL = AT_LEAST_ONCE;

    private final UUID sessionId;
    private final MqttTransportContext context;
    private final JsonV1MqttAdaptor jsonV1MqttAdaptor;
    private final JsonV2MqttAdaptor jsonV2MqttAdaptor;
    private final ProtoMqttAdaptor protoMqttAdaptor;
    private final TransportService transportService;
    private final SslHandler sslHandler;
    private final ConcurrentMap<MqttTopicMatcher, Integer> mqttQoSMap;

    private volatile SessionInfoProto sessionInfo;
    private volatile InetSocketAddress address;
    private volatile DeviceSessionCtx deviceSessionCtx;
    private volatile GatewaySessionHandler gatewaySessionHandler;

    MqttTransportHandler(MqttTransportContext context, SslHandler sslHandler) {
        this.sessionId = UUID.randomUUID();
        this.context = context;
        this.transportService = context.getTransportService();
        this.jsonV1MqttAdaptor = context.getJsonV1MqttAdaptor();
        this.jsonV2MqttAdaptor = context.getJsonV2MqttAdaptor();
        this.protoMqttAdaptor = context.getProtoMqttAdaptor();
        this.sslHandler = sslHandler;
        this.mqttQoSMap = new ConcurrentHashMap<>();
        this.deviceSessionCtx = new DeviceSessionCtx(sessionId, mqttQoSMap);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        log.trace("[{}] Processing msg: {}", sessionId, msg);
        try {
            if (msg instanceof MqttMessage) {
                processMqttMsg(ctx, (MqttMessage) msg);
            } else {
                ctx.close();
            }
        } finally {
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    private void processMqttMsg(ChannelHandlerContext ctx, MqttMessage msg) {
        address = (InetSocketAddress) ctx.channel().remoteAddress();
        if (msg.fixedHeader() == null) {
            log.info("[{}:{}] Invalid message received", address.getHostName(), address.getPort());
            processDisconnect(ctx);
            return;
        }
        deviceSessionCtx.setChannel(ctx);
        switch (msg.fixedHeader().messageType()) {
            case CONNECT:
                processConnect(ctx, (MqttConnectMessage) msg);
                break;
            case PUBLISH:
                processPublish(ctx, (MqttPublishMessage) msg);
                break;
            case SUBSCRIBE:
                processSubscribe(ctx, (MqttSubscribeMessage) msg);
                break;
            case UNSUBSCRIBE:
                processUnsubscribe(ctx, (MqttUnsubscribeMessage) msg);
                break;
            case PINGREQ:
                if (checkConnected(ctx, msg)) {
                    ctx.writeAndFlush(new MqttMessage(new MqttFixedHeader(PINGRESP, false, AT_MOST_ONCE, false, 0)));
                    transportService.reportActivity(sessionInfo);
                }
                break;
            case DISCONNECT:
                if (checkConnected(ctx, msg)) {
                    processDisconnect(ctx);
                }
                break;
            default:
                break;
        }
    }

    private void processPublish(ChannelHandlerContext ctx, MqttPublishMessage mqttMsg) {
        if (!checkConnected(ctx, mqttMsg)) {
            return;
        }
        String topicName = mqttMsg.variableHeader().topicName();
        int msgId = mqttMsg.variableHeader().packetId();
        log.trace("[{}][{}] Processing publish msg [{}][{}]!", sessionId, deviceSessionCtx.getDeviceId(), topicName, msgId);

        if (topicName.startsWith(MqttTopics.BASE_GATEWAY_API_TOPIC_V1_JSON) || topicName.startsWith(MqttTopics.BASE_GATEWAY_API_V2_TOPIC)) {
            if (gatewaySessionHandler != null) {
                handleGatewayPublishMsg(topicName, msgId, mqttMsg);
                transportService.reportActivity(sessionInfo);
            }
        } else {
            processDevicePublish(ctx, mqttMsg, topicName, msgId);
        }
    }

    private void handleGatewayPublishMsg(String topicName, int msgId, MqttPublishMessage mqttMsg) {
        try {
            switch (topicName) {
                case MqttTopics.GATEWAY_TELEMETRY_TOPIC_V1_JSON:
                case MqttTopics.GATEWAY_TELEMETRY_TOPIC_V2_JSON:
                    gatewaySessionHandler.onDeviceTelemetryJson(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_CLAIM_TOPIC_V1_JSON:
                case MqttTopics.GATEWAY_CLAIM_TOPIC_V2_JSON:
                    gatewaySessionHandler.onDeviceClaimJson(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC_V1_JSON:
                case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC_V2_JSON:
                    gatewaySessionHandler.onDeviceAttributesJson(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_ATTRIBUTES_REQUEST_TOPIC_V1_JSON:
                case MqttTopics.GATEWAY_ATTRIBUTES_REQUEST_TOPIC_V2_JSON:
                    gatewaySessionHandler.onDeviceAttributesRequestJson(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_RPC_TOPIC_V1_JSON:
                case MqttTopics.GATEWAY_RPC_TOPIC_V2_JSON:
                    gatewaySessionHandler.onDeviceRpcResponseJson(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_CONNECT_TOPIC_V1_JSON:
                case MqttTopics.GATEWAY_CONNECT_TOPIC_V2_JSON:
                    gatewaySessionHandler.onDeviceConnectJson(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_DISCONNECT_TOPIC_V1_JSON:
                case MqttTopics.GATEWAY_DISCONNECT_TOPIC_V2_JSON:
                    gatewaySessionHandler.onDeviceDisconnectJson(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_TELEMETRY_TOPIC_V2_PROTO:
                    gatewaySessionHandler.onDeviceTelemetryProto(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_CLAIM_TOPIC_V2_PROTO:
                    gatewaySessionHandler.onDeviceClaimProto(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC_V2_PROTO:
                    gatewaySessionHandler.onDeviceAttributesProto(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_ATTRIBUTES_REQUEST_TOPIC_V2_PROTO:
                    gatewaySessionHandler.onDeviceAttributesRequestProto(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_RPC_TOPIC_V2_PROTO:
                    gatewaySessionHandler.onDeviceRpcResponseProto(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_CONNECT_TOPIC_V2_PROTO:
                    gatewaySessionHandler.onDeviceConnectProto(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_DISCONNECT_TOPIC_V2_PROTO:
                    gatewaySessionHandler.onDeviceDisconnectProto(mqttMsg);
                    break;
            }
        } catch (RuntimeException | AdaptorException e) {
            log.warn("[{}] Failed to process publish msg [{}][{}]", sessionId, topicName, msgId, e);
        }
    }

    private void processDevicePublish(ChannelHandlerContext ctx, MqttPublishMessage mqttMsg, String topicName, int msgId) {
        try {
            if (topicName.startsWith(MqttTopics.BASE_DEVICE_API_TOPIC_V1_JSON)) {
                if (topicName.equals(MqttTopics.DEVICE_TELEMETRY_TOPIC_V1_JSON)) {
                    TransportProtos.PostTelemetryMsg postTelemetryMsg = jsonV1MqttAdaptor.convertToPostTelemetry(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, postTelemetryMsg, getPubAckCallback(ctx, msgId, postTelemetryMsg));
                } else if (topicName.equals(MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V1_JSON)) {
                    TransportProtos.PostAttributeMsg postAttributeMsg = jsonV1MqttAdaptor.convertToPostAttributes(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, postAttributeMsg, getPubAckCallback(ctx, msgId, postAttributeMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_ATTRIBUTES_REQUEST_TOPIC_PREFIX_V1_JSON)) {
                    TransportProtos.GetAttributeRequestMsg getAttributeMsg = jsonV1MqttAdaptor.convertToGetAttributes(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, getAttributeMsg, getPubAckCallback(ctx, msgId, getAttributeMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_RESPONSE_TOPIC_V1_JSON)) {
                    TransportProtos.ToDeviceRpcResponseMsg rpcResponseMsg = jsonV1MqttAdaptor.convertToDeviceRpcResponse(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, rpcResponseMsg, getPubAckCallback(ctx, msgId, rpcResponseMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_REQUESTS_TOPIC_V1_JSON)) {
                    TransportProtos.ToServerRpcRequestMsg rpcRequestMsg = jsonV1MqttAdaptor.convertToServerRpcRequest(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, rpcRequestMsg, getPubAckCallback(ctx, msgId, rpcRequestMsg));
                } else if (topicName.equals(MqttTopics.DEVICE_CLAIM_TOPIC_V1_JSON)) {
                    TransportProtos.ClaimDeviceMsg claimDeviceMsg = jsonV1MqttAdaptor.convertToClaimDevice(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, claimDeviceMsg, getPubAckCallback(ctx, msgId, claimDeviceMsg));
                } else {
                    transportService.reportActivity(sessionInfo);
                }
            } else if (topicName.startsWith(MqttTopics.BASE_DEVICE_API_TOPIC_V2_JSON)) {
                if (topicName.equals(MqttTopics.DEVICE_TELEMETRY_TOPIC_V2_JSON)) {
                    TransportProtos.PostTelemetryMsg postTelemetryMsg = jsonV2MqttAdaptor.convertToPostTelemetry(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, postTelemetryMsg, getPubAckCallback(ctx, msgId, postTelemetryMsg));
                } else if (topicName.equals(MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V2_JSON)) {
                    TransportProtos.PostAttributeMsg postAttributeMsg = jsonV2MqttAdaptor.convertToPostAttributes(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, postAttributeMsg, getPubAckCallback(ctx, msgId, postAttributeMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_ATTRIBUTES_REQUEST_TOPIC_PREFIX_V2_JSON)) {
                    TransportProtos.GetAttributeRequestMsg getAttributeMsg = jsonV2MqttAdaptor.convertToGetAttributes(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, getAttributeMsg, getPubAckCallback(ctx, msgId, getAttributeMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_RESPONSE_TOPIC_V2_JSON)) {
                    TransportProtos.ToDeviceRpcResponseMsg rpcResponseMsg = jsonV2MqttAdaptor.convertToDeviceRpcResponse(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, rpcResponseMsg, getPubAckCallback(ctx, msgId, rpcResponseMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_REQUESTS_TOPIC_V2_JSON)) {
                    TransportProtos.ToServerRpcRequestMsg rpcRequestMsg = jsonV2MqttAdaptor.convertToServerRpcRequest(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, rpcRequestMsg, getPubAckCallback(ctx, msgId, rpcRequestMsg));
                } else if (topicName.equals(MqttTopics.DEVICE_CLAIM_TOPIC_V2_JSON)) {
                    TransportProtos.ClaimDeviceMsg claimDeviceMsg = jsonV2MqttAdaptor.convertToClaimDevice(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, claimDeviceMsg, getPubAckCallback(ctx, msgId, claimDeviceMsg));
                } else {
                    transportService.reportActivity(sessionInfo);
                }
            } else if (topicName.startsWith(MqttTopics.BASE_DEVICE_API_TOPIC_V2_PROTO)) {
                if (topicName.equals(MqttTopics.DEVICE_TELEMETRY_TOPIC_V2_PROTO)) {
                    TransportProtos.PostTelemetryMsg postTelemetryMsg = protoMqttAdaptor.convertToPostTelemetry(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, postTelemetryMsg, getPubAckCallback(ctx, msgId, postTelemetryMsg));
                } else if (topicName.equals(MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V2_PROTO)) {
                    TransportProtos.PostAttributeMsg postAttributeMsg = protoMqttAdaptor.convertToPostAttributes(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, postAttributeMsg, getPubAckCallback(ctx, msgId, postAttributeMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_ATTRIBUTES_REQUEST_TOPIC_PREFIX_V2_PROTO)) {
                    TransportProtos.GetAttributeRequestMsg getAttributeMsg = protoMqttAdaptor.convertToGetAttributes(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, getAttributeMsg, getPubAckCallback(ctx, msgId, getAttributeMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_RESPONSE_TOPIC_V2_PROTO)) {
                    TransportProtos.ToDeviceRpcResponseMsg rpcResponseMsg = protoMqttAdaptor.convertToDeviceRpcResponse(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, rpcResponseMsg, getPubAckCallback(ctx, msgId, rpcResponseMsg));
                } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_REQUESTS_TOPIC_V2_PROTO)) {
                    TransportProtos.ToServerRpcRequestMsg rpcRequestMsg = protoMqttAdaptor.convertToServerRpcRequest(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, rpcRequestMsg, getPubAckCallback(ctx, msgId, rpcRequestMsg));
                } else if (topicName.equals(MqttTopics.DEVICE_CLAIM_TOPIC_V2_PROTO)) {
                    TransportProtos.ClaimDeviceMsg claimDeviceMsg = protoMqttAdaptor.convertToClaimDevice(deviceSessionCtx, mqttMsg);
                    transportService.process(sessionInfo, claimDeviceMsg, getPubAckCallback(ctx, msgId, claimDeviceMsg));
                } else {
                    transportService.reportActivity(sessionInfo);
                }
            }
        } catch (AdaptorException e) {
            log.warn("[{}] Failed to process publish msg [{}][{}]", sessionId, topicName, msgId, e);
            log.info("[{}] Closing current session due to invalid publish msg [{}][{}]", sessionId, topicName, msgId);
            ctx.close();
        }
    }

    private <T> TransportServiceCallback<Void> getPubAckCallback(final ChannelHandlerContext ctx, final int msgId, final T msg) {
        return new TransportServiceCallback<Void>() {
            @Override
            public void onSuccess(Void dummy) {
                log.trace("[{}] Published msg: {}", sessionId, msg);
                if (msgId > 0) {
                    ctx.writeAndFlush(createMqttPubAckMsg(msgId));
                }
            }

            @Override
            public void onError(Throwable e) {
                log.trace("[{}] Failed to publish msg: {}", sessionId, msg, e);
                processDisconnect(ctx);
            }
        };
    }

    private void processSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage mqttMsg) {
        if (!checkConnected(ctx, mqttMsg)) {
            return;
        }
        log.trace("[{}] Processing subscription [{}]!", sessionId, mqttMsg.variableHeader().messageId());
        List<Integer> grantedQoSList = new ArrayList<>();
        boolean activityReported = false;
        for (MqttTopicSubscription subscription : mqttMsg.payload().topicSubscriptions()) {
            String topic = subscription.topicName();
            if (topic.startsWith(MqttTopics.BASE_DEVICE_API_TOPIC_V1_JSON) || topic.startsWith(MqttTopics.BASE_GATEWAY_API_TOPIC_V1_JSON)) {
                deviceSessionCtx.setTransportAdaptor(jsonV1MqttAdaptor);
            } else if (topic.startsWith(MqttTopics.BASE_DEVICE_API_TOPIC_V2_JSON) || topic.startsWith(MqttTopics.BASE_GATEWAY_API_TOPIC_V2_JSON)) {
                deviceSessionCtx.setTransportAdaptor(jsonV2MqttAdaptor);
            } else {
                deviceSessionCtx.setTransportAdaptor(protoMqttAdaptor);
            }
            MqttQoS reqQoS = subscription.qualityOfService();
            try {
                switch (topic) {
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V1_JSON:
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V2_JSON:
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V2_PROTO: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToAttributeUpdatesMsg.newBuilder().build(), null);
                        registerSubQoS(topic, grantedQoSList, reqQoS);
                        activityReported = true;
                        break;
                    }
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC_V1_JSON:
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC_V2_JSON:
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC_V2_PROTO: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToRPCMsg.newBuilder().build(), null);
                        registerSubQoS(topic, grantedQoSList, reqQoS);
                        activityReported = true;
                        break;
                    }
                    case MqttTopics.DEVICE_RPC_RESPONSE_SUB_TOPIC_V1_JSON:
                    case MqttTopics.DEVICE_RPC_RESPONSE_SUB_TOPIC_V2_JSON:
                    case MqttTopics.DEVICE_RPC_RESPONSE_SUB_TOPIC_V2_PROTO:

                    case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC_V1_JSON:
                    case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC_V2_JSON:
                    case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC_V2_PROTO:

                    case MqttTopics.GATEWAY_RPC_TOPIC_V1_JSON:
                    case MqttTopics.GATEWAY_RPC_TOPIC_V2_JSON:
                    case MqttTopics.GATEWAY_RPC_TOPIC_V2_PROTO:

                    case MqttTopics.GATEWAY_ATTRIBUTES_RESPONSE_TOPIC_V1_JSON:
                    case MqttTopics.GATEWAY_ATTRIBUTES_RESPONSE_TOPIC_V2_JSON:
                    case MqttTopics.GATEWAY_ATTRIBUTES_RESPONSE_TOPIC_V2_PROTO:

                    case MqttTopics.DEVICE_ATTRIBUTES_RESPONSES_TOPIC_V1_JSON:
                    case MqttTopics.DEVICE_ATTRIBUTES_RESPONSES_TOPIC_V2_JSON:
                    case MqttTopics.DEVICE_ATTRIBUTES_RESPONSES_TOPIC_V2_PROTO:
                        registerSubQoS(topic, grantedQoSList, reqQoS);
                        break;
                    default:
                        log.warn("[{}] Failed to subscribe to [{}][{}]", sessionId, topic, reqQoS);
                        grantedQoSList.add(FAILURE.value());
                        break;
                }
            } catch (Exception e) {
                log.warn("[{}] Failed to subscribe to [{}][{}]", sessionId, topic, reqQoS, e);
                grantedQoSList.add(FAILURE.value());
            }
        }
        if (!activityReported) {
            transportService.reportActivity(sessionInfo);
        }
        ctx.writeAndFlush(createSubAckMessage(mqttMsg.variableHeader().messageId(), grantedQoSList));
    }

    private void registerSubQoS(String topic, List<Integer> grantedQoSList, MqttQoS reqQoS) {
        grantedQoSList.add(getMinSupportedQos(reqQoS));
        mqttQoSMap.put(new MqttTopicMatcher(topic), getMinSupportedQos(reqQoS));
    }

    private void processUnsubscribe(ChannelHandlerContext ctx, MqttUnsubscribeMessage mqttMsg) {
        if (!checkConnected(ctx, mqttMsg)) {
            return;
        }
        boolean activityReported = false;
        log.trace("[{}] Processing subscription [{}]!", sessionId, mqttMsg.variableHeader().messageId());
        for (String topicName : mqttMsg.payload().topics()) {
            mqttQoSMap.remove(new MqttTopicMatcher(topicName));
            try {
                switch (topicName) {
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V1_JSON:
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V2_JSON:
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC_V2_PROTO: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToAttributeUpdatesMsg.newBuilder().setUnsubscribe(true).build(), null);
                        activityReported = true;
                        break;
                    }
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC_V1_JSON:
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC_V2_JSON:
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC_V2_PROTO: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToRPCMsg.newBuilder().setUnsubscribe(true).build(), null);
                        activityReported = true;
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] Failed to process unsubscription [{}] to [{}]", sessionId, mqttMsg.variableHeader().messageId(), topicName);
            }
        }
        if (!activityReported) {
            transportService.reportActivity(sessionInfo);
        }
        ctx.writeAndFlush(createUnSubAckMessage(mqttMsg.variableHeader().messageId()));
    }

    private MqttMessage createUnSubAckMessage(int msgId) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(UNSUBACK, false, AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader mqttMessageIdVariableHeader = MqttMessageIdVariableHeader.from(msgId);
        return new MqttMessage(mqttFixedHeader, mqttMessageIdVariableHeader);
    }

    private void processConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
        log.info("[{}] Processing connect msg for client: {}!", sessionId, msg.payload().clientIdentifier());
        X509Certificate cert;
        if (sslHandler != null && (cert = getX509Certificate()) != null) {
            processX509CertConnect(ctx, cert);
        } else {
            processAuthTokenConnect(ctx, msg);
        }
    }

    private void processAuthTokenConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
        String userName = msg.payload().userName();
        log.info("[{}] Processing connect msg for client with user name: {}!", sessionId, userName);
        if (StringUtils.isEmpty(userName)) {
            ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD));
            ctx.close();
        } else {
            transportService.process(DeviceTransportType.MQTT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(userName).build(),
                    new TransportServiceCallback<ValidateDeviceCredentialsResponse>() {
                        @Override
                        public void onSuccess(ValidateDeviceCredentialsResponse msg) {
                            onValidateDeviceResponse(msg, ctx);
                        }

                        @Override
                        public void onError(Throwable e) {
                            log.trace("[{}] Failed to process credentials: {}", address, userName, e);
                            ctx.writeAndFlush(createMqttConnAckMsg(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE));
                            ctx.close();
                        }
                    });
        }
    }

    private void processX509CertConnect(ChannelHandlerContext ctx, X509Certificate cert) {
        try {
            String strCert = SslUtil.getX509CertificateString(cert);
            String sha3Hash = EncryptionUtil.getSha3Hash(strCert);
            transportService.process(DeviceTransportType.MQTT, ValidateDeviceX509CertRequestMsg.newBuilder().setHash(sha3Hash).build(),
                    new TransportServiceCallback<ValidateDeviceCredentialsResponse>() {
                        @Override
                        public void onSuccess(ValidateDeviceCredentialsResponse msg) {
                            onValidateDeviceResponse(msg, ctx);
                        }

                        @Override
                        public void onError(Throwable e) {
                            log.trace("[{}] Failed to process credentials: {}", address, sha3Hash, e);
                            ctx.writeAndFlush(createMqttConnAckMsg(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE));
                            ctx.close();
                        }
                    });
        } catch (Exception e) {
            ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_REFUSED_NOT_AUTHORIZED));
            ctx.close();
        }
    }

    private X509Certificate getX509Certificate() {
        try {
            X509Certificate[] certChain = sslHandler.engine().getSession().getPeerCertificateChain();
            if (certChain.length > 0) {
                return certChain[0];
            }
        } catch (SSLPeerUnverifiedException e) {
            log.warn(e.getMessage());
            return null;
        }
        return null;
    }

    private void processDisconnect(ChannelHandlerContext ctx) {
        ctx.close();
        log.info("[{}] Client disconnected!", sessionId);
        doDisconnect();
    }

    private MqttConnAckMessage createMqttConnAckMsg(MqttConnectReturnCode returnCode) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(CONNACK, false, AT_MOST_ONCE, false, 0);
        MqttConnAckVariableHeader mqttConnAckVariableHeader =
                new MqttConnAckVariableHeader(returnCode, true);
        return new MqttConnAckMessage(mqttFixedHeader, mqttConnAckVariableHeader);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[{}] Unexpected Exception", sessionId, cause);
        ctx.close();
    }

    private static MqttSubAckMessage createSubAckMessage(Integer msgId, List<Integer> grantedQoSList) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(SUBACK, false, AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader mqttMessageIdVariableHeader = MqttMessageIdVariableHeader.from(msgId);
        MqttSubAckPayload mqttSubAckPayload = new MqttSubAckPayload(grantedQoSList);
        return new MqttSubAckMessage(mqttFixedHeader, mqttMessageIdVariableHeader, mqttSubAckPayload);
    }

    private static int getMinSupportedQos(MqttQoS reqQoS) {
        return Math.min(reqQoS.value(), MAX_SUPPORTED_QOS_LVL.value());
    }

    public static MqttPubAckMessage createMqttPubAckMsg(int requestId) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(PUBACK, false, AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader mqttMsgIdVariableHeader =
                MqttMessageIdVariableHeader.from(requestId);
        return new MqttPubAckMessage(mqttFixedHeader, mqttMsgIdVariableHeader);
    }

    private boolean checkConnected(ChannelHandlerContext ctx, MqttMessage msg) {
        if (deviceSessionCtx.isConnected()) {
            return true;
        } else {
            log.info("[{}] Closing current session due to invalid msg order: {}", sessionId, msg);
            ctx.close();
            return false;
        }
    }

    private void checkGatewaySession() {
        TransportDeviceInfo device = deviceSessionCtx.getDeviceInfo();
        try {
            JsonNode infoNode = context.getMapper().readTree(device.getAdditionalInfo());
            if (infoNode != null) {
                JsonNode gatewayNode = infoNode.get("gateway");
                if (gatewayNode != null && gatewayNode.asBoolean()) {
                    gatewaySessionHandler = new GatewaySessionHandler(context, deviceSessionCtx, sessionId);
                }
            }
        } catch (IOException e) {
            log.trace("[{}][{}] Failed to fetch device additional info", sessionId, device.getDeviceName(), e);
        }
    }

    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
        doDisconnect();
    }

    private void doDisconnect() {
        if (deviceSessionCtx.isConnected()) {
            transportService.process(sessionInfo, DefaultTransportService.getSessionEventMsg(SessionEvent.CLOSED), null);
            transportService.deregisterSession(sessionInfo);
            if (gatewaySessionHandler != null) {
                gatewaySessionHandler.onGatewayDisconnect();
            }
            deviceSessionCtx.setDisconnected();
        }
    }

    private void onValidateDeviceResponse(ValidateDeviceCredentialsResponse msg, ChannelHandlerContext ctx) {
        if (!msg.hasDeviceInfo()) {
            ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_REFUSED_NOT_AUTHORIZED));
            ctx.close();
        } else {
            deviceSessionCtx.setDeviceInfo(msg.getDeviceInfo());
            deviceSessionCtx.setDeviceProfile(msg.getDeviceProfile());
            sessionInfo = SessionInfoCreator.create(msg, context, sessionId);
            transportService.process(sessionInfo, DefaultTransportService.getSessionEventMsg(SessionEvent.OPEN), new TransportServiceCallback<Void>() {
                @Override
                public void onSuccess(Void msg) {
                    transportService.registerAsyncSession(sessionInfo, MqttTransportHandler.this);
                    checkGatewaySession();
                    ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_ACCEPTED));
                    log.info("[{}] Client connected!", sessionId);
                }

                @Override
                public void onError(Throwable e) {
                    log.warn("[{}] Failed to submit session event", sessionId, e);
                    ctx.writeAndFlush(createMqttConnAckMsg(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE));
                    ctx.close();
                }
            });
        }
    }

    @Override
    public void onGetAttributesResponse(TransportProtos.GetAttributeResponseMsg response) {
        try {
            MqttTransportAdaptor transportAdaptor = deviceSessionCtx.getTransportAdaptor();
            transportAdaptor.convertToPublish(deviceSessionCtx, response).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device attributes response to MQTT msg", sessionId, e);
        }
    }

    @Override
    public void onAttributeUpdate(TransportProtos.AttributeUpdateNotificationMsg notification) {
        try {
            MqttTransportAdaptor transportAdaptor = deviceSessionCtx.getTransportAdaptor();
            transportAdaptor.convertToPublish(deviceSessionCtx, notification).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device attributes update to MQTT msg", sessionId, e);
        }
    }

    @Override
    public void onRemoteSessionCloseCommand(TransportProtos.SessionCloseNotificationProto sessionCloseNotification) {
        log.trace("[{}] Received the remote command to close the session", sessionId);
        processDisconnect(deviceSessionCtx.getChannel());
    }

    @Override
    public void onToDeviceRpcRequest(TransportProtos.ToDeviceRpcRequestMsg rpcRequest) {
        log.trace("[{}] Received RPC command to device", sessionId);
        try {
            MqttTransportAdaptor transportAdaptor = deviceSessionCtx.getTransportAdaptor();
            transportAdaptor.convertToPublish(deviceSessionCtx, rpcRequest).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device RPC command to MQTT msg", sessionId, e);
        }
    }

    @Override
    public void onToServerRpcResponse(TransportProtos.ToServerRpcResponseMsg rpcResponse) {
        log.trace("[{}] Received RPC command to device", sessionId);
        try {
            MqttTransportAdaptor transportAdaptor = deviceSessionCtx.getTransportAdaptor();
            transportAdaptor.convertToPublish(deviceSessionCtx, rpcResponse).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device RPC command to MQTT msg", sessionId, e);
        }
    }

    @Override
    public void onProfileUpdate(DeviceProfile deviceProfile) {
        deviceSessionCtx.getDeviceInfo().setDeviceType(deviceProfile.getName());
        sessionInfo = SessionInfoProto.newBuilder().mergeFrom(sessionInfo).setDeviceType(deviceProfile.getName()).build();
    }
}
