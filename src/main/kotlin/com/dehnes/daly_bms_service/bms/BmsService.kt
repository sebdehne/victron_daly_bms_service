package com.dehnes.daly_bms_service.bms

import com.dehnes.daly_bms_service.utils.AbstractProcess
import com.dehnes.daly_bms_service.utils.PersistenceService
import com.dehnes.daly_bms_service.utils.SerialPortFinder
import com.dehnes.daly_bms_service.utils.runInParallel
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService


fun PersistenceService.numberOfCells() = this["daly_bms.numberOfCells", "16"]!!.toInt()
fun PersistenceService.numberOfPacks() =
    this.getAllFor("daly_bms.deviceSettings").filter { it.first.contains("displayName") }.count()

class BmsService(
    private val executorService: ExecutorService,
    private val persistenceService: PersistenceService,
) : AbstractProcess(
    executorService,
    persistenceService["interval_in_seconds", "10"]!!.toLong(),
) {
    private val logger = KotlinLogging.logger { }
    override fun logger() = logger

    private val connected = mutableMapOf<BmsId, BmsConnection>()

    val listeners = ConcurrentHashMap<String, (data: List<BmsData>) -> Unit>()

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
                displayName = persistenceService["daly_bms.deviceSettings.${it.value}.displayName", ""]!!,
                capacity = persistenceService["daly_bms.deviceSettings.${it.value}.capacity", "280"]!!.toInt(),
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
                connected[d] = BmsConnection(serialFile, d, persistenceService.numberOfCells())
            } catch (e: Exception) {
                logger.error(e) { "Could not connect to $d" }
            }
        }

        // 3 - query all data
        val collectedData = runInParallel(
            false,
            executorService,
            connected.toList(),
        ) { (device, connection) ->
            connection.readData() ?: run {
                synchronized(toBeDisconnected) {
                    toBeDisconnected.add(device)
                }
                null
            }
        }

        // 4 - close failed connections
        closeToBeDisconnected()

        // 4 - publish data
        collectedData.forEach { logger.info { "$it" } }
        listeners.forEach {
            executorService.submit {
                try {
                    it.value(collectedData)
                } catch (e: Exception) {
                    logger.error(e) { "Listener failed to prosess data" }
                }
            }
        }

        return true
    }

    fun writeSoc(bmsId: String, soc: Int): Boolean {
        var result = false
        asLocked {
            connected.toList().filter { it.first.bmsId == bmsId }.forEach { conn ->
                result = conn.second.writeSoc(soc)
            }
        }
        return result
    }

}

data class BmsId(
    val usbId: String,
    val bmsId: String,
    val displayName: String,
    val capacity: Int,
)

data class BmsData(
    val bmsId: BmsId,
    val timestamp: Instant,
    val voltage: Double,
    val current: Double,
    val soc: Double,
    val avgEstimatedSoc: Double,

    val maxCellVoltage: Double,
    val maxCellNumber: Int,
    val minCellVoltage: Double,
    val minCellNumber: Int,

    val maxTemp: Int,
    val maxTempCellNumber: Int,
    val minTemp: Int,
    val minTempCellNumber: Int,

    val status: BmStatus,
    val mosfetCharging: Boolean,
    val mosfetDischarging: Boolean,

    val lifeCycles: Int,
    val remainingCapacity: Double, // in Ah

    val chargerStatus: Boolean,
    val loadStatus: Boolean,

    val cycles: Int,

    val cellVoltages: List<Double>,
    val socEstimates: List<Double>,
    val errors: List<String>,
)
