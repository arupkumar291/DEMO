/**
 * Copyright © 2016-2019 The Thingsboard Authors
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
package org.thingsboard.server.dao.sqlts.psql;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sqlts.dictionary.TsKvDictionary;
import org.thingsboard.server.dao.model.sqlts.dictionary.TsKvDictionaryCompositeKey;
import org.thingsboard.server.dao.model.sqlts.psql.TsKvEntity;
import org.thingsboard.server.dao.sqlts.AbstractRelationalSqlTimeseriesDao;
import org.thingsboard.server.dao.sqlts.AbstractTimeseriesInsertRepository;
import org.thingsboard.server.dao.sqlts.dictionary.TsKvDictionaryRepository;
import org.thingsboard.server.dao.timeseries.TimeseriesDao;
import org.thingsboard.server.dao.util.PsqlDao;
import org.thingsboard.server.dao.util.SqlTsDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


@Component
@Slf4j
@SqlTsDao
@PsqlDao
public class JpaPsqlTimeseriesDao extends AbstractRelationalSqlTimeseriesDao<TsKvEntity> implements TimeseriesDao {

    private final ConcurrentMap<String, Integer> tsKvDictionaryMap = new ConcurrentHashMap<>();

    private static final ReentrantLock tsCreationLock = new ReentrantLock();

    private AtomicInteger keyCounter = new AtomicInteger(0);

    @Autowired
    private TsKvDictionaryRepository dictionaryRepository;

    @Autowired
    private TsKvPsqlRepository tsKvRepository;

    @Autowired
    private AbstractTimeseriesInsertRepository insertRepository;

    @Override
    public ListenableFuture<List<TsKvEntry>> findAllAsync(TenantId tenantId, EntityId entityId, List<ReadTsKvQuery> queries) {
        return processFindAllAsync(tenantId, entityId, queries);
    }

    @Override
    public ListenableFuture<Void> save(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry, long ttl) {
        String strKey = tsKvEntry.getKey();
        Integer keyId = getOrSaveKeyId(strKey);
        TsKvEntity entity = new TsKvEntity();
        entity.setEntityId(entityId.getId());
        entity.setTs(tsKvEntry.getTs());
        entity.setKey(keyId);
        entity.setStrValue(tsKvEntry.getStrValue().orElse(null));
        entity.setDoubleValue(tsKvEntry.getDoubleValue().orElse(null));
        entity.setLongValue(tsKvEntry.getLongValue().orElse(null));
        entity.setBooleanValue(tsKvEntry.getBooleanValue().orElse(null));
        log.trace("Saving entity: {}", entity);
        return insertService.submit(() -> {
            insertRepository.saveOrUpdate(entity);
            return null;
        });
    }

    @Override
    public ListenableFuture<Void> remove(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
        String strKey = query.getKey();
        Integer keyId = getOrSaveKeyId(strKey);
        tsKvRepository.delete(
                entityId.getId(),
                keyId,
                query.getStartTs(),
                query.getEndTs());
        return insertService.submit(() -> null);
    }

    @Override
    public ListenableFuture<Void> removeLatest(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
        return getRemoveLatestFuture(tenantId, entityId, query);
    }

    @Override
    public ListenableFuture<Void> saveLatest(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry) {
        return getSaveLatestFuture(entityId, tsKvEntry);
    }

    @Override
    public ListenableFuture<TsKvEntry> findLatest(TenantId tenantId, EntityId entityId, String key) {
        return getFindLatestFuture(entityId, key);
    }

    @Override
    public ListenableFuture<List<TsKvEntry>> findAllLatest(TenantId tenantId, EntityId entityId) {
        return getFindAllLatestFuture(entityId);
    }

    @Override
    public ListenableFuture<Void> savePartition(TenantId tenantId, EntityId entityId, long tsKvEntryTs, String key, long ttl) {
        return insertService.submit(() -> null);
    }

    @Override
    public ListenableFuture<Void> removePartition(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
        return service.submit(() -> null);
    }

    protected ListenableFuture<Optional<TsKvEntry>> findAndAggregateAsync(EntityId entityId, String key, long startTs, long endTs, long ts, Aggregation aggregation) {
        List<CompletableFuture<TsKvEntity>> entitiesFutures = new ArrayList<>();
        switchAgregation(entityId, key, startTs, endTs, aggregation, entitiesFutures);
        return Futures.transform(setFutures(entitiesFutures), entity -> {
            if (entity != null && entity.isNotEmpty()) {
                entity.setEntityId(entityId.getId());
                entity.setStrKey(key);
                entity.setTs(ts);
                return Optional.of(DaoUtil.getData(entity));
            } else {
                return Optional.empty();
            }
        });
    }

    protected ListenableFuture<List<TsKvEntry>> findAllAsyncWithLimit(EntityId entityId, ReadTsKvQuery query) {
        Integer keyId = getOrSaveKeyId(query.getKey());
        List<TsKvEntity> tsKvEntities = tsKvRepository.findAllWithLimit(
                entityId.getId(),
                keyId,
                query.getStartTs(),
                query.getEndTs(),
                new PageRequest(0, query.getLimit(),
                        new Sort(Sort.Direction.fromString(
                                query.getOrderBy()), "ts")));
        tsKvEntities.forEach(tsKvEntity -> tsKvEntity.setStrKey(query.getKey()));
        return Futures.immediateFuture(DaoUtil.convertDataList(tsKvEntities));
    }

    private Integer getOrSaveKeyId(String strKey) {
        Integer keyId = tsKvDictionaryMap.get(strKey);
        if (keyId == null) {
            Optional<TsKvDictionary> tsKvDictionaryOptional;
            tsKvDictionaryOptional = dictionaryRepository.findById(new TsKvDictionaryCompositeKey(strKey));
            if (!tsKvDictionaryOptional.isPresent()) {
                tsCreationLock.lock();
                try {
                    tsKvDictionaryOptional = dictionaryRepository.findById(new TsKvDictionaryCompositeKey(strKey));
                    if (!tsKvDictionaryOptional.isPresent()) {
                        TsKvDictionary tsKvDictionary = new TsKvDictionary();
                        tsKvDictionary.setKey(strKey);
                        tsKvDictionary.setKeyId(keyCounter.getAndIncrement());
                        try {
                            TsKvDictionary saved = dictionaryRepository.save(tsKvDictionary);
                            tsKvDictionaryMap.put(saved.getKey(), saved.getKeyId());
                            keyId = saved.getKeyId();
                        } catch (ConstraintViolationException e) {
                            tsKvDictionaryOptional = dictionaryRepository.findById(new TsKvDictionaryCompositeKey(strKey));
                            TsKvDictionary dictionary = tsKvDictionaryOptional.orElseThrow(() -> new RuntimeException("Failed to get TsKvDictionary entity from DB!"));
                            tsKvDictionaryMap.put(dictionary.getKey(), dictionary.getKeyId());
                            keyId = dictionary.getKeyId();
                        }
                    } else {
                        keyId = tsKvDictionaryOptional.get().getKeyId();
                    }
                } finally {
                    tsCreationLock.unlock();
                }
            } else {
                keyId = tsKvDictionaryOptional.get().getKeyId();
            }
        }
        return keyId;
    }

    private void switchAgregation(EntityId entityId, String key, long startTs, long endTs, Aggregation aggregation, List<CompletableFuture<TsKvEntity>> entitiesFutures) {
        switch (aggregation) {
            case AVG:
                findAvg(entityId.getId(), key, startTs, endTs, entitiesFutures);
                break;
            case MAX:
                findMax(entityId.getId(), key, startTs, endTs, entitiesFutures);
                break;
            case MIN:
                findMin(entityId.getId(), key, startTs, endTs, entitiesFutures);
                break;
            case SUM:
                findSum(entityId.getId(), key, startTs, endTs, entitiesFutures);
                break;
            case COUNT:
                findCount(entityId.getId(), key, startTs, endTs, entitiesFutures);
                break;
            default:
                throw new IllegalArgumentException("Not supported aggregation type: " + aggregation);
        }
    }

    private void findCount(UUID entityId, String key, long startTs, long endTs, List<CompletableFuture<TsKvEntity>> entitiesFutures) {
        Integer keyId = getOrSaveKeyId(key);
        entitiesFutures.add(tsKvRepository.findCount(
                entityId,
                keyId,
                startTs,
                endTs));
    }

    private void findSum(UUID entityId, String key, long startTs, long endTs, List<CompletableFuture<TsKvEntity>> entitiesFutures) {
        Integer keyId = getOrSaveKeyId(key);
        entitiesFutures.add(tsKvRepository.findSum(
                entityId,
                keyId,
                startTs,
                endTs));
    }

    private void findMin(UUID entityId, String key, long startTs, long endTs, List<CompletableFuture<TsKvEntity>> entitiesFutures) {
        Integer keyId = getOrSaveKeyId(key);
        entitiesFutures.add(tsKvRepository.findStringMin(
                entityId,
                keyId,
                startTs,
                endTs));
        entitiesFutures.add(tsKvRepository.findNumericMin(
                entityId,
                keyId,
                startTs,
                endTs));
    }

    private void findMax(UUID entityId, String key, long startTs, long endTs, List<CompletableFuture<TsKvEntity>> entitiesFutures) {
        Integer keyId = getOrSaveKeyId(key);
        entitiesFutures.add(tsKvRepository.findStringMax(
                entityId,
                keyId,
                startTs,
                endTs));
        entitiesFutures.add(tsKvRepository.findNumericMax(
                entityId,
                keyId,
                startTs,
                endTs));
    }

    private void findAvg(UUID entityId, String key, long startTs, long endTs, List<CompletableFuture<TsKvEntity>> entitiesFutures) {
        Integer keyId = getOrSaveKeyId(key);
        entitiesFutures.add(tsKvRepository.findAvg(
                entityId,
                keyId,
                startTs,
                endTs));
    }

}