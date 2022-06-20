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
package org.thingsboard.server.service.entitiy.ota;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.OtaPackage;
import org.thingsboard.server.common.data.OtaPackageInfo;
import org.thingsboard.server.common.data.SaveOtaPackageInfoRequest;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.OtaPackageId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.ota.ChecksumAlgorithm;
import org.thingsboard.server.dao.ota.OtaPackageService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.AbstractTbEntityService;

import java.nio.ByteBuffer;

@Service
@TbCoreComponent
@AllArgsConstructor
@Slf4j
public class DefaultTbOtaPackageService extends AbstractTbEntityService implements TbOtaPackageService {

    private final OtaPackageService otaPackageService;

    @Override
    public OtaPackageInfo save(SaveOtaPackageInfoRequest saveOtaPackageInfoRequest, User user) throws ThingsboardException {
        TenantId tenantId = saveOtaPackageInfoRequest.getTenantId();
        ActionType actionType = saveOtaPackageInfoRequest.getId() == null ? ActionType.ADDED : ActionType.UPDATED;
        OtaPackageInfo savedOtaPackageInfo = checkNotNull(otaPackageService.saveOtaPackageInfo(
                new OtaPackageInfo(saveOtaPackageInfoRequest), saveOtaPackageInfoRequest.isUsesUrl()));
        notificationEntityService.logEntityAction(tenantId, savedOtaPackageInfo.getId(), savedOtaPackageInfo, actionType, user);
        return savedOtaPackageInfo;
    }

    @Override
    public OtaPackageInfo saveOtaPackageData(OtaPackageInfo otaPackageInfo, String checksum, ChecksumAlgorithm checksumAlgorithm,
                                             byte[] data, String filename, String contentType, User user) throws ThingsboardException {
        TenantId tenantId = otaPackageInfo.getTenantId();
        OtaPackageId otaPackageId = otaPackageInfo.getId();
        if (StringUtils.isEmpty(checksum)) {
            checksum = otaPackageService.generateChecksum(checksumAlgorithm, ByteBuffer.wrap(data));
        }
        OtaPackage otaPackage = new OtaPackage(otaPackageId);
        otaPackage.setCreatedTime(otaPackageInfo.getCreatedTime());
        otaPackage.setTenantId(tenantId);
        otaPackage.setDeviceProfileId(otaPackageInfo.getDeviceProfileId());
        otaPackage.setType(otaPackageInfo.getType());
        otaPackage.setTitle(otaPackageInfo.getTitle());
        otaPackage.setVersion(otaPackageInfo.getVersion());
        otaPackage.setTag(otaPackageInfo.getTag());
        otaPackage.setAdditionalInfo(otaPackageInfo.getAdditionalInfo());
        otaPackage.setChecksumAlgorithm(checksumAlgorithm);
        otaPackage.setChecksum(checksum);
        otaPackage.setFileName(filename);
        otaPackage.setContentType(contentType);
        otaPackage.setData(ByteBuffer.wrap(data));
        otaPackage.setDataSize((long) data.length);
        OtaPackageInfo savedOtaPackage = otaPackageService.saveOtaPackage(otaPackage);
        notificationEntityService.logEntityAction(tenantId, savedOtaPackage.getId(), savedOtaPackage, ActionType.UPDATED, user);
        return savedOtaPackage;
    }

    @Override
    public void delete(OtaPackageInfo otaPackageInfo, User user) throws ThingsboardException {
        TenantId tenantId = otaPackageInfo.getTenantId();
        OtaPackageId otaPackageId = otaPackageInfo.getId();
        otaPackageService.deleteOtaPackage(tenantId, otaPackageId);
        notificationEntityService.logEntityAction(tenantId, otaPackageId, otaPackageInfo, ActionType.DELETED, user,
                otaPackageInfo.getId().toString());
    }
}
