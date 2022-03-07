/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.rule.engine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.alarm.AlarmStatus;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;

import java.io.IOException;
import java.util.List;

@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "create alarm", relationTypes = {"Created", "Updated", "False"},
        configClazz = TbCreateAlarmNodeConfiguration.class,
        nodeDescription = "Create or Update Alarm",
        nodeDetails =
                "Details - JS function that creates JSON object based on incoming message. This object will be added into Alarm.details field.\n" +
                        "Node output:\n" +
                        "If alarm was not created, original message is returned. Otherwise new Message returned with type 'ALARM', Alarm object in 'msg' property and 'metadata' will contains one of those properties 'isNewAlarm/isExistingAlarm'. " +
                        "Message payload can be accessed via <code>msg</code> property. For example <code>'temperature = ' + msg.temperature ;</code>. " +
                        "Message metadata can be accessed via <code>metadata</code> property. For example <code>'name = ' + metadata.customerName;</code>.",
        uiResources = {"static/rulenode/rulenode-core-config.js"},
        configDirective = "tbActionNodeCreateAlarmConfig",
        icon = "notifications_active"
)
public class TbCreateAlarmNode extends TbAbstractAlarmNode<TbCreateAlarmNodeConfiguration> {

    private static ObjectMapper mapper = new ObjectMapper();
    private List<String> relationTypes;
    private AlarmSeverity notDynamicAlarmSeverity;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        super.init(ctx, configuration);
        if(!this.config.isDynamicSeverity()) {
            this.notDynamicAlarmSeverity = EnumUtils.getEnum(AlarmSeverity.class, this.config.getSeverity());
            if(this.notDynamicAlarmSeverity == null) {
                throw new TbNodeException("Incorrect Alarm Severity value: " + this.config.getSeverity());
            }
        }
    }


    @Override
    protected TbCreateAlarmNodeConfiguration loadAlarmNodeConfig(TbNodeConfiguration configuration) throws TbNodeException {
        TbCreateAlarmNodeConfiguration nodeConfiguration = TbNodeUtils.convert(configuration, TbCreateAlarmNodeConfiguration.class);
        relationTypes = nodeConfiguration.getRelationTypes();
        return nodeConfiguration;
    }

    @Override
    protected ListenableFuture<TbAlarmResult> processAlarm(TbContext ctx, TbMsg msg) {
        String alarmType;
        final Alarm msgAlarm;

        if (!config.isUseMessageAlarmData()) {
            alarmType = TbNodeUtils.processPattern(this.config.getAlarmType(), msg);
            msgAlarm = null;
        } else {
            try {
                msgAlarm = getAlarmFromMessage(ctx, msg);
                alarmType = msgAlarm.getType();
            } catch (IOException e) {
                ctx.tellFailure(msg, e);
                return null;
            }
        }

        ListenableFuture<Alarm> latest = ctx.getAlarmService().findLatestByOriginatorAndType(ctx.getTenantId(), msg.getOriginator(), alarmType);
        return Futures.transformAsync(latest, existingAlarm -> {
            if (existingAlarm == null || existingAlarm.getStatus().isCleared()) {
                return createNewAlarm(ctx, msg, msgAlarm);
            } else {
                return updateAlarm(ctx, msg, existingAlarm, msgAlarm);
            }
        }, ctx.getDbCallbackExecutor());

    }

    private Alarm getAlarmFromMessage(TbContext ctx, TbMsg msg) throws IOException {
        Alarm msgAlarm;
        msgAlarm = mapper.readValue(msg.getData(), Alarm.class);
        msgAlarm.setTenantId(ctx.getTenantId());
        if (msgAlarm.getOriginator() == null) {
            msgAlarm.setOriginator(msg.getOriginator());
        }
        if (msgAlarm.getStatus() == null) {
            msgAlarm.setStatus(AlarmStatus.ACTIVE_UNACK);
        }
        return msgAlarm;
    }

    private ListenableFuture<TbAlarmResult> createNewAlarm(TbContext ctx, TbMsg msg, Alarm msgAlarm) {
        ListenableFuture<JsonNode> asyncDetails;
        boolean buildDetails = !config.isUseMessageAlarmData() || config.isOverwriteAlarmDetails();
        if (buildDetails) {
            ctx.logJsEvalRequest();
            asyncDetails = buildAlarmDetails(ctx, msg, null);
        } else {
            asyncDetails = Futures.immediateFuture(null);
        }
        ListenableFuture<Alarm> asyncAlarm =  Futures.transform(asyncDetails, details -> {
            if (buildDetails) {
                ctx.logJsEvalResponse();
            }
            Alarm newAlarm;
            if (msgAlarm != null) {
                newAlarm = msgAlarm;
                if (buildDetails) {
                    newAlarm.setDetails(details);
                }
            } else {
                newAlarm = buildAlarm(msg, details, ctx.getTenantId());
            }
            return newAlarm;
        }, MoreExecutors.directExecutor());
        ListenableFuture<Alarm> asyncCreated = Futures.transform(asyncAlarm,
                alarm -> ctx.getAlarmService().createOrUpdateAlarm(alarm), ctx.getDbCallbackExecutor());
        return Futures.transform(asyncCreated, alarm -> new TbAlarmResult(true, false, false, alarm), MoreExecutors.directExecutor());
    }

    private ListenableFuture<TbAlarmResult> updateAlarm(TbContext ctx, TbMsg msg, Alarm existingAlarm, Alarm msgAlarm) {
        ListenableFuture<JsonNode> asyncDetails;
        boolean buildDetails = !config.isUseMessageAlarmData() || config.isOverwriteAlarmDetails();
        if (buildDetails) {
            ctx.logJsEvalRequest();
            asyncDetails = buildAlarmDetails(ctx, msg, existingAlarm.getDetails());
        } else {
            asyncDetails = Futures.immediateFuture(null);
        }
        ListenableFuture<Alarm> asyncUpdated = Futures.transform(asyncDetails, (Function<JsonNode, Alarm>) details -> {
            if (buildDetails) {
                ctx.logJsEvalResponse();
            }
            if (msgAlarm != null) {
                existingAlarm.setSeverity(msgAlarm.getSeverity());
                updateStatus(existingAlarm, msgAlarm);
                existingAlarm.setPropagate(msgAlarm.isPropagate());
                existingAlarm.setPropagateToOwner(msgAlarm.isPropagateToOwner());
                existingAlarm.setPropagateToTenant(msgAlarm.isPropagateToTenant());
                existingAlarm.setPropagateRelationTypes(msgAlarm.getPropagateRelationTypes());
                if (buildDetails) {
                    existingAlarm.setDetails(details);
                } else {
                    existingAlarm.setDetails(msgAlarm.getDetails());
                }
            } else {
                existingAlarm.setSeverity(processAlarmSeverity(msg));
                existingAlarm.setPropagate(config.isPropagate());
                existingAlarm.setPropagateToOwner(config.isPropagateToOwner());
                existingAlarm.setPropagateToTenant(config.isPropagateToTenant());
                existingAlarm.setPropagateRelationTypes(relationTypes);
                existingAlarm.setDetails(details);
            }
            existingAlarm.setEndTs(System.currentTimeMillis());
            return ctx.getAlarmService().createOrUpdateAlarm(existingAlarm);
        }, ctx.getDbCallbackExecutor());

        return Futures.transform(asyncUpdated, a -> new TbAlarmResult(false, true, false, a), MoreExecutors.directExecutor());
    }

    private void updateStatus(Alarm existingAlarm, Alarm msgAlarm) {
        boolean isActive = existingAlarm.getStatus() == AlarmStatus.ACTIVE_ACK
                || existingAlarm.getStatus() == AlarmStatus.ACTIVE_UNACK;

        boolean acknowledged = existingAlarm.getStatus() == AlarmStatus.ACTIVE_ACK
                || existingAlarm.getStatus() == AlarmStatus.CLEARED_ACK;
        boolean shouldBeAcknowledged = msgAlarm.getStatus() == AlarmStatus.ACTIVE_ACK
                || msgAlarm.getStatus() == AlarmStatus.CLEARED_ACK;

        if (!acknowledged && shouldBeAcknowledged) {
            if (isActive) {
                existingAlarm.setStatus(AlarmStatus.ACTIVE_ACK);
            } else {
                existingAlarm.setStatus(AlarmStatus.CLEARED_ACK);
            }
        }
    }

    private Alarm buildAlarm(TbMsg msg, JsonNode details, TenantId tenantId) {
        long ts = msg.getMetaDataTs();
        return Alarm.builder()
                .tenantId(tenantId)
                .originator(msg.getOriginator())
                .status(AlarmStatus.ACTIVE_UNACK)
                .severity(this.config.isDynamicSeverity() ? processAlarmSeverity(msg) : notDynamicAlarmSeverity)
                .propagate(config.isPropagate())
                .propagateToOwner(config.isPropagateToOwner())
                .propagateToTenant(config.isPropagateToTenant())
                .type(TbNodeUtils.processPattern(this.config.getAlarmType(), msg))
                .propagateRelationTypes(relationTypes)
                .startTs(ts)
                .endTs(ts)
                .details(details)
                .build();
    }

    private AlarmSeverity processAlarmSeverity(TbMsg msg) {
        AlarmSeverity severity = EnumUtils.getEnum(AlarmSeverity.class, TbNodeUtils.processPattern(this.config.getSeverity(), msg));
        if(severity == null) {
            throw new RuntimeException("Used incorrect pattern or Alarm Severity not included in message");
        }
        return severity;
    }

}
