/**
 * Copyright © 2016-2023 The Thingsboard Authors
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
package org.thingsboard.server.common.data.rule;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.id.RuleChainId;

@Data
@EqualsAndHashCode(callSuper = true)
public class RuleChainInfo extends RuleChain {

    private static final long serialVersionUID = 4773536807963461973L;

    private transient boolean errorPresent;

    public RuleChainInfo() {
        super();
    }

    public RuleChainInfo(RuleChainId id) {
        super(id);
    }

    public RuleChainInfo(RuleChainId id, boolean errorPresent) {
        super(id);
        this.errorPresent = errorPresent;
    }

    public boolean isErrorPresent() {
        return errorPresent;
    }

    public void setErrorPresent(boolean errorPresent) {
        this.errorPresent = errorPresent;
    }

}
