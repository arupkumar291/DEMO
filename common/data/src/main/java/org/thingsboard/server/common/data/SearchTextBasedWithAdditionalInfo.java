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
package org.thingsboard.server.common.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.UUIDBased;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Created by ashvayka on 19.02.18.
 */
@Slf4j
public abstract class SearchTextBasedWithAdditionalInfo<I extends UUIDBased> extends SearchTextBased<I> implements HasAdditionalInfo {

    private transient JsonNode additionalInfo;
    @JsonIgnore
    private byte[] additionalInfoBytes;

    public SearchTextBasedWithAdditionalInfo() {
        super();
    }

    public SearchTextBasedWithAdditionalInfo(I id) {
        super(id);
    }

    public SearchTextBasedWithAdditionalInfo(SearchTextBasedWithAdditionalInfo<I> searchTextBased) {
        super(searchTextBased);
        setAdditionalInfo(searchTextBased.getAdditionalInfo());
    }

    @Override
    public JsonNode getAdditionalInfo() {
        return getJson(() -> additionalInfo, () -> additionalInfoBytes);
    }

    public void setAdditionalInfo(JsonNode addInfo) {
        setJson(addInfo, json -> this.additionalInfo = json, bytes -> this.additionalInfoBytes = bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        SearchTextBasedWithAdditionalInfo<?> that = (SearchTextBasedWithAdditionalInfo<?>) o;
        return Arrays.equals(additionalInfoBytes, that.additionalInfoBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), additionalInfoBytes);
    }

    public static JsonNode getJson(Supplier<JsonNode> jsonData, Supplier<byte[]> binaryData) {
        JsonNode json = jsonData.get();
        if (json != null) {
            return json;
        } else {
            byte[] data = binaryData.get();
            if (data != null) {
                try {
                    return JacksonUtil.fromBytes(data);
                } catch (IllegalArgumentException e) {
                    log.warn("Can't deserialize json data: ", e);
                    return null;
                }
            } else {
                return null;
            }
        }
    }

    public static void setJson(JsonNode json, Consumer<JsonNode> jsonConsumer, Consumer<byte[]> bytesConsumer) {
        jsonConsumer.accept(json);
        try {
            bytesConsumer.accept(JacksonUtil.writeValueAsBytes(json));
        } catch (IllegalArgumentException e) {
            log.warn("Can't serialize json data: ", e);
        }
    }
}
