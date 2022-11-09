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
package org.thingsboard.server.transport.mqtt.mqttv5.client;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.transport.mqtt.MqttTestConfigProperties;

@DaoSqlTest
public class MqttV5ClientTest extends AbstractMqttV5ClientTest {

    @Before
    public void beforeTest() throws Exception {
        MqttTestConfigProperties configProperties = MqttTestConfigProperties.builder()
                .deviceName("Test MqttV5 client device")
                .build();
        processBeforeTest(configProperties);
    }

    @Test
    public void testClientWithCorrectAccessToken() throws Exception {
        processClientWithCorrectAccessTokenTest();
    }

    @Test
    public void testClientWithWrongAccessToken() throws Exception {
        processClientWithWrongAccessTokenTest();
    }

    @Test
    public void testClientWithWrongClientIdAndEmptyUsernamePassword() throws Exception {
        processClientWithWrongClientIdAndEmptyUsernamePasswordTest();
    }

    @Test
    public void testClientWithNoCredentialsTest() throws Exception {
        processClientWithNoCredentialsTest();
    }

    @Test
    @Ignore("Not implemented on the server.")
    public void testClientWithPacketSizeLimitation() throws Exception {
        processClientWithPacketSizeLimitationTest();
    }
}
