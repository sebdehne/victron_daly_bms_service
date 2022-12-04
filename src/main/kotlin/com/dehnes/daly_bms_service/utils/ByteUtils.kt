package com.dehnes.daly_bms_service.utils

import java.nio.ByteBuffer

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

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

