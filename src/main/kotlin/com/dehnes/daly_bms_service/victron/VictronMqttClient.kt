package com.dehnes.daly_bms_service.victron

import com.hivemq.client.mqtt.MqttClient
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.TimeUnit

class VictronMqttClient(
    private val victronHost: String
) {

    val asyncClient = MqttClient.builder()
        .identifier(UUID.randomUUID().toString())
        .serverAddress(InetSocketAddress(victronHost, 1883))
        .useMqttVersion5()
        .automaticReconnect()
        .initialDelay(1, TimeUnit.SECONDS)
        .maxDelay(5, TimeUnit.SECONDS)
        .applyAutomaticReconnect()
        .buildAsync()

    fun reconnect() {
        asyncClient.disconnect()
        asyncClient.connect().get(20, TimeUnit.SECONDS)
    }


}