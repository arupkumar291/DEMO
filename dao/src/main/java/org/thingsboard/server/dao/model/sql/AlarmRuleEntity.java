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
package org.thingsboard.server.dao.model.sql;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLJsonPGObjectJsonbType;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.alarm.rule.AlarmRule;
import org.thingsboard.server.common.data.alarm.rule.condition.AlarmRuleConfiguration;
import org.thingsboard.server.common.data.id.AlarmRuleId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.model.BaseSqlEntity;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.util.mapping.JsonConverter;

import java.util.UUID;

import static org.thingsboard.server.dao.model.ModelConstants.ALARM_RULE_ALARM_TYPE_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.ALARM_RULE_COLUMN_FAMILY_NAME;
import static org.thingsboard.server.dao.model.ModelConstants.ALARM_RULE_DESCRIPTION_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.ALARM_RULE_ENABLED_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.ALARM_RULE_NAME_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.ALARM_RULE_TENANT_ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.EXTERNAL_ID_PROPERTY;

@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = ALARM_RULE_COLUMN_FAMILY_NAME)
@Entity
public class AlarmRuleEntity extends BaseSqlEntity<AlarmRule> {

    @Column(name = ALARM_RULE_TENANT_ID_PROPERTY)
    private UUID tenantId;

    @Column(name = ALARM_RULE_ALARM_TYPE_PROPERTY)
    private String alarmType;

    @Column(name = ALARM_RULE_NAME_PROPERTY)
    private String name;

    @Column(name = ALARM_RULE_ENABLED_PROPERTY)
    private boolean enabled;

    @Convert(converter = JsonConverter.class)
    @JdbcType(PostgreSQLJsonPGObjectJsonbType.class)
    @Column(name = ModelConstants.ALARM_RULE_CONFIGURATION_PROPERTY)
    private JsonNode configuration;

    @Column(name = ALARM_RULE_DESCRIPTION_PROPERTY)
    private String description;

    @Column(name = EXTERNAL_ID_PROPERTY)
    private UUID externalId;

    public AlarmRuleEntity() {
        super();
    }

    public AlarmRuleEntity(AlarmRule alarmRule) {
        if (alarmRule.getId() != null) {
            this.setUuid(alarmRule.getUuidId());
        }
        this.setCreatedTime(alarmRule.getCreatedTime());
        if (alarmRule.getTenantId() != null) {
            this.tenantId = alarmRule.getTenantId().getId();
        }
        this.alarmType = alarmRule.getAlarmType();
        this.name = alarmRule.getName();
        this.enabled = alarmRule.isEnabled();
        this.configuration = JacksonUtil.valueToTree(alarmRule.getConfiguration());
        this.description = alarmRule.getDescription();
        if (alarmRule.getExternalId() != null) {
            this.externalId = alarmRule.getExternalId().getId();
        }
    }

    @Override
    public AlarmRule toData() {
        AlarmRule alarmRule = new AlarmRule(new AlarmRuleId(id));
        alarmRule.setCreatedTime(createdTime);
        if (tenantId != null) {
            alarmRule.setTenantId(TenantId.fromUUID(tenantId));
        }
        alarmRule.setAlarmType(alarmType);
        alarmRule.setName(name);
        alarmRule.setEnabled(enabled);
        alarmRule.setConfiguration(JacksonUtil.treeToValue(configuration, AlarmRuleConfiguration.class));
        alarmRule.setDescription(description);
        if (externalId != null) {
            alarmRule.setExternalId(new AlarmRuleId(externalId));
        }

        return alarmRule;
    }
}
