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
package org.thingsboard.server.service.sync.ie.importing.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.EntityView;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityViewId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.sync.ie.EntityExportData;
import org.thingsboard.server.dao.entityview.EntityViewService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.entityView.TbEntityViewService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.sync.vc.data.EntitiesImportCtx;

@Service
@TbCoreComponent
@RequiredArgsConstructor
public class EntityViewImportService extends BaseEntityImportService<EntityViewId, EntityView, EntityExportData<EntityView>> {

    private final EntityViewService entityViewService;

    @Lazy
    @Autowired
    private TbEntityViewService tbEntityViewService;

    @Override
    protected void setOwner(TenantId tenantId, EntityView entityView, IdProvider idProvider) {
        entityView.setTenantId(tenantId);
        entityView.setCustomerId(idProvider.getInternalId(entityView.getCustomerId()));
    }

    @Override
    protected EntityView prepare(EntitiesImportCtx ctx, EntityView entityView, EntityView old, EntityExportData<EntityView> exportData, IdProvider idProvider) {
        entityView.setEntityId(idProvider.getInternalId(entityView.getEntityId()));
        return entityView;
    }

    @Override
    protected EntityView saveOrUpdate(EntitiesImportCtx ctx, EntityView entityView, EntityExportData<EntityView> exportData, IdProvider idProvider) {
        return entityViewService.saveEntityView(entityView);
    }

    @Override
    protected void onEntitySaved(SecurityUser user, EntityView savedEntityView, EntityView oldEntityView) throws ThingsboardException {
        tbEntityViewService.updateEntityViewAttributes(user, savedEntityView, oldEntityView);
        super.onEntitySaved(user, savedEntityView, oldEntityView);
        if (oldEntityView != null) {
            entityActionService.sendEntityNotificationMsgToEdgeService(user.getTenantId(), savedEntityView.getId(), EdgeEventActionType.UPDATED);
        }
    }

    @Override
    protected EntityView deepCopy(EntityView entityView) {
        return new EntityView(entityView);
    }

    @Override
    protected void cleanupForComparison(EntityView e) {
        super.cleanupForComparison(e);
        if (e.getCustomerId() != null && e.getCustomerId().isNullUid()) {
            e.setCustomerId(null);
        }
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.ENTITY_VIEW;
    }

}
