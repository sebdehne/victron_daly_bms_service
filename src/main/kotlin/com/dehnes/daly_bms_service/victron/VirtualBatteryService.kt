package com.dehnes.daly_bms_service.victron

import com.dehnes.daly_bms_service.bms.BmsData
import com.dehnes.daly_bms_service.bms.BmsId
import com.dehnes.daly_bms_service.bms.BmsService
import com.dehnes.daly_bms_service.bms.numberOfCells
import com.dehnes.daly_bms_service.utils.PersistenceService
import com.dehnes.daly_bms_service.utils.round2d
import com.dehnes.daly_bms_service.victron.ValueTypes.*
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

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
        if (knownBmsData.isEmpty()) {
            logger.info { "Cannot send data to Victron - no BMS-data found" }
            return
        }

        val numberOfCells = persistenceService.numberOfCells()

        val bmsOfflineAfterSeconds = persistenceService["daly_bms.bmsOfflineAfterSeconds", "60"]!!.toLong()
        val now = Instant.now()

        val (onlineBmses, offlineBmses) = knownBmsData.values.partition {
            it.timestamp.isAfter(now.minusSeconds(bmsOfflineAfterSeconds))
        }

        val bmsOfflineAlarm = if (offlineBmses.isNotEmpty()) 2 else 0

        val minBatteryVoltage = onlineBmses.minOf { it.minCellVoltage } * persistenceService.numberOfCells()

        val capacityRemainig = onlineBmses.map { it.remainingCapacity }.sum()
        val installedCapacity = onlineBmses.map { it.bmsId.capacity }.sum()
        val soc = onlineBmses.map { it.soc }.average().round2d()
        val voltage = onlineBmses.map { it.voltage }.average().round2d()
        val current = onlineBmses.sumOf { it.current }.round2d()
        val power = (voltage * current).round2d()
        val maxTemperature = onlineBmses.map { it.maxTemp }.max()
        val minTemperature = onlineBmses.map { it.minTemp }.min()
        val maxCellVoltage = onlineBmses.maxBy { it.maxCellVoltage }
        val minCellVoltage = onlineBmses.minBy { it.minCellVoltage }
        val chargeCycles = onlineBmses.maxBy { it.cycles }.cycles

        val cellDelta = maxCellVoltage.maxCellVoltage - minCellVoltage.minCellVoltage

        val controlParams: ControlParams = calculateControllParameters(onlineBmses)

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
                DbusData("/FirmwareVersion", "1.0"), // TODO read from daly
                DbusData("/HardwareVersion", "1.0"), // TODO read from daly
                DbusData("/Connected", "1", integer),
                DbusData("/CustomName", "Daly Bms service", writeable = true),

                DbusData("/Info/BatteryLowVoltage", minBatteryVoltage.toString(), float),

                // DVCC data:
                DbusData(
                    "/Info/MaxChargeVoltage",
                    controlParams.chargeParams.maxChargeVoltage.round2d().toString(),
                    float
                ),
                DbusData(
                    "/Info/MaxChargeCurrent",
                    controlParams.chargeParams.maxChargeCurrent.round2d().toString(),
                    float
                ),
                DbusData(
                    "/Info/MaxDischargeCurrent",
                    controlParams.dischargeParams.maxDischargeCurrent.round2d().toString(),
                    float
                ),
                DbusData("/Io/AllowToCharge", if (controlParams.chargeParams.allowToCharge) "1" else "0", integer),
                DbusData(
                    "/Io/AllowToDischarge",
                    if (controlParams.dischargeParams.allowToDischarge) "1" else "0",
                    integer
                ),

                DbusData("/System/NrOfCellsPerBattery", numberOfCells.toString(), integer),
                DbusData("/System/NrOfModulesOnline", (onlineBmses.size).toString(), integer),
                DbusData("/System/NrOfModulesOffline", (offlineBmses.size).toString(), integer),
                DbusData(
                    "/System/NrOfModulesBlockingCharge",
                    controlParams.chargeParams.nrOfModulesBlockingCharge.toString(),
                    integer
                ),
                DbusData(
                    "/System/NrOfModulesBlockingDischarge",
                    controlParams.dischargeParams.nrOfModulesBlockingDischarge.toString(),
                    integer
                ),

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
                DbusData("/Alarms/HighChargeTemperature", (if (maxTemperature > 40) 2 else 0).toString(), integer),
                DbusData("/Alarms/LowChargeTemperature", (if (minTemperature < 10) 2 else 0).toString(), integer),
                DbusData("/Alarms/HighTemperature", (if (maxTemperature > 40) 2 else 0).toString(), integer),
                DbusData("/Alarms/LowTemperature", (if (minTemperature < 10) 2 else 0).toString(), integer),

                DbusData("/Alarms/Alarm", bmsOfflineAlarm.toString(), integer),
                DbusData("/Alarms/InternalFailure", bmsOfflineAlarm.toString(), integer),
            )
        )

        victronMqttClient.send(topic, toBeSendt)
    }

    private fun calculateControllParameters(onlineBmses: List<BmsData>) =
        ControlParams(
            chargeParams = calculateChargeParams(onlineBmses),
            dischargeParams = calculateDischargeParams(onlineBmses),
        )

    private fun calculateChargeParams(onlineBmses: List<BmsData>): ChargeParams {
        val numberOfCells = persistenceService.numberOfCells()
        val maxChargeCurrent = persistenceService["daly_bms.maxChargeCurrent", "210"]!!.toDouble()
        val maxChargeTemp = persistenceService["daly_bms.maxChargeTemp", "45"]!!.toInt()
        val minChargeTemp = persistenceService["daly_bms.minChargeTemp", "5"]!!.toInt()
        val maxCellVoltage = persistenceService["daly_bms.maxCellVoltage", "3.45"]!!.toDouble()

        val bmsesAllowingCharging = onlineBmses.filter { bms ->
            val tempOK = bms.maxTemp in (minChargeTemp..maxChargeTemp)
            val chargingOK = bms.mosfetCharging
            val cellVoltageOK = bms.maxCellVoltage <= maxCellVoltage

            if (tempOK && chargingOK && cellVoltageOK) {
                true
            } else {
                logger.warn { "${bms.bmsId} does not allow charging: tempOK=$tempOK chargingOK=$chargingOK cellVoltageOK=$cellVoltageOK" }
                false
            }
        }
        val nrOfModulesBlockingCharge = onlineBmses.size - bmsesAllowingCharging.size

        return if (nrOfModulesBlockingCharge > 0) {
            ChargeParams(
                maxChargeVoltage = (maxCellVoltage * numberOfCells),
                maxChargeCurrent = 0.0,
                allowToCharge = false,
                nrOfModulesBlockingCharge
            )
        } else {
            val soc = onlineBmses.map { it.soc }.average()

            val chargeCurrent = if (soc >= 90) {
                val slope = ((maxChargeCurrent - (maxChargeCurrent * 0.3)) / (100 - 90)) * -1
                (90 - soc) * slope + maxChargeCurrent
            } else {
                maxChargeCurrent
            }

            ChargeParams(
                maxChargeVoltage = (maxCellVoltage * numberOfCells),
                maxChargeCurrent = chargeCurrent,
                allowToCharge = true,
                0
            )
        }

    }

    private fun calculateDischargeParams(onlineBmses: List<BmsData>): DischargeParams {
        val maxDischargeCurrent = persistenceService["daly_bms.maxDischargeCurrent", "380"]!!.toDouble()
        val minCellVoltage = persistenceService["daly_bms.minCellVoltage", "3.00"]!!.toDouble()
        val minDischargeTemp = persistenceService["daly_bms.minDischargeTemp", "-10"]!!.toInt()
        val maxDischargeTemp = persistenceService["daly_bms.maxDischargeTemp", "45"]!!.toInt()
        val minDischargeSoC = persistenceService["daly_bms.minDischargeSoC", "10"]!!.toInt()

        val soc = onlineBmses.map { it.soc }.average()

        val bmsesAllowingDischarging = onlineBmses.filter { bms ->
            val tempOK = bms.maxTemp in (minDischargeTemp..maxDischargeTemp)
            val dischargingOK = bms.mosfetDischarging
            val cellVoltageOK = bms.minCellVoltage >= minCellVoltage
            val socOK = soc > minDischargeSoC

            if (tempOK && dischargingOK && cellVoltageOK && socOK) {
                true
            } else {
                logger.warn {
                    "${bms.bmsId} does not allow discharging: " +
                            "tempOK=$tempOK " +
                            "dischargingOK=$dischargingOK " +
                            "cellVoltageOK=$cellVoltageOK " +
                            "socOK=$socOK"
                }
                false
            }
        }
        val nrOfModulesBlockingDischarge = onlineBmses.size - bmsesAllowingDischarging.size

        return if (nrOfModulesBlockingDischarge > 0) {
            DischargeParams(
                maxDischargeCurrent = 0.0,
                allowToDischarge = false,
                nrOfModulesBlockingDischarge
            )
        } else {
            DischargeParams(
                maxDischargeCurrent = maxDischargeCurrent,
                allowToDischarge = true,
                0
            )
        }
    }
}

data class ChargeParams(
    val maxChargeVoltage: Double,
    val maxChargeCurrent: Double = 210.0,
    val allowToCharge: Boolean,
    val nrOfModulesBlockingCharge: Int,
)

data class DischargeParams(
    val maxDischargeCurrent: Double = 350.0,
    val allowToDischarge: Boolean,
    val nrOfModulesBlockingDischarge: Int,
)

data class ControlParams(
    val chargeParams: ChargeParams,
    val dischargeParams: DischargeParams,
)

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

