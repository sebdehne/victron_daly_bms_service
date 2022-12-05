package com.dehnes.daly_bms_service.victron

import com.dehnes.daly_bms_service.utils.PersistenceService
import com.fasterxml.jackson.databind.ObjectMapper
import com.hivemq.client.internal.mqtt.datatypes.MqttTopicImpl
import com.hivemq.client.internal.mqtt.datatypes.MqttUserPropertiesImpl
import com.hivemq.client.internal.mqtt.message.publish.MqttWillPublish
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit

class VictronMqttClient(
    private val persistenceService: PersistenceService,
    private val objectMapper: ObjectMapper,
) {

    private val logger = KotlinLogging.logger { }

    val asyncClient = MqttClient.builder()
        .identifier(UUID.randomUUID().toString())
        .serverAddress(InetSocketAddress(persistenceService["victron.mqtt_host", "localhost"]!!, 1883))
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

    fun send(topic: String) {
        sendAny(topic, null)
    }

    fun send(topic: String, value: Long) {
        sendAny(topic, mapOf("value" to value))
    }

    fun send(topic: String, json: Any) {
        sendAny(topic, json)
    }

    fun send(type: TopicType, path: String, value: Double) {
        sendAny(topic(type, path), mapOf("value" to value))
    }
    fun send(type: TopicType, path: String, value: Long) {
        sendAny(topic(type, path), mapOf("value" to value))
    }

    private fun getPortalId() =
        persistenceService["victron.portalId"] ?: error("victron.portalId not configured")

    fun writeEnabled() = persistenceService["victron.writeEnabled", "false"]!! == "true"

    fun topic(type: TopicType, path: String) = when (type) {
        TopicType.notify -> "N"
        TopicType.read -> "R"
        TopicType.write -> "W"
    } + "/${getPortalId()}$path"

    private fun sendAny(topic: String, value: Any?) {
        if (topic.startsWith("W/") && !writeEnabled()) {
            logger.warn { "Could not publish to Victron - write disabled" }
            return
        }

        val msg = value?.let { objectMapper.writeValueAsBytes(it) }
        asyncClient.publish(
            MqttWillPublish(
                MqttTopicImpl.of(topic),
                msg?.let { ByteBuffer.wrap(it) },
                MqttQos.AT_LEAST_ONCE,
                false,
                1000,
                null,
                null,
                null,
                null,
                MqttUserPropertiesImpl.NO_USER_PROPERTIES,
                0
            )
        ).get()
    }

}

// https://github.com/victronenergy/venus-html5-app/blob/master/TOPICS.md
enum class TopicType {
    notify,
    read,
    write
}
