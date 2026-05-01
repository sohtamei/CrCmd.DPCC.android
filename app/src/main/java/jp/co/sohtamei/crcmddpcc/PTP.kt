package jp.co.sohtamei.crcmddpcc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PtpContainer(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray,
) {
    fun params(): List<Int> {
        val out = mutableListOf<Int>()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) out += buf.int
        return out
    }
}

object PtpCommandBuilder {
    fun makeCommand(opCode: Int, transactionId: Int, params: List<Int> = emptyList()): ByteArray {
        val buf = ByteBuffer.allocate(12 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(12 + params.size * 4)
        buf.putShort(PTPContainerType.Command.raw.toShort())
        buf.putShort(opCode.toShort())
        buf.putInt(transactionId)
        params.forEach { buf.putInt(it) }
        return buf.array()
    }

    fun makeData(opCode: Int, transactionId: Int, data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(12 + data.size)
        header.putShort(PTPContainerType.Data.raw.toShort())
        header.putShort(opCode.toShort())
        header.putInt(transactionId)
        return header.array() + data
    }
}

object PtpParser {
    fun parseContainer(data: ByteArray): PtpContainer {
        require(data.size >= 12) { "container too short" }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val length = buf.int
        require(length >= 12 && data.size >= length) { "invalid container length" }
        val type = buf.short.toInt() and 0xFFFF
        val code = buf.short.toInt() and 0xFFFF
        val txId = buf.int
        val payload = data.copyOfRange(12, length)
        return PtpContainer(length, type, code, txId, payload)
    }

    fun readUInt16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    fun readUInt32LE(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    fun readUInt64LE(data: ByteArray, offset: Int): Long {
        var v = 0L
        repeat(8) { i -> v = v or ((data[offset + i].toLong() and 0xFF) shl (8 * i)) }
        return v
    }
}

fun ByteArray.hexDump(separator: String = " "): String = joinToString(separator) { "%02X".format(it) }
