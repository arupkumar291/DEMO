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
package org.thingsboard.rule.engine.geo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.concurrent.TimeUnit;

/**
 * Created by ashvayka on 19.01.18.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TbGpsGeofencingActionNodeConfiguration extends TbGpsGeofencingFilterNodeConfiguration {

    private long minInsideDuration;
    private long minOutsideDuration;

    private String minInsideDurationTimeUnit;
    private String minOutsideDurationTimeUnit;

    private boolean reportPresenceStatusOnEachMessage;

    @Override
    public TbGpsGeofencingActionNodeConfiguration defaultConfiguration() {
        TbGpsGeofencingActionNodeConfiguration configuration = new TbGpsGeofencingActionNodeConfiguration();
        configuration.setLatitudeKeyName("latitude");
        configuration.setLongitudeKeyName("longitude");
        configuration.setPerimeterType(PerimeterType.POLYGON);
        configuration.setFetchPerimeterInfoFromMessageMetadata(true);
        configuration.setPerimeterKeyName("ss_perimeter");
        configuration.setMinInsideDurationTimeUnit(TimeUnit.MINUTES.name());
        configuration.setMinOutsideDurationTimeUnit(TimeUnit.MINUTES.name());
        configuration.setMinInsideDuration(1);
        configuration.setMinOutsideDuration(1);
        configuration.setReportPresenceStatusOnEachMessage(true);
        return configuration;
    }
}
