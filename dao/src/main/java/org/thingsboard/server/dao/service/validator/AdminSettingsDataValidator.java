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
package org.thingsboard.server.dao.service.validator;

import lombok.AllArgsConstructor;
import org.thingsboard.server.common.data.StringUtils;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.settings.AdminSettingsService;

@Component
@AllArgsConstructor
public class AdminSettingsDataValidator extends DataValidator<AdminSettings> {

    private final AdminSettingsService adminSettingsService;

    @Override
    protected void validateCreate(TenantId tenantId, AdminSettings adminSettings) {
        AdminSettings existentAdminSettingsWithKey = adminSettingsService.findAdminSettingsByKey(tenantId, adminSettings.getKey());
        if (existentAdminSettingsWithKey != null) {
            throw new DataValidationException("Admin settings with such name already exists!");
        }
    }

    @Override
    protected void validateUpdate(TenantId tenantId, AdminSettings adminSettings) {
        AdminSettings existentAdminSettings = adminSettingsService.findAdminSettingsById(tenantId, adminSettings.getId());
        if (existentAdminSettings != null) {
            if (!existentAdminSettings.getKey().equals(adminSettings.getKey())) {
                throw new DataValidationException("Changing key of admin settings entry is prohibited!");
            }
        }
    }


    @Override
    protected void validateDataImpl(TenantId tenantId, AdminSettings adminSettings) {
        if (StringUtils.isEmpty(adminSettings.getKey())) {
            throw new DataValidationException("Key should be specified!");
        }
        if (adminSettings.getJsonValue() == null) {
            throw new DataValidationException("Json value should be specified!");
        }
    }
}
