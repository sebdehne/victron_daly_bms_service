package com.dehnes.daly_bms_service.bms

import com.dehnes.daly_bms_service.utils.AbstractProcess
import com.dehnes.daly_bms_service.utils.PersistenceService
import com.dehnes.daly_bms_service.utils.SerialPortFinder
import mu.KotlinLogging
import java.util.concurrent.ExecutorService

class BmsService(
    executorService: ExecutorService,
    private val persistenceService: PersistenceService,
) : AbstractProcess(
    executorService,
    persistenceService["interval_in_seconds", "10"]!!.toLong(),
) {
    private val logger = KotlinLogging.logger { }
    override fun logger() = logger

    private val connected = mutableMapOf<BmsId, BmsConnection>()

    override fun tickLocked(): Boolean {
        logger.info { "Running" }

        val toBeDisconnected = mutableListOf<BmsId>()
        val closeToBeDisconnected = {
            toBeDisconnected.forEach { d ->
                logger.info { "Closing $d" }
                connected[d]?.close()
                connected.remove(d)
            }
        }

        // 1) find out which devices to connect to
        val targetDevices = persistenceService.getAllFor("daly_bms.devices").toMap().mapKeys { key ->
            key.key.replace("daly_bms.devices.", "")
        }.map {
            BmsId(
                usbId = it.key,
                bmsId = it.value,
                displayName = persistenceService["daly_bms.deviceSettings.${it.value}.displayName", ""]!!
            )
        }

        // 2) disconnect if not wanted anymore
        toBeDisconnected.addAll(
            connected.keys.filterNot { it in targetDevices }
        )
        closeToBeDisconnected()

        val toBeConnected = targetDevices.filterNot { it in connected }
        toBeConnected.forEach { d ->
            try {
                val serialFile =
                    SerialPortFinder.findSerialPortFor(d.usbId) ?: error("Could not lookup serial file for $d")
                connected[d] = BmsConnection(serialFile, d)
            } catch (e: Exception) {
                logger.error(e) { "Could not connect to $d" }
            }
        }

        // 3 - query all data
        val collectedData = connected.mapNotNull { (device, connection) ->
            val data = connection.readData() ?: run {
                toBeDisconnected.add(device)
                return@mapNotNull null
            }

            device to data
        }

        // 4 - close failed connections
        closeToBeDisconnected()

        // 4 - publish data
        logger.info { "collectedData=$collectedData" }

        return true
    }

}

data class BmsId(
    val usbId: String,
    val bmsId: String,
    val displayName: String,
)
