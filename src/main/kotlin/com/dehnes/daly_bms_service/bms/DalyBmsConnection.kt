package com.dehnes.daly_bms_service.bms

import com.dehnes.daly_bms_service.utils.readInt16Bits
import com.dehnes.daly_bms_service.utils.readInt32Bits
import com.dehnes.daly_bms_service.utils.toUnsignedInt
import com.dehnes.daly_bms_service.zoneId
import mu.KLogger
import mu.KotlinLogging
import java.io.File
import java.time.Duration
import java.time.Instant

class BmsConnection(
    private val serialPortFile: String,
    private val bmsId: BmsId,
    private val numberOfCells: Int,
) {

    private val logger = KotlinLogging.logger { }

    val file = File(serialPortFile)
    val outputStream = file.outputStream()
    val inputStream = file.inputStream()
    val rxBuf = ByteArray(1024) { 0 }
    val batterId = bmsId.bmsId

    init {
        read(rxBuf.size, Duration.ofSeconds(1))
        logger.info { "$batterId - Connected to $serialPortFile" }
    }

    fun readData() = try {
        readDataInternal()
    } catch (e: Exception) {
        logger.error(e) { "$batterId - Could not read from $bmsId" }
        null
    }

    fun writeSoc(soc: Int): Boolean {
        val responses = exec(
            DalyBmsCommand.WRITE_SOC,
            isMultiResponse = false,
            value = soc
        )
        logger.info { "Wrote SOC $responses" }
        return responses.isNotEmpty()
    }

    private fun readDataInternal(): BmsData {
        val cellVoltages = exec(DalyBmsCommand.CELL_VOLTAGES, true).let { frames ->
            val result = mutableListOf<Double>()

            frames
                .distinctBy { it.data[0].toUnsignedInt() }
                .sortedBy { it.data[0].toUnsignedInt() }
                .forEachIndexed { index, f ->
                    val frameNumber = f.data[0].toUnsignedInt()
                    check(frameNumber == index + 1) { "Frame number does not match" }
                    repeat(3) { i ->
                        if (result.size >= numberOfCells) return@forEachIndexed
                        result.add(readInt16Bits(f.data, 1 + i + i).toDouble() / 1000)
                    }
                }

            check(result.size == numberOfCells)
            result
        }

        return parseData(
            logger,
            bmsId,
            exec(DalyBmsCommand.VOUT_IOUT_SOC).single(),
            exec(DalyBmsCommand.MIN_MAX_CELL_VOLTAGE).single(),
            exec(DalyBmsCommand.MIN_MAX_TEMPERATURE).single(),
            exec(DalyBmsCommand.DISCHARGE_CHARGE_MOS_STATUS).single(),
            exec(DalyBmsCommand.STATUS_INFO).single(),
            cellVoltages,
            exec(DalyBmsCommand.FAILURE_CODES).single(),
        )
    }

    private fun exec(cmd: DalyBmsCommand, isMultiResponse: Boolean = false, value: Any? = null): List<ResponseFrame> {
        repeat(10) { retry ->
            val toSerialBytes = cmd.toSerialBytes(value)
            outputStream.write(toSerialBytes)
            outputStream.flush()
            logger.debug { "$batterId - Sent retry=$retry cmd=$cmd toSerialBytes=${toSerialBytes.toList()}" }

            val responseFrames = readFramesV2(isMultiResponse)
            if (responseFrames.isNotEmpty()) {
                val good = responseFrames.filter { it.isChecksumValid }
                if (isMultiResponse && good.size > 5) return good
                else if (!isMultiResponse && good.size == 1) return good
            }
        }

        error("Could not read response(s) for cmd=$cmd")
    }

    private fun readFramesV2(isMultiResponse: Boolean): List<ResponseFrame> {
        return if (!isMultiResponse) {
            listOfNotNull(readFrame())
        } else {
            val data = read(rxBuf.size, Duration.ofSeconds(1))

            val results = mutableListOf<ResponseFrame>()
            var pos = 0
            while (pos + 13 <= data.size) {

                // search for start pattern
                if (data[pos] != 0xa5.toByte()) {
                    pos++
                    continue
                }
                if (data[pos + 1] != 1.toByte()) {
                    pos++
                    continue
                }
                if (data[pos + 2] != DalyBmsCommand.CELL_VOLTAGES.cmd.toByte()) {
                    pos++
                    continue
                }
                if (data[pos + 3] != 8.toByte()) {
                    pos++
                    continue
                }

                val rawData = ByteArray(13) { 0 }
                System.arraycopy(rxBuf, pos, rawData, 0, rawData.size)
                var checksum: Byte = 0
                (0..11).forEach {
                    checksum = (checksum + rawData[it]).toByte()
                }
                results.add(
                    ResponseFrame(
                        rawData[0].toUnsignedInt(),
                        rawData[1].toUnsignedInt(),
                        rawData[2].toUnsignedInt(),
                        rawData[3].toUnsignedInt(),
                        ByteArray(8).apply {
                            System.arraycopy(rawData, 4, this, 0, 8)
                        },
                        (checksum == rawData[12]).apply {
                            if (!this) {
                                logger.debug { "$batterId - Checksum failure. checksum=$checksum rawData=${rawData.toList()}" }
                            }
                        },
                        rawData
                    ).apply {
                        logger.debug { "$batterId - readFramesV2 pos=$pos frame=$this" }
                    }
                )

                pos += 13
            }


            results
        }
    }

    private fun read(target: Int, timeout: Duration): ByteArray {
        logger.debug { "$batterId - Trying to readBytes=$target during timeout=${timeout.toSeconds()} seconds" }
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        var read = 0
        while (read < target && System.currentTimeMillis() <= deadline) {
            if (inputStream.available() < 1) {
                Thread.sleep(100)
                continue
            }
            val r = inputStream.read(rxBuf, read, target - read)
            if (r < 0) {
                logger.warn { "$batterId - EOF detected" }
                Thread.sleep(100)
                continue
            }
            read += r
        }

        check(read <= target) { "Read too much" }

        val array = ByteArray(read) { 0 }
        System.arraycopy(rxBuf, 0, array, 0, read)
        logger.debug { "$batterId - Read array=${array.toList()}" }
        return array
    }

    private fun readFrame(): ResponseFrame? {
        val rawData = read(13, Duration.ofMillis(500))
        if (rawData.isEmpty()) return null // timeout
        if (rawData.size != 13) {
            logger.error { "$batterId - Did not read 13 bytes rawData=${rawData.toList()}" }
            return null
        }

        var checksum: Byte = 0
        (0..11).forEach {
            checksum = (checksum + rawData[it]).toByte()
        }

        return ResponseFrame(
            rawData[0].toUnsignedInt(),
            rawData[1].toUnsignedInt(),
            rawData[2].toUnsignedInt(),
            rawData[3].toUnsignedInt(),
            ByteArray(8).apply {
                System.arraycopy(rawData, 4, this, 0, 8)
            },
            (checksum == rawData[12]).apply {
                if (!this) {
                    logger.debug { "$batterId - Checksum failure. checksum=$checksum rawData=${rawData.toList()}" }
                }
            },
            rawData
        )
    }

    fun close() {
        try {
            outputStream.close()
            inputStream.close()
        } catch (e: Exception) {
            logger.error(e) { "$batterId - Could not close $serialPortFile" }
        }
    }

}

data class ResponseFrame(
    val startByte: Int,
    val hostAddr: Int,
    val commandId: Int,
    val length: Int,
    val data: ByteArray,
    val isChecksumValid: Boolean,
    val rawData: ByteArray
)


// See https://diysolarforum.com/resources/daly-smart-bms-manual-and-documentation.48/
// TODO figure out how to set SoC (it is possible via PC software)
enum class DalyBmsCommand(val cmd: Int) {
    VOUT_IOUT_SOC(0x90),
    MIN_MAX_CELL_VOLTAGE(0x91),
    MIN_MAX_TEMPERATURE(0x92),
    DISCHARGE_CHARGE_MOS_STATUS(0x93),
    STATUS_INFO(0x94),
    CELL_VOLTAGES(0x95),
    CELL_TEMPERATURE(0x96),
    CELL_BALANCE_STATE(0x97),
    FAILURE_CODES(0x98),
    UNKNOWN_1(0x99),
    UNKNOWN_2(0xd8),
    DISCHRG_FET(0xd9),
    CHRG_FET(0xda),
    BMS_RESET(0x00),
    WRITE_SOC(0x21),
    ;

    fun toSerialBytes(value: Any?): ByteArray {

        var checksum: Byte = 0

        val buf = ByteArray(13) { 0 }
        buf[0] = 0xa5.toByte() // Start byte
        buf[1] = 0x40.toByte() // Host address
        buf[2] = cmd.toByte()
        buf[3] = 0x08 // Length

        if (this == WRITE_SOC) {
            val soc = value as Int
            check(soc in 0..100) {
                "Invalid soc $soc"
            }
            val soc10 = soc * 10
            val dateTime = Instant.now().atZone(zoneId)
            buf[4] = dateTime.year.mod(100).toByte() // year
            buf[5] = dateTime.monthValue.toByte()
            buf[6] = dateTime.dayOfMonth.toByte()
            buf[7] = dateTime.hour.toByte()
            buf[8] = dateTime.minute.toByte()
            buf[9] = dateTime.second.toByte()
            buf[10] = soc10.shr(8).toByte()
            buf[11] = soc10.toByte()
        }

        (0..11).forEach {
            checksum = (checksum + buf[it]).toByte()
        }

        buf[12] = checksum

        return buf
    }
}

fun parseData(
    logger: KLogger,
    usbId: BmsId,
    voutIoutSoc: ResponseFrame,
    minMaxCell: ResponseFrame,
    minMaxTemp: ResponseFrame,
    chargeDischargeStatus: ResponseFrame,
    statusInfo: ResponseFrame,
    cellVoltages: List<Double>,
    failureCodes: ResponseFrame,
): BmsData {
    val current = (readInt16Bits(voutIoutSoc.data, 4).toDouble() - 30000) / 10
    return BmsData(
        bmsId = usbId,
        timestamp = Instant.now(),
        voltage = readInt16Bits(voutIoutSoc.data, 0).toDouble() / 10,
        current = current,
        soc = readInt16Bits(voutIoutSoc.data, 6).toDouble() / 10,
        avgEstimatedSoc = cellVoltages.map { SoCEstimator.estimate(it, current < 0) }.average(),
        maxCellVoltage = (readInt16Bits(minMaxCell.data, 0).toDouble()) / 1000,
        maxCellNumber = minMaxCell.data[2].toInt(),
        minCellVoltage = (readInt16Bits(minMaxCell.data, 3).toDouble()) / 1000,
        minCellNumber = minMaxCell.data[5].toInt(),

        maxTemp = minMaxTemp.data[0].toInt() - 40,
        maxTempCellNumber = minMaxTemp.data[1].toInt(),
        minTemp = minMaxTemp.data[2].toInt() - 40,
        minTempCellNumber = minMaxTemp.data[3].toInt(),

        status = chargeDischargeStatus.data[0].toInt().let { statusInt ->
            BmStatus.values().firstOrNull { it.value == statusInt } ?: run {
                logger.error { "${usbId.bmsId} - No BmStatus for statusInt=$statusInt" }
                BmStatus.stationary
            }
        },
        mosfetCharging = chargeDischargeStatus.data[1].toInt() == 1,
        mosfetDischarging = chargeDischargeStatus.data[2].toInt() == 1,
        lifeCycles = chargeDischargeStatus.data[3].toUnsignedInt(),
        remainingCapacity = readInt32Bits(chargeDischargeStatus.data, 4).toDouble() / 1000,

        chargerStatus = statusInfo.data[2].toInt() == 1,
        loadStatus = statusInfo.data[3].toInt() == 1,
        cycles = readInt16Bits(statusInfo.data, 5),
        cellVoltages = cellVoltages,
        socEstimates = cellVoltages.map { SoCEstimator.estimate(it, current < 0) },
        errors = errorsCodes.mapNotNull { (bitPos, errorStr) ->
            val (byteN, bitN) = bitPos
            val b = failureCodes.data[byteN].toUnsignedInt()
            val mask = 1 shl bitN
            if (b.and(mask) > 0) {
                errorStr
            } else null
        }
    )
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




