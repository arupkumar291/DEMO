/**
 * Copyright © 2016-2021 The Thingsboard Authors
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
package org.thingsboard.server.common.data.device.profile;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.device.data.lwm2m.BootstrapConfiguration;
import org.thingsboard.server.common.data.device.data.lwm2m.OtherConfiguration;
import org.thingsboard.server.common.data.device.data.lwm2m.TelemetryMappingConfiguration;

@EqualsAndHashCode(callSuper = true)
@Data
public class Lwm2mDeviceProfileTransportConfiguration extends AbstractDeviceProfileTransportConfiguration {

    private static final long serialVersionUID = 6257277825459600068L;

    private TelemetryMappingConfiguration observeAttr;
    private BootstrapConfiguration bootstrap;
    private OtherConfiguration clientLwM2mSettings;

    @Override
    public DeviceTransportType getType() {
        return DeviceTransportType.LWM2M;
    }

}
