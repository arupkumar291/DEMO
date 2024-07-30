/**
 * Copyright © 2016-2024 The Thingsboard Authors
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
package org.thingsboard.server.service.alarm.rule;

import com.google.gson.JsonParser;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.adaptor.JsonConverter;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.rule.AlarmRule;
import org.thingsboard.server.common.data.alarm.rule.condition.AlarmConditionFilterKey;
import org.thingsboard.server.common.data.alarm.rule.condition.AlarmConditionKeyType;
import org.thingsboard.server.common.data.exception.ApiUsageLimitsExceededException;
import org.thingsboard.server.common.data.id.AlarmRuleId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.service.alarm.rule.state.PersistedAlarmState;
import org.thingsboard.server.service.alarm.rule.state.PersistedEntityState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.thingsboard.server.common.data.msg.TbMsgType.ACTIVITY_EVENT;
import static org.thingsboard.server.common.data.msg.TbMsgType.ALARM_ACK;
import static org.thingsboard.server.common.data.msg.TbMsgType.ALARM_CLEAR;
import static org.thingsboard.server.common.data.msg.TbMsgType.ALARM_DELETE;
import static org.thingsboard.server.common.data.msg.TbMsgType.ATTRIBUTES_DELETED;
import static org.thingsboard.server.common.data.msg.TbMsgType.ATTRIBUTES_UPDATED;
import static org.thingsboard.server.common.data.msg.TbMsgType.INACTIVITY_EVENT;
import static org.thingsboard.server.common.data.msg.TbMsgType.POST_ATTRIBUTES_REQUEST;
import static org.thingsboard.server.common.data.msg.TbMsgType.POST_TELEMETRY_REQUEST;

@Slf4j
class EntityState {

    @Getter
    private final TenantId tenantId;
    @Getter
    private final EntityId entityId;
    @Getter
    @Setter
    private EntityId profileId;
    private final TbAlarmRuleContext ctx;
    private final EntityRulesState entityRulesState;
    private final PersistedEntityState pes;
    private DataSnapshot latestValues;
    private final ConcurrentMap<AlarmRuleId, AlarmState> alarmStates = new ConcurrentHashMap<>();
    private final DynamicPredicateValueCtx dynamicPredicateValueCtx;
    @Getter
    private final Lock lock = new ReentrantLock();

    EntityState(TenantId tenantId, EntityId entityId, EntityId profileId, TbAlarmRuleContext ctx, EntityRulesState entityRulesState, PersistedEntityState pes) {
        this.tenantId = tenantId;
        this.entityId = entityId;
        this.profileId = profileId;
        this.ctx = ctx;
        this.entityRulesState = entityRulesState;

        this.dynamicPredicateValueCtx = new DynamicPredicateValueCtxImpl(tenantId, entityId, ctx);

        if (pes != null) {
            this.pes = pes;

            for (AlarmRule alarmRule : entityRulesState.getAlarmRules().values()) {
                alarmStates.computeIfAbsent(alarmRule.getId(),
                        a -> new AlarmState(entityRulesState, tenantId, entityId, alarmRule, getOrInitPersistedAlarmState(alarmRule), dynamicPredicateValueCtx));
            }
        } else {
            this.pes = new PersistedEntityState();
            this.pes.setTenantId(tenantId);
            this.pes.setEntityId(entityId);
            this.pes.setAlarmStates(new HashMap<>());
        }
    }

    public List<AlarmRule> getAlarmRules() {
        return new ArrayList<>(entityRulesState.getAlarmRules().values());
    }

    public void addAlarmRule(AlarmRule alarmRule) {
        if (!alarmStates.containsKey(alarmRule.getId())) {
            lock.lock();
            try {
                entityRulesState.addAlarmRule(alarmRule);
                alarmStates.put(alarmRule.getId(),
                        new AlarmState(entityRulesState, tenantId, entityId, alarmRule, getOrInitPersistedAlarmState(alarmRule), dynamicPredicateValueCtx));
            } finally {
                lock.unlock();
            }
        }
    }

    public void updateAlarmRule(AlarmRule alarmRule) throws ExecutionException, InterruptedException {
        lock.lock();
        try {
            Set<AlarmConditionFilterKey> oldKeys = Set.copyOf(this.entityRulesState.getEntityKeys());
            this.entityRulesState.updateAlarmRule(alarmRule);
            if (latestValues != null) {
                Set<AlarmConditionFilterKey> keysToFetch = new HashSet<>(this.entityRulesState.getEntityKeys());
                keysToFetch.removeAll(oldKeys);
                if (!keysToFetch.isEmpty()) {
                    addEntityKeysToSnapshot(ctx, entityId, keysToFetch, latestValues);
                }
            }

            if (alarmStates.containsKey(alarmRule.getId())) {
                alarmStates.get(alarmRule.getId()).updateState(alarmRule, getOrInitPersistedAlarmState(alarmRule));
            } else {
                alarmStates.putIfAbsent(alarmRule.getId(), new AlarmState(this.entityRulesState, tenantId, entityId, alarmRule, getOrInitPersistedAlarmState(alarmRule), dynamicPredicateValueCtx));
            }
        } finally {
            lock.unlock();
        }
    }

    public void removeAlarmRules(List<AlarmRuleId> alarmRuleIds) {
        boolean stateChanged = false;
        for (AlarmRuleId alarmRuleId : alarmRuleIds) {
            stateChanged |= doRemoveAlarmRule(alarmRuleId);
        }
        if (stateChanged) {
            saveState();
        }
    }

    public void removeAlarmRule(AlarmRuleId alarmRuleId) {
        if (doRemoveAlarmRule(alarmRuleId)) {
            saveState();
        }
    }

    private boolean doRemoveAlarmRule(AlarmRuleId alarmRuleId) {
        entityRulesState.removeAlarmRule(alarmRuleId);
        alarmStates.remove(alarmRuleId);
        if (pes != null) {
            PersistedAlarmState pas = pes.getAlarmStates().remove(alarmRuleId.toString());
            return pas != null;
        }
        return false;
    }

    public boolean isEmpty() {
        return alarmStates.isEmpty();
    }

    public void harvestAlarms(long ts) throws ExecutionException, InterruptedException {
        log.debug("[{}] Going to harvest alarms: {}", entityId, ts);
        lock.lock();
        try {
            boolean stateChanged = false;
            for (AlarmState state : alarmStates.values()) {
                stateChanged |= state.process(ctx, ts);
            }
            if (stateChanged) {
                saveState();
            }
        } finally {
            lock.unlock();
        }
    }

    public void process(TbAlarmRuleRequestCtx requestCtx, TbMsg msg) throws ExecutionException, InterruptedException {
        lock.lock();
        try {
            if (latestValues == null) {
                latestValues = fetchLatestValues(ctx, entityId);
            }
            boolean stateChanged = false;
            if (msg.isTypeOf(POST_TELEMETRY_REQUEST)) {
                stateChanged = processTelemetry(requestCtx, msg);
            } else if (msg.isTypeOf(POST_ATTRIBUTES_REQUEST)) {
                stateChanged = processAttributesUpdateRequest(requestCtx, msg);
            } else if (msg.isTypeOneOf(ACTIVITY_EVENT, INACTIVITY_EVENT)) {
                stateChanged = processDeviceActivityEvent(requestCtx, msg);
            } else if (msg.isTypeOf(ATTRIBUTES_UPDATED)) {
                stateChanged = processAttributesUpdateNotification(requestCtx, msg);
            } else if (msg.isTypeOf(ATTRIBUTES_DELETED)) {
                stateChanged = processAttributesDeleteNotification(requestCtx, msg);
            } else if (msg.isTypeOf(ALARM_CLEAR)) {
                stateChanged = processAlarmClearNotification(msg);
            } else if (msg.isTypeOf(ALARM_ACK)) {
                processAlarmAckNotification(msg);
            } else if (msg.isTypeOf(ALARM_DELETE)) {
                processAlarmDeleteNotification(msg);
            } else {
                if (msg.getType().equals(DataConstants.ENTITY_ASSIGNED) || msg.getType().equals(DataConstants.ENTITY_UNASSIGNED)) {
                    dynamicPredicateValueCtx.resetCustomer();
                }
            }
            if (stateChanged) {
                saveState();
            }
        } finally {
            lock.unlock();
        }
    }

    private void saveState() {
        ctx.getStateStore().put(pes);
    }

    private boolean processDeviceActivityEvent(TbAlarmRuleRequestCtx requestCtx, TbMsg msg) throws ExecutionException, InterruptedException {
        String scope = msg.getMetaData().getValue(DataConstants.SCOPE);
        if (StringUtils.isEmpty(scope)) {
            return processTelemetry(requestCtx, msg);
        } else {
            return processAttributes(requestCtx, msg, scope);
        }
    }

    private boolean processAlarmClearNotification(TbMsg msg) {
        boolean stateChanged = false;
        Alarm alarmNf = JacksonUtil.fromString(msg.getData(), Alarm.class);
        for (AlarmRule alarm : entityRulesState.getAlarmRules().values()) {
            AlarmState alarmState = alarmStates.computeIfAbsent(alarm.getId(),
                    a -> new AlarmState(this.entityRulesState, tenantId, entityId, alarm, getOrInitPersistedAlarmState(alarm), dynamicPredicateValueCtx));
            stateChanged |= alarmState.processAlarmClear(alarmNf);
        }
        return stateChanged;
    }

    private void processAlarmAckNotification(TbMsg msg) {
        Alarm alarmNf = JacksonUtil.fromString(msg.getData(), Alarm.class);
        for (AlarmRule alarm : entityRulesState.getAlarmRules().values()) {
            AlarmState alarmState = alarmStates.computeIfAbsent(alarm.getId(),
                    a -> new AlarmState(this.entityRulesState, tenantId, entityId, alarm, getOrInitPersistedAlarmState(alarm), dynamicPredicateValueCtx));
            alarmState.processAckAlarm(alarmNf);
        }
    }

    private void processAlarmDeleteNotification(TbMsg msg) {
        Alarm alarm = JacksonUtil.fromString(msg.getData(), Alarm.class);
        processAlarmDeleteNotification(alarm);
    }

    public void processAlarmDeleteNotification(Alarm alarm) {
        alarmStates.values().removeIf(alarmState -> alarmState.getCurrentAlarm() != null
                && alarmState.getCurrentAlarm().getId().equals(alarm.getId()));
    }

    private boolean processAttributesUpdateNotification(TbAlarmRuleRequestCtx requestCtx, TbMsg msg) throws ExecutionException, InterruptedException {
        String scope = msg.getMetaData().getValue(DataConstants.SCOPE);
        if (StringUtils.isEmpty(scope)) {
            scope = DataConstants.CLIENT_SCOPE;
        }
        return processAttributes(requestCtx, msg, scope);
    }

    private boolean processAttributesDeleteNotification(TbAlarmRuleRequestCtx requestCtx, TbMsg msg) throws ExecutionException, InterruptedException {
        List<String> keys = new ArrayList<>();
        JsonParser.parseString(msg.getData()).getAsJsonObject().get("attributes").getAsJsonArray().forEach(e -> keys.add(e.getAsString()));
        String scope = msg.getMetaData().getValue(DataConstants.SCOPE);
        if (StringUtils.isEmpty(scope)) {
            scope = DataConstants.CLIENT_SCOPE;
        }
        boolean stateChanged = false;

        if (!keys.isEmpty()) {
            EntityKeyType keyType = getKeyTypeFromScope(scope);
            Set<AlarmConditionFilterKey> removedKeys = keys.stream().map(key -> new EntityKey(keyType, key))
                    .peek(latestValues::removeValue)
                    .map(DataSnapshot::toConditionKey).collect(Collectors.toSet());
            SnapshotUpdate update = new SnapshotUpdate(AlarmConditionKeyType.ATTRIBUTE, removedKeys);

            for (AlarmRule alarm : entityRulesState.getAlarmRules().values()) {
                AlarmState alarmState = alarmStates.computeIfAbsent(alarm.getId(),
                        a -> new AlarmState(this.entityRulesState, tenantId, entityId, alarm, getOrInitPersistedAlarmState(alarm), dynamicPredicateValueCtx));
                stateChanged |= alarmState.process(requestCtx, ctx, msg, latestValues, update);
            }
        }
        return stateChanged;
    }

    private boolean processAttributesUpdateRequest(TbAlarmRuleRequestCtx requestCtx, TbMsg msg) throws ExecutionException, InterruptedException {
        return processAttributes(requestCtx, msg, DataConstants.CLIENT_SCOPE);
    }

    private boolean processAttributes(TbAlarmRuleRequestCtx requestCtx, TbMsg msg, String scope) throws ExecutionException, InterruptedException {
        boolean stateChanged = false;
        Set<AttributeKvEntry> attributes = JsonConverter.convertToAttributes(JsonParser.parseString(msg.getData()));
        if (!attributes.isEmpty()) {
            SnapshotUpdate update = merge(latestValues, attributes, scope);
            for (AlarmRule alarm : entityRulesState.getAlarmRules().values()) {
                AlarmState alarmState = alarmStates.computeIfAbsent(alarm.getId(),
                        a -> new AlarmState(this.entityRulesState, tenantId, entityId, alarm, getOrInitPersistedAlarmState(alarm), dynamicPredicateValueCtx));
                stateChanged |= alarmState.process(requestCtx, ctx, msg, latestValues, update);
            }
        }
        return stateChanged;
    }

    private boolean processTelemetry(TbAlarmRuleRequestCtx requestCtx, TbMsg msg) throws ExecutionException, InterruptedException {
        boolean stateChanged = false;
        Map<Long, List<KvEntry>> tsKvMap = JsonConverter.convertToSortedTelemetry(JsonParser.parseString(msg.getData()), msg.getMetaDataTs());
        // iterate over data by ts (ASC order).
        for (Map.Entry<Long, List<KvEntry>> entry : tsKvMap.entrySet()) {
            Long ts = entry.getKey();
            List<KvEntry> data = entry.getValue();
            SnapshotUpdate update = merge(latestValues, ts, data);
            if (update.hasUpdate()) {
                for (AlarmRule alarm : entityRulesState.getAlarmRules().values()) {
                    AlarmState alarmState = alarmStates.computeIfAbsent(alarm.getId(),
                            a -> new AlarmState(this.entityRulesState, tenantId, entityId, alarm, getOrInitPersistedAlarmState(alarm), dynamicPredicateValueCtx));
                    try {
                        stateChanged |= alarmState.process(requestCtx, ctx, msg, latestValues, update);
                    } catch (ApiUsageLimitsExceededException e) {
                        alarmStates.remove(alarm.getId());
                        throw e;
                    }
                }
            }
        }
        return stateChanged;
    }

    private SnapshotUpdate merge(DataSnapshot latestValues, Long newTs, List<KvEntry> data) {
        Set<AlarmConditionFilterKey> keys = new HashSet<>();
        for (KvEntry entry : data) {
            AlarmConditionFilterKey entityKey = new AlarmConditionFilterKey(AlarmConditionKeyType.TIME_SERIES, entry.getKey());
            if (latestValues.putValue(entityKey, newTs, toEntityValue(entry))) {
                keys.add(entityKey);
            }
        }
        latestValues.setTs(newTs);
        return new SnapshotUpdate(AlarmConditionKeyType.TIME_SERIES, keys);
    }

    private SnapshotUpdate merge(DataSnapshot latestValues, Set<AttributeKvEntry> attributes, String scope) {
        long newTs = 0;
        Set<AlarmConditionFilterKey> keys = new HashSet<>();
        for (AttributeKvEntry entry : attributes) {
            newTs = Math.max(newTs, entry.getLastUpdateTs());
            AlarmConditionFilterKey entityKey = new AlarmConditionFilterKey(AlarmConditionKeyType.ATTRIBUTE, entry.getKey());
            if (latestValues.putValue(entityKey, newTs, toEntityValue(entry))) {
                keys.add(entityKey);
            }
        }
        latestValues.setTs(newTs);
        return new SnapshotUpdate(AlarmConditionKeyType.ATTRIBUTE, keys);
    }

    private static EntityKeyType getKeyTypeFromScope(String scope) {
        return switch (scope) {
            case DataConstants.CLIENT_SCOPE -> EntityKeyType.CLIENT_ATTRIBUTE;
            case DataConstants.SHARED_SCOPE -> EntityKeyType.SHARED_ATTRIBUTE;
            case DataConstants.SERVER_SCOPE -> EntityKeyType.SERVER_ATTRIBUTE;
            default -> EntityKeyType.ATTRIBUTE;
        };
    }

    private DataSnapshot fetchLatestValues(TbAlarmRuleContext ctx, EntityId originator) throws ExecutionException, InterruptedException {
        Set<AlarmConditionFilterKey> entityKeysToFetch = entityRulesState.getEntityKeys();
        DataSnapshot result = new DataSnapshot(entityKeysToFetch);
        addEntityKeysToSnapshot(ctx, originator, entityKeysToFetch, result);
        return result;
    }

    private void addEntityKeysToSnapshot(TbAlarmRuleContext ctx, EntityId originator, Set<AlarmConditionFilterKey> entityKeysToFetch, DataSnapshot result) throws InterruptedException, ExecutionException {
        Set<String> attributeKeys = new HashSet<>();
        Set<String> latestTsKeys = new HashSet<>();

        for (AlarmConditionFilterKey entityKey : entityKeysToFetch) {
            String key = entityKey.getKey();
            switch (entityKey.getType()) {
                case ATTRIBUTE -> attributeKeys.add(key);
                case TIME_SERIES -> latestTsKeys.add(key);
            }
        }

        if (!latestTsKeys.isEmpty()) {
            List<TsKvEntry> data = ctx.getTimeseriesService().findLatest(tenantId, originator, latestTsKeys).get();
            for (TsKvEntry entry : data) {
                if (entry.getValue() != null) {
                    result.putValue(new AlarmConditionFilterKey(AlarmConditionKeyType.TIME_SERIES, entry.getKey()), entry.getTs(), toEntityValue(entry));
                }
            }
        }
        if (!attributeKeys.isEmpty()) {
            addToSnapshot(result, ctx.getAttributesService().find(tenantId, originator, AttributeScope.CLIENT_SCOPE, attributeKeys).get());
            addToSnapshot(result, ctx.getAttributesService().find(tenantId, originator, AttributeScope.SHARED_SCOPE, attributeKeys).get());
            addToSnapshot(result, ctx.getAttributesService().find(tenantId, originator, AttributeScope.SERVER_SCOPE, attributeKeys).get());
        }
    }

    private void addToSnapshot(DataSnapshot snapshot, List<AttributeKvEntry> data) {
        for (AttributeKvEntry entry : data) {
            if (entry.getValue() != null) {
                EntityKeyValue value = toEntityValue(entry);
                snapshot.putValue(new AlarmConditionFilterKey(AlarmConditionKeyType.ATTRIBUTE, entry.getKey()), entry.getLastUpdateTs(), value);
            }
        }
    }

    public static EntityKeyValue toEntityValue(KvEntry entry) {
        return switch (entry.getDataType()) {
            case STRING -> EntityKeyValue.fromString(entry.getStrValue().get());
            case LONG -> EntityKeyValue.fromLong(entry.getLongValue().get());
            case DOUBLE -> EntityKeyValue.fromDouble(entry.getDoubleValue().get());
            case BOOLEAN -> EntityKeyValue.fromBool(entry.getBooleanValue().get());
            case JSON -> EntityKeyValue.fromJson(entry.getJsonValue().get());
            default -> throw new RuntimeException("Can't parse entry:* " + entry.getDataType());
        };
    }

    private PersistedAlarmState getOrInitPersistedAlarmState(AlarmRule alarm) {
        if (pes != null) {
            PersistedAlarmState alarmState = pes.getAlarmStates().get(alarm.getUuidId().toString());
            if (alarmState == null) {
                alarmState = new PersistedAlarmState();
                alarmState.setCreateRuleStates(new HashMap<>());
                pes.getAlarmStates().put(alarm.getUuidId().toString(), alarmState);
            }
            return alarmState;
        } else {
            return null;
        }
    }

}
