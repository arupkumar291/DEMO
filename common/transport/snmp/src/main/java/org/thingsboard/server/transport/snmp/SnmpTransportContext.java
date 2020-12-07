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
package org.thingsboard.server.transport.snmp;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.PDU;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.device.profile.SnmpDeviceProfileKvMapping;
import org.thingsboard.server.common.data.device.profile.SnmpDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.transport.TransportContext;
import org.thingsboard.server.transport.snmp.session.DeviceSessionCtx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service("SnmpTransportContext")
@ConditionalOnExpression("'${service.type:null}'=='tb-transport' || ('${service.type:null}'=='monolith' && '${transport.api_enabled:true}'=='true' && '${transport.snmp.enabled}'=='true')")
@Slf4j
public class SnmpTransportContext extends TransportContext {
    @Getter
    private final Map<DeviceProfileId, SnmpDeviceProfileTransportConfiguration> deviceProfileTransportConfig = new ConcurrentHashMap<>();
    @Getter
    private final Map<DeviceProfileId, List<PDU>> pdusPerProfile = new ConcurrentHashMap<>();
    @Getter
    private final Map<DeviceId, DeviceSessionCtx> deviceSessions = new ConcurrentHashMap<>();

    public Optional<SnmpDeviceProfileKvMapping> findAttributesMapping(DeviceProfileId deviceProfileId, OID responseOid) {
        if (deviceProfileTransportConfig.containsKey(deviceProfileId)) {
            return findMapping(responseOid, deviceProfileTransportConfig.get(deviceProfileId).getAttributes());
        }
        return Optional.empty();
    }

    public Optional<SnmpDeviceProfileKvMapping> findTelemetryMapping(DeviceProfileId deviceProfileId, OID responseOid) {
        if (deviceProfileTransportConfig.containsKey(deviceProfileId)) {
            return findMapping(responseOid, deviceProfileTransportConfig.get(deviceProfileId).getTelemetry());
        }
        return Optional.empty();
    }

    private Optional<SnmpDeviceProfileKvMapping> findMapping(OID responseOid, List<SnmpDeviceProfileKvMapping> mappings) {
        return mappings.stream()
                .filter(kvMapping -> new OID(kvMapping.getOid()).equals(responseOid))
                //TODO: OID shouldn't be duplicated in the config, add verification
                .findFirst();
    }

    public void initPdusPerProfile() {
        deviceProfileTransportConfig.forEach((id, config) -> pdusPerProfile.put(id, getPduList(config)));
    }

    private List<PDU> getPduList(SnmpDeviceProfileTransportConfiguration deviceProfileConfig) {
        Map<String, List<VariableBinding>> varBindingPerMethod = new HashMap<>();

        Consumer<SnmpDeviceProfileKvMapping> varBindingPerMethodConsumer = mapping -> varBindingPerMethod
                .computeIfAbsent(mapping.getMethod(), v -> new ArrayList<>())
                .add(new VariableBinding(new OID(mapping.getOid())));

        deviceProfileConfig.getKvMappings().forEach(varBindingPerMethodConsumer);

        return varBindingPerMethod.keySet().stream()
                .map(method -> {
                    PDU request = new PDU();
                    request.setType(getSnmpMethod(method));
                    request.addAll(varBindingPerMethod.get(method));
                    return request;
                })
                .collect(Collectors.toList());
    }

    private int getSnmpMethod(String configMethod) {
        switch (configMethod) {
            case "get":
                return PDU.GET;
            default:
                return -1;
        }
    }
}
