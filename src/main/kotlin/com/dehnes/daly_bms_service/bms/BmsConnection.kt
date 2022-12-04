package com.dehnes.daly_bms_service.bms

import com.dehnes.daly_bms_service.utils.readInt16Bits
import com.dehnes.daly_bms_service.utils.readInt32Bits
import com.dehnes.daly_bms_service.utils.toUnsignedInt
import mu.KLogger
import mu.KotlinLogging
import java.io.File
import java.time.Duration
import kotlin.math.min

class BmsConnection(
    private val serialPortFile: String,
    private val bmsId: BmsId
) {

    private val logger = KotlinLogging.logger { }

    val file = File("/dev/ttyUSB0")
    val outputStream = file.outputStream()
    val inputStream = file.inputStream()
    val rxBuf = ByteArray(1024) { 0 }

    init {
        read(rxBuf.size, Duration.ofSeconds(2))
        logger.info { "Connected to $serialPortFile" }
    }

    fun readData() = try {
        BmsData.parseData(
            logger,
            bmsId,
            exec(DalyBmsCommand.VOUT_IOUT_SOC),
            exec(DalyBmsCommand.MIN_MAX_CELL_VOLTAGE),
            exec(DalyBmsCommand.MIN_MAX_TEMPERATURE),
            exec(DalyBmsCommand.DISCHARGE_CHARGE_MOS_STATUS),
            exec(DalyBmsCommand.STATUS_INFO),
            exec(DalyBmsCommand.CELL_VOLTAGES),
            exec(DalyBmsCommand.FAILURE_CODES),
        )
    } catch (e: Exception) {
        logger.error(e) { "Could not read from $bmsId" }
        null
    }

    private fun exec(cmd: DalyBmsCommand): ByteArray {
        val toSerialBytes = cmd.toSerialBytes()
        outputStream.write(toSerialBytes)
        outputStream.flush()
        return read(13 * cmd.responseFrames, Duration.ofSeconds(2))
    }

    private fun read(readBytes: Int, timeout: Duration): ByteArray {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        var read = 0
        while (true) {
            if (read >= readBytes || System.currentTimeMillis() > deadline) {
                break
            }
            if (inputStream.available() < 1) {
                Thread.sleep(100)
                continue
            }
            val r = inputStream.read(rxBuf, read, readBytes - read)
            if (r < 0) {
                Thread.sleep(100)
                continue
            }
            read += r
        }

        val array = ByteArray(read) { 0 }
        System.arraycopy(rxBuf, 0, array, 0, min(read, readBytes))
        return array
    }

    fun close() {
        try {
            outputStream.close()
            inputStream.close()
        } catch (e: Exception) {
            logger.error(e) { "Could not close $serialPortFile" }
        }
    }

}


// See https://diysolarforum.com/resources/daly-smart-bms-manual-and-documentation.48/
enum class DalyBmsCommand(
    val cmd: Int,
    val responseFrames: Int = 1
) {
    VOUT_IOUT_SOC(0x90),
    MIN_MAX_CELL_VOLTAGE(0x91),
    MIN_MAX_TEMPERATURE(0x92),
    DISCHARGE_CHARGE_MOS_STATUS(0x93),
    STATUS_INFO(0x94),
    CELL_VOLTAGES(0x95, responseFrames = 11),
    CELL_TEMPERATURE(0x96),
    CELL_BALANCE_STATE(0x97),
    FAILURE_CODES(0x98),
    DISCHRG_FET(0xd9),
    CHRG_FET(0xda),
    BMS_RESET(0x00);

    fun toSerialBytes(): ByteArray {

        var checksum: Byte = 0

        val buf = ByteArray(13) { 0 }
        buf[0] = 0xa5.toByte() // Start byte
        buf[1] = 0x80.toByte() // Host address
        buf[2] = cmd.toByte()
        buf[3] = 0x08 // Length

        (0..11).forEach {
            checksum = (checksum + buf[it]).toByte()
        }

        buf[12] = checksum

        return buf
    }
}

enum class BmStatus(val value: Int) {
    stationary(0),
    charged(1),
    discharged(2)
}

val errorsCodes = mapOf(
    (0 to 0) to "one stage warning of unit over voltage",
    (0 to 1) to "one stage warning of unit over voltage",
    (0 to 2) to "one stage warning of unit over voltage",
    (0 to 3) to "two stage warning of unit over voltage",
    (0 to 4) to "Total voltage is too high One alarm",
    (0 to 5) to "Total voltage is too high Level two alarm",
    (0 to 6) to "Total voltage is too low One alarm",
    (0 to 7) to "Total voltage is too low Level two alarm",

    (1 to 0) to "Charging temperature too high. One alarm",
    (1 to 1) to "Charging temperature too high. Level two alarm",
    (1 to 2) to "Charging temperature too low. One alarm",
    (1 to 3) to "Charging temperature's too low. Level two alarm",
    (1 to 4) to "Discharge temperature is too high. One alarm",
    (1 to 5) to "Discharge temperature is too high. Level two alarm",
    (1 to 6) to "Discharge temperature is too low. One alarm",
    (1 to 7) to "Discharge temperature is too low. Level two alarm",

    (2 to 0) to "Charge over current. Level one alarm",
    (2 to 1) to "Charge over current, level two alarm",
    (2 to 2) to "Discharge over current. Level one alarm",
    (2 to 3) to "Discharge over current, level two alarm",
    (2 to 4) to "SOC is too high an alarm",
    (2 to 5) to "SOC is too high. Alarm Two",
    (2 to 6) to "SOC is too low. level one alarm",
    (2 to 7) to "SOC is too low. level two alarm",

    (3 to 0) to "Excessive differential pressure level one alarm",
    (3 to 1) to "Excessive differential pressure level two alar",
    (3 to 2) to "Excessive temperature difference level one alarm",
    (3 to 3) to "Excessive temperature difference level two alarm",

    (4 to 0) to "charging MOS overtemperature warning",
    (4 to 1) to "discharge MOS overtemperature warning",
    (4 to 2) to "discharge MOS temperature detection sensor failure",
    (4 to 3) to "discharge MOS temperature detection sensor failure",
    (4 to 4) to "charging MOS adhesion failure",
    (4 to 5) to "discharge MOS adhesion failure",
    (4 to 6) to "charging MOS breaker failure",
    (4 to 7) to "discharge MOS breaker failure",

    (5 to 0) to "AFE acquisition chip malfunction",
    (5 to 1) to "monomer collect drop off",
    (5 to 2) to "Single Temperature Sensor Fault",
    (5 to 3) to "EEPROM storage failures",
    (5 to 4) to "RTC clock malfunction",
    (5 to 5) to "Precharge Failure",
    (5 to 6) to "vehicle communications malfunction",
    (5 to 7) to "intranet communication module malfunction",

    (6 to 0) to "Current Module Failure",
    (6 to 1) to "main pressure detection module",
    (6 to 2) to "Short circuit protection failure",
    (6 to 3) to "Low Voltage No Charging",
)


data class BmsData(
    val bmsId: BmsId,
    val voltage: Double,
    val current: Double,
    val soc: Double,
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
    val errors: List<String>,
) {
    companion object {
        fun parseData(
            logger: KLogger,
            usbId: BmsId,
            voutIoutSoc: ByteArray,
            minMaxCell: ByteArray,
            minMaxTemp: ByteArray,
            chargeDischargeStatus: ByteArray,
            statusInfo: ByteArray,
            cellVoltages: ByteArray,
            failureCodes: ByteArray,
        ): BmsData {
            return BmsData(
                bmsId = usbId,
                voltage = readInt16Bits(voutIoutSoc, 4).toDouble() / 10,
                current = (readInt16Bits(voutIoutSoc, 8).toDouble() - 30000) / 10,
                soc = readInt16Bits(voutIoutSoc, 10).toDouble() / 10,
                maxCellVoltage = (readInt16Bits(minMaxCell, 4).toDouble()) / 1000,
                maxCellNumber = minMaxCell[6].toInt(),
                minCellVoltage = (readInt16Bits(minMaxCell, 7).toDouble()) / 1000,
                minCellNumber = minMaxCell[9].toInt(),

                maxTemp = minMaxTemp[4].toInt() - 40,
                maxTempCellNumber = minMaxTemp[5].toInt(),
                minTemp = minMaxTemp[6].toInt() - 40,
                minTempCellNumber = minMaxTemp[7].toInt(),

                status = chargeDischargeStatus[4].toInt().let { statusInt ->
                    BmStatus.values().firstOrNull { it.value == statusInt } ?: run {
                        logger.error { "No BmStatus for statusInt=$statusInt" }
                        BmStatus.stationary
                    }
                },
                mosfetCharging = chargeDischargeStatus[5].toInt() == 1,
                mosfetDischarging = chargeDischargeStatus[6].toInt() == 1,
                lifeCycles = chargeDischargeStatus[7].toUnsignedInt(),
                remainingCapacity = readInt32Bits(chargeDischargeStatus, 8).toDouble() / 1000,

                chargerStatus = statusInfo[6].toInt() == 1,
                loadStatus = statusInfo[7].toInt() == 1,
                cycles = readInt16Bits(statusInfo, 9),
                cellVoltages = (0 until 6).flatMap { i ->
                    val offsetBase = i * 13
                    listOf(
                        readInt16Bits(cellVoltages, offsetBase + 5).toDouble(),
                        readInt16Bits(cellVoltages, offsetBase + 7).toDouble(),
                        readInt16Bits(cellVoltages, offsetBase + 9).toDouble(),
                    )
                }.subList(0, 16),
                errors = errorsCodes.mapNotNull { (bitPos, errorStr) ->
                    val (byteN, bitN) = bitPos
                    val b = failureCodes[byteN + 4].toUnsignedInt()
                    val mask = 1 shl bitN
                    if (b.and(mask) > 0) {
                        errorStr
                    } else null
                }
            )
        }
    }
}



