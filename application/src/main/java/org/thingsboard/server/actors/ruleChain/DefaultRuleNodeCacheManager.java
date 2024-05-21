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
package org.thingsboard.server.actors.ruleChain;

import lombok.RequiredArgsConstructor;
import org.springframework.util.SerializationUtils;
import org.thingsboard.rule.engine.api.RuleNodeCacheManager;
import org.thingsboard.server.cache.RedisSetCacheProvider;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.queue.TbMsgCallback;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class DefaultRuleNodeCacheManager implements RuleNodeCacheManager {

    private final RuleNodeId ruleNodeId;
    private final RedisSetCacheProvider cache;

    @Override
    public void add(String key, String value) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, key);
        cache.add(cacheKey, value.getBytes());
    }

    @Override
    public void add(String key, EntityId id) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, key);
        cache.add(cacheKey, SerializationUtils.serialize(id));
    }

    @Override
    public void add(EntityId key, Integer partition, TbMsg value) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, partition, key);
        cache.add(cacheKey, TbMsg.toByteArray(value));
    }

    @Override
    public void removeTbMsgList(EntityId key, Integer partition, List<TbMsg> values) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, partition, key);
        byte[][] valuesBytes = tbMsgListToBytes(values);
        cache.remove(cacheKey, valuesBytes);
    }

    @Override
    public void removeStringList(String key, List<String> values) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, key);
        byte[][] valuesBytes = stringListToBytes(values);
        cache.remove(cacheKey, valuesBytes);
    }

    @Override
    public void removeEntityIdList(String key, List<EntityId> values) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, key);
        byte[][] valuesBytes = entityIdListToBytes(values);
        cache.remove(cacheKey, valuesBytes);
    }

    @Override
    public Set<String> getStrings(String key) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, key);
        return toStringSet(cache.get(cacheKey));
    }

    @Override
    public Set<EntityId> getEntityIds(String key) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, key);
        return toEntityIdSet(cache.get(cacheKey));
    }

    @Override
    public Set<TbMsg> getTbMsgs(EntityId key, Integer partition) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, partition, key);
        return toTbMsgSet(cache.get(cacheKey));
    }

    @Override
    public void evictTbMsgs(EntityId key, Integer partition) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, partition, key);
        cache.evict(cacheKey);
    }

    @Override
    public void evict(String key) {
        byte[] cacheKey = toRuleNodeCacheKey(ruleNodeId, key);
        cache.evict(cacheKey);
    }

    private byte[][] stringListToBytes(List<String> values) {
        return values.stream()
                .map(String::getBytes)
                .toArray(byte[][]::new);
    }

    private byte[][] entityIdListToBytes(List<EntityId> values) {
        return values.stream()
                .map(SerializationUtils::serialize)
                .toArray(byte[][]::new);
    }

    private byte[][] tbMsgListToBytes(List<TbMsg> values) {
        return values.stream()
                .map(TbMsg::toByteArray)
                .toArray(byte[][]::new);
    }

    private Set<String> toStringSet(Set<byte[]> values) {
        return values.stream()
                .map(String::new)
                .collect(Collectors.toSet());
    }

    private Set<EntityId> toEntityIdSet(Set<byte[]> values) {
        return values.stream()
                .map(bytes -> (EntityId) SerializationUtils.deserialize(bytes))
                .collect(Collectors.toSet());
    }

    private Set<TbMsg> toTbMsgSet(Set<byte[]> values) {
        return values.stream()
                .map(bytes -> TbMsg.fromBytes(bytes, TbMsgCallback.EMPTY))
                .collect(Collectors.toSet());
    }

    private byte[] toRuleNodeCacheKey(RuleNodeId ruleNodeId, String key) {
        return String.format("%s::%s", ruleNodeId.getId().toString(), key).getBytes();
    }

    private byte[] toRuleNodeCacheKey(RuleNodeId ruleNodeId, Integer partition, EntityId key) {
        return String.format("%s::%s::%s", ruleNodeId.getId().toString(), partition, key.getId().toString()).getBytes();
    }

}
