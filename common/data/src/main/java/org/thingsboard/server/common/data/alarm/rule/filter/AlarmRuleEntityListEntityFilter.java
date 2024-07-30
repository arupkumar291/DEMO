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
package org.thingsboard.server.common.data.alarm.rule.filter;

import lombok.Data;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;

import java.util.List;

@Data
public class AlarmRuleEntityListEntityFilter implements AlarmRuleEntityFilter {

    private final EntityType entityType;

    private final List<EntityId> entityIds;

    @Override
    public AlarmRuleEntityFilterType getType() {
        return AlarmRuleEntityFilterType.ENTITY_LIST;
    }

    @Override
    public boolean isEntityMatches(EntityId entityId) {
        return entityType.equals(entityId.getEntityType()) && entityIds.contains(entityId);
    }
}
