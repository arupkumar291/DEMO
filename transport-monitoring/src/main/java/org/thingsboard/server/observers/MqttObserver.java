package org.thingsboard.server.observers;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.server.utils.AccessTokenHttpProvider;
import org.thingsboard.server.transport.TransportType;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        value="mqtt.enabled",
        havingValue = "true")
@Slf4j
public class MqttObserver extends AbstractTransportObserver {

    private static final String DEVICE_TELEMETRY_TOPIC = "v1/devices/me/telemetry";

    @Value("${mqtt.monitoring_rate}")
    private int monitoringRate;

    @Value("${mqtt.host}")
    private String mqttUrl;

    @Value("${mqtt.test_device.access_token}")
    private String testDeviceAccessToken;

    @Value("${mqtt.qos}")
    private int qos;

    @Value("${mqtt.timeout}")
    private long timeout;

    @Value("${mqtt.test_device.id}")
    private UUID testDeviceUuid;

    private MqttAsyncClient mqttAsyncClient;
    
    public MqttObserver(AccessTokenHttpProvider tokenHttpProvider) {
        super(tokenHttpProvider);
    }

    @Override
    protected void publishMsg(String payload) throws Exception {
        if (mqttAsyncClient == null || !mqttAsyncClient.isConnected()) {
            mqttAsyncClient = getMqttAsyncClient();
        }
        if (mqttAsyncClient.isConnected()) {
            MqttMessage message = new MqttMessage();
            message.setPayload(payload.getBytes());
            message.setQos(qos);
            mqttAsyncClient.publish(DEVICE_TELEMETRY_TOPIC, message);
        }
    }

    @Override
    public UUID getTestDeviceUuid() {
        return testDeviceUuid;
    }

    @Override
    public int getMonitoringRate() {
        return monitoringRate;
    }

    @Override
    public TransportType getTransportType() {
        return TransportType.MQTT;
    }

    private MqttAsyncClient getMqttAsyncClient() throws MqttException {
        String clientId = MqttAsyncClient.generateClientId();
        MqttAsyncClient client = new MqttAsyncClient(mqttUrl, clientId, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(testDeviceAccessToken);
        client.connect(options).waitForCompletion(timeout);
        return client;
    }
}
