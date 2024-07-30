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
package org.thingsboard.server.common.data.alarm.rule;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.ExportableEntity;
import org.thingsboard.server.common.data.alarm.rule.condition.AlarmRuleConfiguration;
import org.thingsboard.server.common.data.id.AlarmRuleId;

import java.io.Serial;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AlarmRule extends AlarmRuleInfo implements ExportableEntity<AlarmRuleId> {

    @Serial
    private static final long serialVersionUID = -6283966069145860173L;

    @Valid
    @Schema(description = "JSON object with Alarm Rule Configuration")
    private AlarmRuleConfiguration configuration;

    private AlarmRuleId externalId;

    public AlarmRule() {
        super();
    }

    public AlarmRule(AlarmRuleId id) {
        super(id);
    }

    public AlarmRule(AlarmRule alarmRule) {
        super(alarmRule);
        this.configuration = alarmRule.getConfiguration();
        this.externalId = alarmRule.getExternalId();
    }
}
