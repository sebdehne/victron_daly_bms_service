package com.dehnes.daly_bms_service.utils

import mu.KotlinLogging
import java.io.File
import java.util.concurrent.TimeUnit

object SerialPortFinder {

    private val logger = KotlinLogging.logger { }

    /*
     * Input: usb-device-id like "1a86:7523"
     * Output: path to seral port device, if found
     */
    fun findSerialPortFor(usbDeviceId: String): String? {

        // see
        // $ udevadm info /dev/ttyUSB0
        //
        // and
        // ID_PATH=platform-fd500000.pcie-pci-0000:01:00.0-usb-0:1.1:1.0

        val connectedSerialPorts = "ls -1 /sys/bus/usb-serial/devices".runCommand() ?: return null
        val device = connectedSerialPorts.firstOrNull { device ->
            val deviceData = "udevadm info /dev/$device".runCommand() ?: return@firstOrNull false
            deviceData.any { it.contains(usbDeviceId) }
        }

        return device?.let {
            val devFile = "/dev/$it"
            check("stty -F $devFile 9600 raw".runCommand() != null)
            devFile
        }
    }

    private fun String.runCommand(workingDir: String = ".") = try {
        val parts = this.split("\\s".toRegex())
        val proc = ProcessBuilder(*parts.toTypedArray())
            .directory(File(workingDir))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        proc.waitFor(60, TimeUnit.MINUTES)
        if (proc.exitValue() > 0) {
            logger.error { "exec=$this resulted in output=" + proc.errorStream.bufferedReader().readText() }
            null
        } else {
            proc.inputStream.bufferedReader().readLines()
        }
    } catch (e: Exception) {
        logger.error(e) { "" }
        null
    }

}


