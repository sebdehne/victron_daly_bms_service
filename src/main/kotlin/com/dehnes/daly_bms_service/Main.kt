package com.dehnes.daly_bms_service

import com.dehnes.daly_bms_service.bms.BmsService
import com.dehnes.daly_bms_service.utils.PersistenceService
import com.dehnes.daly_bms_service.victron.VictronMqttClient
import com.dehnes.daly_bms_service.victron.VirtualBatteryService
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.ZoneId
import java.util.concurrent.Executors

fun objectMapper() = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

fun main(vararg args: String) {

    check(args.isNotEmpty()) { "Invalid argument. Usage: <properties.json>" }

    val executorService = Executors.newCachedThreadPool()
    val objectMapper = objectMapper()
    val persistenceService = PersistenceService(objectMapper, args[0])

    val bmsService = BmsService(
        executorService,
        persistenceService,
    ).apply { this.start() }

    val victronMqttClient = VictronMqttClient(persistenceService, objectMapper, executorService)
    victronMqttClient.reconnect()
    val virtualBatteryService = VirtualBatteryService(victronMqttClient, persistenceService, bmsService)

    while (true) {
        Thread.sleep(10000)
    }

}


val zoneId = ZoneId.of("Europe/Oslo")