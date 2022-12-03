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
        val connectedSerialPorts = "ls -1 /sys/bus/usb-serial/devices".runCommand() ?: return null
        val device = connectedSerialPorts.mapNotNull { device ->
            val lines = "cat /sys/bus/usb-serial/devices/$device/../../uevent".runCommand() ?: return@mapNotNull null
            val productLine = lines.firstOrNull { it.startsWith("PRODUCT=") } ?: return@mapNotNull null
            val productCodeRaw = productLine.split("=").last()
            val productCode = productCodeRaw.replace("/", ":")
            device to productCode
        }.firstOrNull { it.second.contains(usbDeviceId) }
            ?.first

        return device?.let { "/dev/$it" }
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


