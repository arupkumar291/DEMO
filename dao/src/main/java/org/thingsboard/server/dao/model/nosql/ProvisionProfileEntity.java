/**
 * Copyright © 2016-2020 The Thingsboard Authors
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
package org.thingsboard.server.dao.model.nosql;

import com.datastax.driver.core.utils.UUIDs;
import com.datastax.driver.mapping.annotations.Column;
import com.datastax.driver.mapping.annotations.PartitionKey;
import com.datastax.driver.mapping.annotations.Table;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ProvisionProfileId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.device.provision.ProvisionProfile;
import org.thingsboard.server.dao.device.provision.ProvisionProfileCredentials;
import org.thingsboard.server.dao.model.BaseEntity;

import java.util.UUID;

import static org.thingsboard.server.dao.model.ModelConstants.ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.PROVISION_PROFILE_COLUMN_FAMILY_NAME;
import static org.thingsboard.server.dao.model.ModelConstants.PROVISION_PROFILE_CUSTOMER_ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.PROVISION_PROFILE_KEY_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.PROVISION_PROFILE_SECRET_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.PROVISION_PROFILE_TENANT_ID_PROPERTY;

@Table(name = PROVISION_PROFILE_COLUMN_FAMILY_NAME)
@EqualsAndHashCode
@ToString
public final class ProvisionProfileEntity implements BaseEntity<ProvisionProfile> {

    @PartitionKey(value = 0)
    @Column(name = ID_PROPERTY)
    private UUID id;

    @PartitionKey(value = 1)
    @Column(name = PROVISION_PROFILE_TENANT_ID_PROPERTY)
    private UUID tenantId;

    @PartitionKey(value = 2)
    @Column(name = PROVISION_PROFILE_CUSTOMER_ID_PROPERTY)
    private UUID customerId;

    @PartitionKey(value = 3)
    @Column(name = PROVISION_PROFILE_KEY_PROPERTY)
    private String key;

    @Column(name = PROVISION_PROFILE_SECRET_PROPERTY)
    private String secret;

    public ProvisionProfileEntity() {
        super();
    }

    public ProvisionProfileEntity(ProvisionProfile profile) {
        if (profile.getId() != null) {
            this.id = profile.getId().getId();
        }
        if (profile.getTenantId() != null) {
            this.tenantId = profile.getTenantId().getId();
        }
        if (profile.getCustomerId() != null) {
            this.customerId = profile.getCustomerId().getId();
        }
        this.key = profile.getCredentials().getProvisionProfileKey();
        this.secret = profile.getCredentials().getProvisionProfileSecret();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public ProvisionProfile toData() {
        ProvisionProfile profile = new ProvisionProfile(new ProvisionProfileId(id));
        profile.setCreatedTime(UUIDs.unixTimestamp(id));
        if (tenantId != null) {
            profile.setTenantId(new TenantId(tenantId));
        }
        if (customerId != null) {
            profile.setCustomerId(new CustomerId(customerId));
        }
        profile.setCredentials(new ProvisionProfileCredentials(key, secret));
        return profile;
    }
}
