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
package org.thingsboard.server.service.state;

import org.springframework.context.ApplicationListener;
import org.thingsboard.rule.engine.api.RuleEngineDeviceStateService;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.queue.discovery.event.PartitionChangeEvent;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.common.msg.queue.TbCallback;

/**
 * Created by ashvayka on 01.05.18.
 */
public interface DeviceStateService extends RuleEngineDeviceStateService, ApplicationListener<PartitionChangeEvent> {

    void onDeviceConnect(TenantId tenantId, DeviceId deviceId);

    void onDeviceActivity(TenantId tenantId, DeviceId deviceId, long lastReportedActivityTime);

    void onDeviceDisconnect(TenantId tenantId, DeviceId deviceId);

    void onDeviceInactivityTimeoutUpdate(TenantId tenantId, DeviceId deviceId, long inactivityTimeout);

    void onDeviceInactivity(TenantId tenantId, DeviceId deviceId);

    void onQueueMsg(TransportProtos.DeviceStateServiceMsgProto proto, TbCallback bytes);

}
