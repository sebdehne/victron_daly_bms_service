package com.dehnes.daly_bms_service.bms

import mu.KotlinLogging
import java.io.File
import java.nio.ByteBuffer
import java.time.Duration
import kotlin.math.min

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

class BmsConnection(
    private val serialPortFile: String
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

    fun exec(cmd: DalyBmsCommand): ByteArray {
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


//
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


fun readInt16Bits(buf: ByteArray, offset: Int): Int {
    val byteBuffer = ByteBuffer.allocate(4)
    byteBuffer.put(0)
    byteBuffer.put(0)
    byteBuffer.put(buf, offset, 2)
    byteBuffer.flip()
    return byteBuffer.getInt(0)
}

fun readInt32Bits(buf: ByteArray, offset: Int): Int {
    val byteBuffer = ByteBuffer.allocate(4)
    byteBuffer.put(buf, offset, 4)
    byteBuffer.flip()
    return byteBuffer.getInt(0)
}

fun Byte.toUnsignedInt(): Int = this.toInt().let {
    if (it < 0)
        it + 256
    else
        it
}
