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
package org.thingsboard.server.transport.lwm2m.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.util.Base64;
import org.eclipse.leshan.core.ResponseCode;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.transport.lwm2m.utils.LwM2mValueConverterImpl;

import static org.eclipse.leshan.core.model.ResourceModel.Type.BOOLEAN;
import static org.eclipse.leshan.core.model.ResourceModel.Type.FLOAT;
import static org.eclipse.leshan.core.model.ResourceModel.Type.INTEGER;
import static org.eclipse.leshan.core.model.ResourceModel.Type.OBJLNK;
import static org.eclipse.leshan.core.model.ResourceModel.Type.OPAQUE;
import static org.eclipse.leshan.core.model.ResourceModel.Type.STRING;
import static org.eclipse.leshan.core.model.ResourceModel.Type.TIME;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.thingsboard.server.transport.lwm2m.client.model.LwM2MTestObjectModelWithResource.BOOLEAN_RESOURCE_ID;
import static org.thingsboard.server.transport.lwm2m.client.model.LwM2MTestObjectModelWithResource.OPAQUE_MULTI_INSTANCE_RESOURCE_ID;
import static org.thingsboard.server.transport.lwm2m.client.model.LwM2MTestObjectModelWithResource.STRING_RESOURCE_ID;
import static org.thingsboard.server.transport.lwm2m.client.model.LwM2MTestObjectModelWithResource.booleanNamePrefix;
import static org.thingsboard.server.transport.lwm2m.client.model.LwM2MTestObjectModelWithResource.opaqueMultiNamePrefix;
import static org.thingsboard.server.transport.lwm2m.client.model.LwM2MTestObjectModelWithResource.stringNamePrefix;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportUtil.equalsResourceTypeGetSimpleName;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportUtil.fromVersionedIdToObjectId;
import static org.thingsboard.server.transport.lwm2m.server.RpcModelsTestHelper.resourceNameSuffixRws;

public class RpcLwm2mIntegrationReadTest extends RpcAbstractLwM2MIntegrationTest {

    /**
     * Read {"id":"/2001"}
     *
     * @throws Exception
     */
    @Test
    public void testReadObject_Ok() throws Exception {
        String actualResult = sendRead("/2001_1.1");
        ObjectNode rpcActualResult = JacksonUtil.fromString(actualResult, ObjectNode.class);
        assertEquals(ResponseCode.CONTENT.getName(), rpcActualResult.get("result").asText());
        assertTrue(rpcActualResult.get("value").asText().contains("LwM2mObject"));
    }

    /**
     * Read {"id":"/2000/0/"}
     *
     * @throws Exception
     */
    @Test
    public void testReadObjectInstance_Ok() throws Exception {
        String actualResult = sendRead("/2000/0");
        ObjectNode rpcActualResult = JacksonUtil.fromString(actualResult, ObjectNode.class);
        assertEquals(ResponseCode.CONTENT.getName(), rpcActualResult.get("result").asText());
        assertTrue(rpcActualResult.get("value").asText().contains("LwM2mObjectInstance"));
    }

    /**
     * Read {"id":"/2003/1/12"}
     *
     * @throws Exception
     */
    @Test
    public void testReadOpaqueMultipleResource_Ok() throws Exception {
        String actualResult = sendRead("/2003_1.1/1/" + OPAQUE_MULTI_INSTANCE_RESOURCE_ID);
        ObjectNode rpcActualResult = JacksonUtil.fromString(actualResult, ObjectNode.class);
        assertEquals(ResponseCode.CONTENT.getName(), rpcActualResult.get("result").asText());
        assertTrue(rpcActualResult.get("value").asText().contains("LwM2mMultipleResource"));
    }

    /**
     *     Test by type value:
     *     public static final int STRING_RESOURCE_ID = 0;
     *     public static final int BOOLEAN_RESOURCE_ID = 1;
     *     public static final int INTEGER_RESOURCE_ID = 2;
     *     public static final int FLOAT_RESOURCE_ID = 3;
     *     public static final int TIME_RESOURCE_ID = 4;
     *     public static final int OBJLNK_RESOURCE_ID = 5;
     *     public static final int OPAQUE_RESOURCE_ID = 6;
     *     public static final int UNSIGNED_INTEGER_RESOURCE_ID = 7;
     *     public static final int INTEGER_MANDATORY_RESOURCE_ID = 8;
     *     public static final int STRING_MANDATORY_RESOURCE_ID = 9;
     *     public static final int STRING_MULTI_INSTANCE_RESOURCE_ID = 10;
     *     public static final int OBJLNK_MULTI_INSTANCE_RESOURCE_ID = 11;
     *     public static final int OPAQUE_MULTI_INSTANCE_RESOURCE_ID = 65010;
     */

    /**
     * Read {"id":"/2000/0/0"}
     *
     * @throws Exception
     */
    @Test
    public void testReadStringSingleResource_Ok() throws Exception {
        String expectedValue = stringNamePrefix + resourceNameSuffixRws;
        ResourceModel.Type expectedType = STRING;
        String resourcePath = "/2000/0/" + STRING_RESOURCE_ID;
        String actualResult = sendRead(resourcePath);
        ObjectNode rpcActualResult = JacksonUtil.fromString(actualResult, ObjectNode.class);
        assertEquals(ResponseCode.CONTENT.getName(), rpcActualResult.get("result").asText());
        assertTrue(rpcActualResult.get("value").asText().contains("LwM2mSingleResource"));
        isValueTypeResource_Ok(rpcActualResult.get("value").asText(), expectedValue, expectedType, resourcePath);
    }

    /**
     * Read {"id":"/2000/0/1"}
     *
     * @throws Exception
     */
    @Test
    public void testReadBooleanSingleResource_Ok() throws Exception {
        String expectedValue = booleanNamePrefix + resourceNameSuffixRws;
        ResourceModel.Type expectedType = BOOLEAN;
        String resourcePath = "/2000/0/" + BOOLEAN_RESOURCE_ID;
        String actualResult = sendRead(resourcePath);
        ObjectNode rpcActualResult = JacksonUtil.fromString(actualResult, ObjectNode.class);
        assertEquals(ResponseCode.CONTENT.getName(), rpcActualResult.get("result").asText());
        assertTrue(rpcActualResult.get("value").asText().contains("LwM2mSingleResource"));
        isValueTypeResource_Ok(rpcActualResult.get("value").asText(), expectedValue, expectedType, resourcePath);
    }

    /**
     * Read {"id":"/2003/1/12/0"}
     *
     * @throws Exception
     */
    @Test
    public void testReadOpaqueResourceInstance_Ok() throws Exception {
        String expectedValue = "Default " + opaqueMultiNamePrefix;
        String resourcePath = "/2003_1.1/1/" + OPAQUE_MULTI_INSTANCE_RESOURCE_ID + "/0";
        String actualResult = sendRead(resourcePath);
        ObjectNode rpcActualResult = JacksonUtil.fromString(actualResult, ObjectNode.class);
        assertEquals(ResponseCode.CONTENT.getName(), rpcActualResult.get("result").asText());
        assertTrue(rpcActualResult.get("value").asText().contains("LwM2mResourceInstance"));
        isValueTypeResource_Ok(rpcActualResult.get("value").asText(), expectedValue, OPAQUE, resourcePath);
    }

    private String sendRead(String path) throws Exception {
        String method = "Read";
        String setRpcRequest = "{\"method\": \"" + method + "\", \"params\": {\"id\": \"" + path + "\"}}";
        return doPostAsync("/api/plugins/rpc/twoway/" + deviceId, setRpcRequest, String.class, status().isOk());
    }

    private String readValue(String contentValueStr) {
        String searchTextValue = "value=";
        if (contentValueStr.contains(searchTextValue)) {
            int startPos = contentValueStr.indexOf(searchTextValue) + searchTextValue.length();
            int endPos = contentValueStr.indexOf(",", startPos);
            if (startPos >= 0 && endPos > 0) {
                return contentValueStr.substring(startPos, endPos);
            }
            return null;
        } else {
            return null;
        }
    }

    private String readType(String contentValueStr) {
        String searchTextType = "type=";
        if (contentValueStr.contains(searchTextType)) {
            int startPos = contentValueStr.indexOf(searchTextType) + searchTextType.length();
            int endPos = contentValueStr.indexOf("]", startPos);
            if (startPos >= 0 && endPos > 0) {
                return contentValueStr.substring(startPos, endPos);
            }
            return null;
        } else {
            return null;
        }
    }

    private void isValueTypeResource_Ok(String contentValueStr, String expectedValue, ResourceModel.Type expectedType, String pathIdVer) {
        String actualType = readType(contentValueStr);
        if (expectedType.equals(OPAQUE)) {
            assertEquals(STRING.name(), actualType);
        } else {
            assertEquals(expectedType.name(), actualType);
        }
        String actualValue = readValue(contentValueStr);

        Object value = LwM2mValueConverterImpl.getInstance().convertValue(actualValue, STRING, expectedType,
                new LwM2mPath(fromVersionedIdToObjectId(pathIdVer)));
        ResourceModel.Type valueType = equalsResourceTypeGetSimpleName(value);
        assertTrue(expectedType.equals(valueType));
        if (actualValue != null) {
            switch (expectedType) {
                case STRING:
                    assertEquals(expectedValue, actualValue);
                    break;
                case OPAQUE:
                    assertEquals(expectedValue, new String((byte[])value));
                    break;
//                case "OBJLNK":
//                    return OBJLNK;
//                case "UNSIGNED_INTEGER":
//                    return OBJLNK;

            }

        }
    }
}
