package com.dehnes.daly_bms_service.victron

import com.dehnes.daly_bms_service.utils.PersistenceService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.hivemq.client.internal.mqtt.datatypes.MqttTopicFilterImpl
import com.hivemq.client.internal.mqtt.datatypes.MqttTopicImpl
import com.hivemq.client.internal.mqtt.datatypes.MqttUserPropertiesImpl
import com.hivemq.client.internal.mqtt.message.publish.MqttWillPublish
import com.hivemq.client.internal.mqtt.message.subscribe.MqttSubscribe
import com.hivemq.client.internal.mqtt.message.subscribe.MqttSubscription
import com.hivemq.client.internal.util.collections.ImmutableList
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5RetainHandling
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

interface VictronMqttClientListener {
    fun writeSoc(bmsId: String, soc: Int)
}

class VictronMqttClient(
    private val persistenceService: PersistenceService,
    private val objectMapper: ObjectMapper,
    private val executorService: ExecutorService
) {

    companion object {
        val logger = KotlinLogging.logger { }
    }

    val listeners = ConcurrentHashMap<String, VictronMqttClientListener>()

    val asyncClient = MqttClient.builder()
        .identifier(UUID.randomUUID().toString())
        .serverAddress(InetSocketAddress(persistenceService["victron.mqtt_host", "localhost"]!!, 1883))
        .useMqttVersion5()
        .automaticReconnect()
        .initialDelay(1, TimeUnit.SECONDS)
        .maxDelay(5, TimeUnit.SECONDS)
        .applyAutomaticReconnect()
        .addConnectedListener { resubscribe() }
        .buildAsync()

    fun reconnect() {
        asyncClient.disconnect()
        asyncClient.connect().get(20, TimeUnit.SECONDS)
    }

    private fun resubscribe() {
        asyncClient.subscribe(
            MqttSubscribe(
                ImmutableList.of(
                    MqttSubscription(
                        MqttTopicFilterImpl.of("W/daly_bms_service/soc/#"),
                        MqttQos.AT_MOST_ONCE,
                        false,
                        Mqtt5RetainHandling.SEND,
                        true
                    )
                ),
                MqttUserPropertiesImpl.NO_USER_PROPERTIES
            ), this::onMqttMessageErrorCatching
        )
    }

    private fun onMqttMessageErrorCatching(msg: Mqtt5Publish) {
        executorService.submit {
            try {
                onMqttMessage(msg)
            } catch (t: Throwable) {
                logger.error(t) { "" }
            }
        }
    }

    private fun onMqttMessage(msg: Mqtt5Publish) {
        val body = msg.payload.orElse(null)?.let {
            Charsets.UTF_8.decode(it)
        }?.toString() ?: "{}"
        val jsonRaw = objectMapper.readValue<Map<String, Any>>(body)

        val bmsId = msg.topic.toString().replace("W/daly_bms_service/soc/", "")
        val socValue = intValue(jsonRaw["value"])

        listeners.values.forEach { l -> l.writeSoc(bmsId, socValue) }
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

fun intValue(any: Any?) = when (any) {
    null -> 0
    is Int -> any
    is Long -> any.toInt()
    is Double -> any.toInt()
    else -> {
        VictronMqttClient.logger.error { "intValue - Unsupported type $any" }
        0
    }
}