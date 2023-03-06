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
package org.thingsboard.server.service.apiusage;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.rule.engine.api.MailService;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.ApiFeature;
import org.thingsboard.server.common.data.ApiUsageRecordKey;
import org.thingsboard.server.common.data.ApiUsageState;
import org.thingsboard.server.common.data.ApiUsageStateMailMessage;
import org.thingsboard.server.common.data.ApiUsageStateValue;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.TenantProfile;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.ApiUsageStateId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.TenantProfileId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageDataIterable;
import org.thingsboard.server.common.data.tenant.profile.TenantProfileConfiguration;
import org.thingsboard.server.common.data.tenant.profile.TenantProfileData;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TbCallback;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.common.msg.tools.SchedulerUtils;
import org.thingsboard.server.dao.tenant.TbTenantProfileCache;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.dao.usagerecord.ApiUsageStateService;
import org.thingsboard.server.gen.transport.TransportProtos.ToUsageStatsServiceMsg;
import org.thingsboard.server.gen.transport.TransportProtos.UsageStatsKVProto;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.service.executors.DbCallbackExecutorService;
import org.thingsboard.server.service.partition.AbstractPartitionBasedService;
import org.thingsboard.server.service.telemetry.InternalTelemetryService;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class DefaultTbApiUsageStateService extends AbstractPartitionBasedService<EntityId> implements TbApiUsageStateService {

    public static final FutureCallback<Integer> VOID_CALLBACK = new FutureCallback<Integer>() {
        @Override
        public void onSuccess(@Nullable Integer result) {
        }

        @Override
        public void onFailure(Throwable t) {
        }
    };
    private final TbClusterService clusterService;
    private final PartitionService partitionService;
    private final TenantService tenantService;
    private final TimeseriesService tsService;
    private final ApiUsageStateService apiUsageStateService;
    private final TbTenantProfileCache tenantProfileCache;
    private final MailService mailService;
    private final DbCallbackExecutorService dbExecutor;

    @Lazy
    @Autowired
    private InternalTelemetryService tsWsService;

    // Entities that should be processed on this server
    final Map<EntityId, BaseApiUsageState> myUsageStates = new ConcurrentHashMap<>();
    // Entities that should be processed on other servers
    final Map<EntityId, ApiUsageState> otherUsageStates = new ConcurrentHashMap<>();

    final Set<EntityId> deletedEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Value("${usage.stats.report.enabled:true}")
    private boolean enabled;

    @Value("${usage.stats.check.cycle:60000}")
    private long nextCycleCheckInterval;

    private final Lock updateLock = new ReentrantLock();

    private final ExecutorService mailExecutor;

    public DefaultTbApiUsageStateService(TbClusterService clusterService,
                                         PartitionService partitionService,
                                         TenantService tenantService,
                                         TimeseriesService tsService,
                                         ApiUsageStateService apiUsageStateService,
                                         TbTenantProfileCache tenantProfileCache,
                                         MailService mailService,
                                         DbCallbackExecutorService dbExecutor) {
        this.clusterService = clusterService;
        this.partitionService = partitionService;
        this.tenantService = tenantService;
        this.tsService = tsService;
        this.apiUsageStateService = apiUsageStateService;
        this.tenantProfileCache = tenantProfileCache;
        this.mailService = mailService;
        this.mailExecutor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("api-usage-svc-mail"));
        this.dbExecutor = dbExecutor;
    }

    @PostConstruct
    public void init() {
        super.init();
        if (enabled) {
            log.info("Starting api usage service.");
            scheduledExecutor.scheduleAtFixedRate(this::checkStartOfNextCycle, nextCycleCheckInterval, nextCycleCheckInterval, TimeUnit.MILLISECONDS);
            log.info("Started api usage service.");
        }
    }

    @Override
    protected String getServiceName() {
        return "API Usage";
    }

    @Override
    protected String getSchedulerExecutorName() {
        return "api-usage-scheduled";
    }

    @Override
    public void process(TbProtoQueueMsg<ToUsageStatsServiceMsg> msg, TbCallback callback) {
        ToUsageStatsServiceMsg statsMsg = msg.getValue();

        TenantId tenantId = TenantId.fromUUID(new UUID(statsMsg.getTenantIdMSB(), statsMsg.getTenantIdLSB()));
        EntityId ownerId;
        if (statsMsg.getCustomerIdMSB() != 0 && statsMsg.getCustomerIdLSB() != 0) {
            ownerId = new CustomerId(new UUID(statsMsg.getCustomerIdMSB(), statsMsg.getCustomerIdLSB()));
        } else {
            ownerId = tenantId;
        }

        processEntityUsageStats(tenantId, ownerId, statsMsg.getValuesList(), statsMsg.getServiceId());
        callback.onSuccess();
    }

    private void processEntityUsageStats(TenantId tenantId, EntityId ownerId, List<UsageStatsKVProto> values, String serviceId) {
        if (deletedEntities.contains(ownerId)) return;

        BaseApiUsageState usageState;
        List<TsKvEntry> updatedEntries;
        Map<ApiFeature, ApiUsageStateValue> result;

        updateLock.lock();
        try {
            usageState = getOrFetchState(tenantId, ownerId);
            long ts = usageState.getCurrentCycleTs();
            long hourTs = usageState.getCurrentHourTs();
            long newHourTs = SchedulerUtils.getStartOfCurrentHour();
            if (newHourTs != hourTs) {
                usageState.setHour(newHourTs);
            }
            updatedEntries = new ArrayList<>(ApiUsageRecordKey.values().length);
            Set<ApiFeature> apiFeatures = new HashSet<>();
            for (UsageStatsKVProto statsItem : values) {
                ApiUsageRecordKey recordKey = ApiUsageRecordKey.valueOf(statsItem.getKey());
                ApiStatsKey statsKey;
                if (statsItem.getEntityIdMSB() != 0 && statsItem.getEntityIdLSB() != 0) {
                    if (!recordKey.isCountPerEntity()) {
                        log.warn("Per-entity stats not supported for {}", recordKey);
                        continue;
                    }
                    UUID entityId = new UUID(statsItem.getEntityIdMSB(), statsItem.getEntityIdLSB());
                    statsKey = ApiStatsKey.of(recordKey, entityId);
                } else {
                    statsKey = ApiStatsKey.of(recordKey);
                }

                long newValue = usageState.calculate(statsKey, statsItem.getValue(), serviceId);
                long newHourlyValue = usageState.calculateHourly(statsKey, statsItem.getValue(), serviceId);

                updatedEntries.add(new BasicTsKvEntry(ts, new LongDataEntry(statsKey.getEntryKey(false), newValue)));
                updatedEntries.add(new BasicTsKvEntry(newHourTs, new LongDataEntry(statsKey.getEntryKey(true), newHourlyValue)));
                if (recordKey.getApiFeature() != null) {
                    apiFeatures.add(recordKey.getApiFeature());
                }
            }
            if (usageState.getEntityType() == EntityType.TENANT && !usageState.getEntityId().equals(TenantId.SYS_TENANT_ID)) {
                result = ((TenantApiUsageState) usageState).checkStateUpdatedDueToThreshold(apiFeatures);
            } else {
                result = Collections.emptyMap();
            }
        } finally {
            updateLock.unlock();
        }
        tsWsService.saveAndNotifyInternal(tenantId, usageState.getApiUsageState().getId(), updatedEntries, VOID_CALLBACK);
        if (!result.isEmpty()) {
            persistAndNotify(usageState, result);
        }
    }

    @Override
    public ApiUsageState getApiUsageState(TenantId tenantId) {
        TenantApiUsageState tenantState = (TenantApiUsageState) myUsageStates.get(tenantId);
        if (tenantState != null) {
            return tenantState.getApiUsageState();
        } else {
            ApiUsageState state = otherUsageStates.get(tenantId);
            if (state != null) {
                return state;
            } else {
                if (partitionService.resolve(ServiceType.TB_CORE, tenantId, tenantId).isMyPartition()) {
                    return getOrFetchState(tenantId, tenantId).getApiUsageState();
                } else {
                    state = otherUsageStates.get(tenantId);
                    if (state == null) {
                        state = apiUsageStateService.findTenantApiUsageState(tenantId);
                        if (state != null) {
                            otherUsageStates.put(tenantId, state);
                        }
                    }
                    return state;
                }
            }
        }
    }

    @Override
    public void onApiUsageStateUpdate(TenantId tenantId) {
        otherUsageStates.remove(tenantId);
    }

    @Override
    public void onTenantProfileUpdate(TenantProfileId tenantProfileId) {
        log.info("[{}] On Tenant Profile Update", tenantProfileId);
        TenantProfile tenantProfile = tenantProfileCache.get(tenantProfileId);
        updateLock.lock();
        try {
            myUsageStates.values().stream()
                    .filter(state -> state.getEntityType() == EntityType.TENANT)
                    .map(state -> (TenantApiUsageState) state)
                    .forEach(state -> {
                        if (tenantProfile.getId().equals(state.getTenantProfileId())) {
                            updateTenantState(state, tenantProfile);
                        }
                    });
        } finally {
            updateLock.unlock();
        }
    }

    @Override
    public void onTenantUpdate(TenantId tenantId) {
        log.info("[{}] On Tenant Update.", tenantId);
        TenantProfile tenantProfile = tenantProfileCache.get(tenantId);
        updateLock.lock();
        try {
            TenantApiUsageState state = (TenantApiUsageState) myUsageStates.get(tenantId);
            if (state != null && !state.getTenantProfileId().equals(tenantProfile.getId())) {
                updateTenantState(state, tenantProfile);
            }
        } finally {
            updateLock.unlock();
        }
    }

    private void updateTenantState(TenantApiUsageState state, TenantProfile profile) {
        TenantProfileData oldProfileData = state.getTenantProfileData();
        state.setTenantProfileId(profile.getId());
        state.setTenantProfileData(profile.getProfileData());
        Map<ApiFeature, ApiUsageStateValue> result = state.checkStateUpdatedDueToThresholds();
        if (!result.isEmpty()) {
            persistAndNotify(state, result);
        }
        updateProfileThresholds(state.getTenantId(), state.getApiUsageState().getId(),
                oldProfileData.getConfiguration(), profile.getProfileData().getConfiguration());
    }

    private void addEntityState(TopicPartitionInfo tpi, BaseApiUsageState state) {
        EntityId entityId = state.getEntityId();
        Set<EntityId> entityIds = partitionedEntities.get(tpi);
        if (entityIds != null) {
            entityIds.add(entityId);
            myUsageStates.put(entityId, state);
        } else {
            log.debug("[{}] belongs to external partition {}", entityId, tpi.getFullTopicName());
            throw new RuntimeException(entityId.getEntityType() + " belongs to external partition " + tpi.getFullTopicName() + "!");
        }
    }

    private void updateProfileThresholds(TenantId tenantId, ApiUsageStateId id,
                                         TenantProfileConfiguration oldData, TenantProfileConfiguration newData) {
        long ts = System.currentTimeMillis();
        List<TsKvEntry> profileThresholds = new ArrayList<>();
        for (ApiUsageRecordKey key : ApiUsageRecordKey.values()) {
            if (key.getApiLimitKey() == null) continue;
            long newProfileThreshold = newData.getProfileThreshold(key);
            if (oldData == null || oldData.getProfileThreshold(key) != newProfileThreshold) {
                log.info("[{}] Updating profile threshold [{}]:[{}]", tenantId, key, newProfileThreshold);
                profileThresholds.add(new BasicTsKvEntry(ts, new LongDataEntry(key.getApiLimitKey(), newProfileThreshold)));
            }
        }
        if (!profileThresholds.isEmpty()) {
            tsWsService.saveAndNotifyInternal(tenantId, id, profileThresholds, VOID_CALLBACK);
        }
    }

    public void onTenantDelete(TenantId tenantId) {
        deletedEntities.add(tenantId);
        myUsageStates.remove(tenantId);
        otherUsageStates.remove(tenantId);
    }

    @Override
    public void onCustomerDelete(CustomerId customerId) {
        deletedEntities.add(customerId);
        myUsageStates.remove(customerId);
    }

    @Override
    protected void cleanupEntityOnPartitionRemoval(EntityId entityId) {
        myUsageStates.remove(entityId);
    }

    private void persistAndNotify(BaseApiUsageState state, Map<ApiFeature, ApiUsageStateValue> result) {
        log.info("[{}] Detected update of the API state for {}: {}", state.getEntityId(), state.getEntityType(), result);
        apiUsageStateService.update(state.getApiUsageState());
        clusterService.onApiStateChange(state.getApiUsageState(), null);
        long ts = System.currentTimeMillis();
        List<TsKvEntry> stateTelemetry = new ArrayList<>();
        result.forEach((apiFeature, aState) -> stateTelemetry.add(new BasicTsKvEntry(ts, new StringDataEntry(apiFeature.getApiStateKey(), aState.name()))));
        tsWsService.saveAndNotifyInternal(state.getTenantId(), state.getApiUsageState().getId(), stateTelemetry, VOID_CALLBACK);

        if (state.getEntityType() == EntityType.TENANT && !state.getEntityId().equals(TenantId.SYS_TENANT_ID)) {
            String email = tenantService.findTenantById(state.getTenantId()).getEmail();
            if (StringUtils.isNotEmpty(email)) {
                result.forEach((apiFeature, stateValue) -> {
                    mailExecutor.submit(() -> {
                        try {
                            mailService.sendApiFeatureStateEmail(apiFeature, stateValue, email, createStateMailMessage((TenantApiUsageState) state, apiFeature, stateValue));
                        } catch (ThingsboardException e) {
                            log.warn("[{}] Can't send update of the API state to tenant with provided email [{}]", state.getTenantId(), email, e);
                        }
                    });
                });
            } else {
                log.warn("[{}] Can't send update of the API state to tenant with empty email!", state.getTenantId());
            }
        }
    }

    private ApiUsageStateMailMessage createStateMailMessage(TenantApiUsageState state, ApiFeature apiFeature, ApiUsageStateValue stateValue) {
        StateChecker checker = getStateChecker(stateValue);
        for (ApiUsageRecordKey apiUsageRecordKey : ApiUsageRecordKey.getKeys(apiFeature)) {
            long threshold = state.getProfileThreshold(apiUsageRecordKey);
            long warnThreshold = state.getProfileWarnThreshold(apiUsageRecordKey);
            long value = state.get(ApiStatsKey.of(apiUsageRecordKey));
            if (checker.check(threshold, warnThreshold, value)) {
                return new ApiUsageStateMailMessage(apiUsageRecordKey, threshold, value);
            }
        }
        return null;
    }

    private StateChecker getStateChecker(ApiUsageStateValue stateValue) {
        if (ApiUsageStateValue.ENABLED.equals(stateValue)) {
            return (t, wt, v) -> true;
        } else if (ApiUsageStateValue.WARNING.equals(stateValue)) {
            return (t, wt, v) -> v < t && v >= wt;
        } else {
            return (t, wt, v) -> v >= t;
        }
    }

    @Override
    public ApiUsageState findApiUsageStateById(TenantId tenantId, ApiUsageStateId id) {
        return apiUsageStateService.findApiUsageStateById(tenantId, id);
    }

    private interface StateChecker {
        boolean check(long threshold, long warnThreshold, long value);
    }

    private void checkStartOfNextCycle() {
        updateLock.lock();
        try {
            long now = System.currentTimeMillis();
            myUsageStates.values().forEach(state -> {
                if ((state.getNextCycleTs() < now) && (now - state.getNextCycleTs() < TimeUnit.HOURS.toMillis(1))) {
                    state.setCycles(state.getNextCycleTs(), SchedulerUtils.getStartOfNextNextMonth());
                    saveNewCounts(state, Stream.of(ApiUsageRecordKey.values())
                            .map(ApiStatsKey::of).collect(Collectors.toList()));
                    if (state.getEntityType() == EntityType.TENANT && !state.getEntityId().equals(TenantId.SYS_TENANT_ID)) {
                        TenantId tenantId = state.getTenantId();
                        updateTenantState((TenantApiUsageState) state, tenantProfileCache.get(tenantId));
                    }
                }
            });
        } finally {
            updateLock.unlock();
        }
    }

    private void saveNewCounts(BaseApiUsageState state, List<ApiStatsKey> keys) {
        List<TsKvEntry> counts = keys.stream()
                .map(key -> new BasicTsKvEntry(state.getCurrentCycleTs(), new LongDataEntry(key.getEntryKey(false), 0L)))
                .collect(Collectors.toList());

        tsWsService.saveAndNotifyInternal(state.getTenantId(), state.getApiUsageState().getId(), counts, VOID_CALLBACK);
    }

    BaseApiUsageState getOrFetchState(TenantId tenantId, EntityId ownerId) {
        if (ownerId == null || ownerId.isNullUid()) {
            ownerId = tenantId;
        }
        BaseApiUsageState state = myUsageStates.get(ownerId);
        if (state != null) {
            return state;
        }

        ApiUsageState storedState = apiUsageStateService.findApiUsageStateByEntityId(ownerId);
        if (storedState == null) {
            try {
                storedState = apiUsageStateService.createDefaultApiUsageState(tenantId, ownerId);
            } catch (Exception e) {
                storedState = apiUsageStateService.findApiUsageStateByEntityId(ownerId);
            }
        }
        if (ownerId.getEntityType() == EntityType.TENANT) {
            if (!ownerId.equals(TenantId.SYS_TENANT_ID)) {
                state = new TenantApiUsageState(tenantProfileCache.get((TenantId) ownerId), storedState);
            } else {
                state = new TenantApiUsageState(storedState);
            }
        } else {
            state = new CustomerApiUsageState(storedState);
        }

        List<ApiStatsKey> newCounts = new ArrayList<>();
        try {
            List<TsKvEntry> dbValues = tsService.findAllLatest(tenantId, storedState.getId()).get();
            // limit for current month ?
            for (ApiUsageRecordKey key : ApiUsageRecordKey.values()) {
                for (TsKvEntry tsKvEntry : dbValues) {
                    String entryKey = tsKvEntry.getKey();
                    Long value = tsKvEntry.getLongValue().orElse(0L);
                    UUID entityId = ApiStatsKey.getEntityId(entryKey);

                    if (ApiStatsKey.isMonthly(key, entryKey)) {
                        boolean outdated = tsKvEntry.getTs() != state.getCurrentCycleTs();
                        if (outdated) {
                            newCounts.add(ApiStatsKey.of(key));
                            continue;
                        }
                        if (entityId == null) {
                            state.set(ApiStatsKey.of(key), value);
                        } else {
                            state.set(ApiStatsKey.of(key, entityId), value);
                        }
                    } else if (ApiStatsKey.isHourly(key, entryKey)) {
                        boolean outdated = tsKvEntry.getTs() != state.getCurrentHourTs();
                        if (outdated) continue;
                        if (entityId == null) {
                            state.setHourly(ApiStatsKey.of(key), value);
                        } else {
                            state.setHourly(ApiStatsKey.of(key, entityId), value);
                        }
                    }
                }
            }
            log.debug("[{}] Initialized state: {}", ownerId, storedState);
            TopicPartitionInfo tpi = partitionService.resolve(ServiceType.TB_CORE, tenantId, ownerId);
            if (tpi.isMyPartition()) {
                addEntityState(tpi, state);
            } else {
                otherUsageStates.put(ownerId, state.getApiUsageState());
            }
            saveNewCounts(state, newCounts);
        } catch (InterruptedException | ExecutionException e) {
            log.warn("[{}] Failed to fetch api usage state from db.", tenantId, e);
        }

        return state;
    }

    @Override
    protected void onRepartitionEvent() {
        otherUsageStates.entrySet().removeIf(entry ->
                partitionService.resolve(ServiceType.TB_CORE, entry.getValue().getTenantId(), entry.getKey()).isMyPartition());
    }

    @Override
    protected Map<TopicPartitionInfo, List<ListenableFuture<?>>> onAddedPartitions(Set<TopicPartitionInfo> addedPartitions) {
        var result = new HashMap<TopicPartitionInfo, List<ListenableFuture<?>>>();
        try {
            log.info("Initializing tenant states.");
            updateLock.lock();
            try {
                PageDataIterable<Tenant> tenantIterator = new PageDataIterable<>(tenantService::findTenants, 1024);
                for (Tenant tenant : tenantIterator) {
                    TopicPartitionInfo tpi = partitionService.resolve(ServiceType.TB_CORE, tenant.getId(), tenant.getId());
                    if (addedPartitions.contains(tpi)) {
                        if (!myUsageStates.containsKey(tenant.getId()) && tpi.isMyPartition()) {
                            log.debug("[{}] Initializing tenant state.", tenant.getId());
                            result.computeIfAbsent(tpi, tmp -> new ArrayList<>()).add(dbExecutor.submit(() -> {
                                try {
                                    updateTenantState((TenantApiUsageState) getOrFetchState(tenant.getId(), tenant.getId()), tenantProfileCache.get(tenant.getTenantProfileId()));
                                    log.debug("[{}] Initialized tenant state.", tenant.getId());
                                } catch (Exception e) {
                                    log.warn("[{}] Failed to initialize tenant API state", tenant.getId(), e);
                                }
                                return null;
                            }));
                        }
                    } else {
                        log.debug("[{}][{}] Tenant doesn't belong to current partition. tpi [{}]", tenant.getName(), tenant.getId(), tpi);
                    }
                }
            } finally {
                updateLock.unlock();
            }
        } catch (Exception e) {
            log.warn("Unknown failure", e);
        }
        return result;
    }

    @PreDestroy
    private void destroy() {
        super.stop();
        if (mailExecutor != null) {
            mailExecutor.shutdownNow();
        }
    }
}
