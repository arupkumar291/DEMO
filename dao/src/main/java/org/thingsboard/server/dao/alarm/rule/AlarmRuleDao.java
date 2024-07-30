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
package org.thingsboard.server.dao.alarm.rule;

import com.fasterxml.jackson.databind.JsonNode;
import org.thingsboard.server.common.data.alarm.rule.AlarmRule;
import org.thingsboard.server.common.data.alarm.rule.AlarmRuleInfo;
import org.thingsboard.server.common.data.id.AlarmRuleId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.dao.Dao;
import org.thingsboard.server.dao.ExportableEntityDao;

import java.util.List;
import java.util.UUID;

public interface AlarmRuleDao extends Dao<AlarmRule>, ExportableEntityDao<AlarmRuleId, AlarmRule> {

    PageData<AlarmRuleInfo> findAlarmRuleInfosByTenantId(UUID tenantId, PageLink pageLink);

    PageData<AlarmRule> findAlarmRulesByTenantId(UUID tenantId, PageLink pageLink);

    PageData<AlarmRule> findEnabledAlarmRulesByTenantId(UUID tenantId, PageLink pageLink);

    List<JsonNode> findRuleNodeStatesByRuleChainIdAndType(DeviceProfileId deviceProfileId, RuleChainId ruleChainId, String type);

}
