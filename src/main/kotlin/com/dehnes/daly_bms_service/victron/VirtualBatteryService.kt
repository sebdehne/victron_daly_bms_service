package com.dehnes.daly_bms_service.victron

import com.dehnes.daly_bms_service.bms.BmsData
import com.dehnes.daly_bms_service.bms.BmsId
import com.dehnes.daly_bms_service.bms.BmsService
import com.dehnes.daly_bms_service.bms.numberOfCells
import com.dehnes.daly_bms_service.utils.PersistenceService
import com.dehnes.daly_bms_service.utils.round2d
import com.dehnes.daly_bms_service.victron.ValueTypes.*
import mu.KotlinLogging
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToLong

/**
 * Listens for data on all Daly BMses and aggregates the data only
 * one virtual battery
 *
 * It talks to XXX to inject the data into the dbus
 */
class VirtualBatteryService(
    private val victronMqttClient: VictronMqttClient,
    private val persistenceService: PersistenceService,
    bmsService: BmsService,
) {

    private val logger = KotlinLogging.logger { }
    private val topic = "W/dbus-mqtt-services"

    init {
        bmsService.listeners["VirtualBatteryService"] = this::onBmsData
    }

    private val lock = ReentrantLock()
    private val knownBmsData = mutableMapOf<BmsId, BmsData>()

    private fun onBmsData(bmsData: List<BmsData>) {
        if (lock.tryLock()) {
            try {
                onBmsDataLocked(bmsData)
            } finally {
                lock.unlock()
            }
        }
    }

    private fun onBmsDataLocked(bmsData: List<BmsData>) {
        bmsData.forEach { d ->
            knownBmsData[d.bmsId] = d
        }

        // re-publish total
        republishTotal()
    }

    private fun republishTotal() {

        val minBatteryVoltage = knownBmsData.minOf { it.value.minCellVoltage } * persistenceService.numberOfCells()
        val maxBatteryVoltage = knownBmsData.minOf { it.value.maxCellVoltage } * persistenceService.numberOfCells()

        val capacityRemainig = knownBmsData.map { it.value.remainingCapacity }.sum()
        val installedCapacity = knownBmsData.map { it.key.capacity }.sum()
        val soc = knownBmsData.map { it.value.soc }.average().round2d()
        val voltage = knownBmsData.map { it.value.voltage }.average().round2d()
        val current = knownBmsData.map { it.value.current }.sum()
        val power = voltage * current
        val maxTemperature = knownBmsData.map { it.value.maxTemp }.max()
        val minTemperature = knownBmsData.map { it.value.minTemp }.min()
        val maxCellVoltage = knownBmsData.maxBy { it.value.maxCellVoltage }.value
        val minCellVoltage = knownBmsData.minBy { it.value.minCellVoltage }.value
        val chargeCycles = knownBmsData.maxBy { it.value.cycles }.value.cycles

        val cellDelta = maxCellVoltage.maxCellVoltage - minCellVoltage.minCellVoltage

        val numberOfCells = persistenceService.numberOfCells()
        val toBeSendt = DbusService(
            "daly_bms_battery_1",
            "battery",
            0,
            listOf(
                DbusData("/Mgmt/ProcessName", "Daly Bms Bridge"),
                DbusData("/Mgmt/ProcessVersion", "1.0"),
                DbusData("/Mgmt/Connection", "Serial Uart Daly"),

                DbusData("/ProductId", "0", integer),
                DbusData("/ProductName", "Daly Bms service"),
                DbusData("/FirmwareVersion", "1.0"),
                DbusData("/HardwareVersion", "1.0"),
                DbusData("/Connected", "1", integer),
                DbusData("/CustomName", "Daly Bms service", writeable = true),


                DbusData("/Info/BatteryLowVoltage", minBatteryVoltage.toString(), float),
                DbusData("/Info/MaxChargeVoltage", maxBatteryVoltage.toString(), float),
                DbusData("/Info/MaxChargeCurrent", 210.0.toString(), float),
                DbusData("/Info/MaxDischargeCurrent", 350.0.toString(), float),
                DbusData("/System/NrOfCellsPerBattery", numberOfCells.toString(), integer),
                DbusData("/System/NrOfModulesOnline", "1", integer),
                DbusData("/System/NrOfModulesOffline", "1", integer),
                DbusData("/System/NrOfModulesBlockingCharge", "", none),
                DbusData("/System/NrOfModulesBlockingDischarge", "", none),

                DbusData("/Capacity", capacityRemainig.toString(), float),
                DbusData("/InstalledCapacity", installedCapacity.toString(), integer),
                DbusData("/ConsumedAmphours", (installedCapacity - capacityRemainig).toString(), float),

                DbusData("/Soc", soc.toString(), float),
                DbusData("/Dc/0/Voltage", voltage.toString(), float),
                DbusData("/Dc/0/Current", current.toString(), float),
                DbusData("/Dc/0/Power", power.toString(), float),
                DbusData("/Dc/0/Temperature", maxTemperature.toString(), float),

                DbusData("/System/MinCellTemperature", minTemperature.toString(), float),
                DbusData("/System/MaxCellTemperature", maxTemperature.toString(), float),
                DbusData("/System/MaxCellVoltage", maxCellVoltage.maxCellVoltage.toString(), float),
                DbusData("/System/MaxVoltageCellId", maxCellVoltage.maxCellNumber.toString(), integer),
                DbusData("/System/MinCellVoltage", minCellVoltage.minCellVoltage.toString(), float),
                DbusData("/System/MinVoltageCellId", minCellVoltage.minCellNumber.toString(), integer),

                DbusData("/History/ChargeCycles", chargeCycles.toString(), integer),
                DbusData("/History/TotalAhDrawn", "0", float), // ???
                DbusData("/Balancing", "0", integer),
                DbusData("/Io/AllowToCharge", "1", integer), // TODO
                DbusData("/Io/AllowToDischarge", "1", integer), // TODO


                DbusData("/Alarms/LowVoltage", (if (voltage < 44.8) 2 else 0).toString(), integer),
                DbusData("/Alarms/HighVoltage", (if (voltage > 56) 2 else 0).toString(), integer),
                DbusData(
                    "/Alarms/LowCellVoltage",
                    (if (minCellVoltage.minCellVoltage < 2.8) 2 else 0).toString(),
                    integer
                ),
                DbusData(
                    "/Alarms/HighCellVoltage",
                    (if (maxCellVoltage.maxCellVoltage > 3.4) 2 else 0).toString(),
                    integer
                ),
                DbusData("/Alarms/LowSoc", (if (soc < 10) 2 else 0).toString(), integer),
                DbusData("/Alarms/HighChargeCurrent", "", none),
                DbusData("/Alarms/HighDischargeCurrent", "", none),
                DbusData("/Alarms/CellImbalance", (if (cellDelta > 0.1) 2 else 0).toString(), integer),
                DbusData("/Alarms/InternalFailure", "", none),
                DbusData("/Alarms/HighChargeTemperature", (if (maxTemperature > 40) 2 else 0).toString(), integer),
                DbusData("/Alarms/LowChargeTemperature", (if (minTemperature < 10) 2 else 0).toString(), integer),
                DbusData("/Alarms/HighTemperature", (if (maxTemperature > 40) 2 else 0).toString(), integer),
                DbusData("/Alarms/LowTemperature", (if (minTemperature < 10) 2 else 0).toString(), integer),
            )
        )


        victronMqttClient.send(topic, toBeSendt)
    }


}

data class DbusService(
    val service: String,
    val serviceType: String,
    val serviceInstance: Int,
    val dbus_data: List<DbusData>,
)

data class DbusData(
    val path: String,
    val value: String,
    val valueType: ValueTypes = string,
    val writeable: Boolean = false,
)

enum class ValueTypes {
    string,
    integer,
    float,
    none,
}

