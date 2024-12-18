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
package org.thingsboard.server.service.cf;

import lombok.Data;
import org.thingsboard.server.common.data.AttributeScope;

import java.util.Map;

@Data
public final class CalculatedFieldResult {

    private final String type;
    private final AttributeScope scope;
    private final Map<String, Object> resultMap;

    public CalculatedFieldResult(String type, AttributeScope scope, Map<String, Object> resultMap) {
        this.type = type;
        this.scope = scope;
        this.resultMap = resultMap == null ? Map.of() : Map.copyOf(resultMap);
    }
}
