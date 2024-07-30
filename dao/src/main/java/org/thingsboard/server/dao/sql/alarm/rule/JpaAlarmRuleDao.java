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
package org.thingsboard.server.dao.sql.alarm.rule;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.alarm.rule.AlarmRule;
import org.thingsboard.server.common.data.alarm.rule.AlarmRuleInfo;
import org.thingsboard.server.common.data.id.AlarmRuleId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.alarm.rule.AlarmRuleDao;
import org.thingsboard.server.dao.model.sql.AlarmRuleEntity;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@SqlDao
public class JpaAlarmRuleDao extends JpaAbstractDao<AlarmRuleEntity, AlarmRule> implements AlarmRuleDao {

    @Autowired
    private AlarmRuleRepository alarmRuleRepository;

    @Override
    protected Class<AlarmRuleEntity> getEntityClass() {
        return AlarmRuleEntity.class;
    }

    @Override
    protected JpaRepository<AlarmRuleEntity, UUID> getRepository() {
        return alarmRuleRepository;
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.ALARM_RULE;
    }

    @Override
    public PageData<AlarmRuleInfo> findAlarmRuleInfosByTenantId(UUID tenantId, PageLink pageLink) {
        return DaoUtil.toPageData(alarmRuleRepository
                .findInfosByTenantId(
                        tenantId,
                        pageLink.getTextSearch(),
                        DaoUtil.toPageable(pageLink)));
    }

    @Override
    public PageData<AlarmRule> findAlarmRulesByTenantId(UUID tenantId, PageLink pageLink) {
        return DaoUtil.toPageData(alarmRuleRepository
                .findByTenantId(
                        tenantId,
                        pageLink.getTextSearch(),
                        DaoUtil.toPageable(pageLink)));
    }

    @Override
    public PageData<AlarmRule> findEnabledAlarmRulesByTenantId(UUID tenantId, PageLink pageLink) {
        return DaoUtil.toPageData(alarmRuleRepository
                .findByTenantIdAndEnabled(
                        tenantId,
                        pageLink.getTextSearch(),
                        DaoUtil.toPageable(pageLink)));
    }

    @Override
    public List<JsonNode> findRuleNodeStatesByRuleChainIdAndType(DeviceProfileId deviceProfileId, RuleChainId ruleChainId, String type) {
        return alarmRuleRepository.findRuleNodeStatesByRuleChainIdAndRuleNodeType(deviceProfileId.getId(), ruleChainId.getId(), type)
                .stream()
                .map(JacksonUtil::toJsonNode)
                .collect(Collectors.toList());
    }

    @Override
    public AlarmRule findByTenantIdAndExternalId(UUID tenantId, UUID externalId) {
        return DaoUtil.getData(alarmRuleRepository.findByTenantIdAndExternalId(tenantId, externalId));
    }

    @Override
    public AlarmRule findByTenantIdAndName(UUID tenantId, String name) {
        return DaoUtil.getData(alarmRuleRepository.findByTenantIdAndName(tenantId, name));
    }

    @Override
    public PageData<AlarmRule> findByTenantId(UUID tenantId, PageLink pageLink) {
        return findAlarmRulesByTenantId(tenantId, pageLink);
    }

    @Override
    public AlarmRuleId getExternalIdByInternal(AlarmRuleId internalId) {
        return Optional.ofNullable(alarmRuleRepository.getExternalIdById(internalId.getId()))
                .map(AlarmRuleId::new).orElse(null);
    }
}
