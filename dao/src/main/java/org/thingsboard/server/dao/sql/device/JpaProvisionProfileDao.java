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
package org.thingsboard.server.dao.sql.device;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.device.ProvisionProfileDao;
import org.thingsboard.server.dao.device.provision.ProvisionProfile;
import org.thingsboard.server.dao.model.sql.ProvisionProfileEntity;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

@Component
@SqlDao
public class JpaProvisionProfileDao extends JpaAbstractDao<ProvisionProfileEntity, ProvisionProfile> implements ProvisionProfileDao {

    @Autowired
    private ProvisionProfileRepository provisionProfileRepository;

    @Override
    public ProvisionProfile findByKey(TenantId tenantId, String key) {
        return DaoUtil.getData(provisionProfileRepository.findByKey(key));
    }

    @Override
    protected Class<ProvisionProfileEntity> getEntityClass() {
        return ProvisionProfileEntity.class;
    }

    @Override
    protected CrudRepository<ProvisionProfileEntity, String> getCrudRepository() {
        return provisionProfileRepository;
    }
}
