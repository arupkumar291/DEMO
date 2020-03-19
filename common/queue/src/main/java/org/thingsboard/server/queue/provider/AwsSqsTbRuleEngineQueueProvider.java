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
package org.thingsboard.server.queue.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.thingsboard.server.queue.*;

import org.thingsboard.server.gen.transport.TransportProtos.ToCoreMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToRuleEngineMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToTransportMsg;
import org.thingsboard.server.queue.TbQueueCoreSettings;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.sqs.TbAwsSqsAdmin;
import org.thingsboard.server.queue.sqs.TbAwsSqsConsumerTemplate;
import org.thingsboard.server.queue.sqs.TbAwsSqsProducerTemplate;
import org.thingsboard.server.queue.sqs.TbAwsSqsSettings;

@Component
@ConditionalOnExpression("'${queue.type:null}'=='aws-sqs' && '${service.type:null}'=='tb-rule-engine'")
public class AwsSqsTbRuleEngineQueueProvider implements TbRuleEngineQueueProvider {

    private final TbAwsSqsSettings sqsSettings;
    private final TbQueueCoreSettings coreSettings;
    private final TbQueueRuleEngineSettings ruleEngineSettings;
    private final TbQueueAdmin admin;

    public AwsSqsTbRuleEngineQueueProvider(TbAwsSqsSettings sqsSettings, TbQueueCoreSettings coreSettings, TbQueueRuleEngineSettings ruleEngineSettings) {
        this.sqsSettings = sqsSettings;
        this.coreSettings = coreSettings;
        this.ruleEngineSettings = ruleEngineSettings;
        this.admin = new TbAwsSqsAdmin(sqsSettings);
    }

    @Override
    public TbQueueProducer<TbProtoQueueMsg<ToTransportMsg>> getTransportNotificationsMsgProducer() {
        return new TbAwsSqsProducerTemplate<>(admin, sqsSettings, coreSettings.getTopic());
    }

    @Override
    public TbQueueProducer<TbProtoQueueMsg<ToRuleEngineMsg>> getRuleEngineMsgProducer() {
        return new TbAwsSqsProducerTemplate<>(admin, sqsSettings, coreSettings.getTopic());
    }

    @Override
    public TbQueueProducer<TbProtoQueueMsg<ToCoreMsg>> getTbCoreMsgProducer() {
        return new TbAwsSqsProducerTemplate<>(admin, sqsSettings, coreSettings.getTopic());
    }

    @Override
    public TbQueueConsumer<TbProtoQueueMsg<ToRuleEngineMsg>> getToRuleEngineMsgConsumer() {
        return new TbAwsSqsConsumerTemplate<>(admin, sqsSettings, ruleEngineSettings.getTopic(), msg -> new TbProtoQueueMsg<>(msg.getKey(), ToRuleEngineMsg.parseFrom(msg.getData()), msg.getHeaders()));
    }
}
