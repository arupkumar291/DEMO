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
package org.thingsboard.server.dao.device.provision;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

@Data
public class ProvisionProfileCredentials implements Serializable {

    private static final long serialVersionUID = 4737290293406694616L;

    private String provisionProfileKey;
    private String provisionProfileSecret;

    @JsonCreator
    public ProvisionProfileCredentials(@JsonProperty("provisionProfileKey") String provisionProfileKey, @JsonProperty("provisionProfileSecret") String provisionProfileSecret) {
        this.provisionProfileKey = provisionProfileKey;
        this.provisionProfileSecret = provisionProfileSecret;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProvisionProfileCredentials that = (ProvisionProfileCredentials) o;
        return provisionProfileKey.equals(that.provisionProfileKey) &&
                provisionProfileSecret.equals(that.provisionProfileSecret);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provisionProfileKey, provisionProfileSecret);
    }
}
