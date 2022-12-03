package com.dehnes.daly_bms_service

import com.dehnes.daly_bms_service.bms.BmsService
import com.dehnes.daly_bms_service.utils.PersistenceService
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.Executors

fun objectMapper() = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)


fun main() {

    val executorService = Executors.newCachedThreadPool()
    val objectMapper = objectMapper()
    val persistenceService = PersistenceService(objectMapper)

    BmsService(executorService, persistenceService).start()

    while (true) {
        Thread.sleep(10000)
    }

}