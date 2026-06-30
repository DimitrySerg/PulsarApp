package com.pulsar.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Кодирование/декодирование значений параметров и каналов согласно их типу данных (little-endian, как pulsar_var_t). */
object ValueCodec {

    fun decode(item: ParamItem, payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (item.dtype.lowercase()) {
                "float32" -> bb.float.toString()
                "int32" -> bb.int.toString()
                "uint32" -> (bb.int.toLong() and 0xFFFFFFFFL).toString()
                "int16" -> bb.short.toString()
                "uint16" -> (bb.short.toInt() and 0xFFFF).toString()
                "int8" -> bb.get().toString()
                "uint8" -> (bb.get().toInt() and 0xFF).toString()
                "int64" -> bb.long.toString()
                "uint64" -> java.lang.Long.toUnsignedString(bb.long)
                "user64", "user48", "user8" -> String(payload, Charsets.US_ASCII).trim('\u0000', ' ')
                else -> payload.joinToString(" ") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            payload.joinToString(" ") { "%02X".format(it) }
        }
    }

    /** Для канала ответ CHAN_READ содержит маску (4 байта) + значение float (4 байта далее). */
    fun decodeChannelValue(payload: ByteArray): String {
        if (payload.size < 8) return payload.joinToString(" ") { "%02X".format(it) }
        val bb = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
        return bb.float.toString()
    }

    fun encode(item: ParamItem, text: String): ByteArray {
        val bb = ByteBuffer.allocate(item.byteLength).order(ByteOrder.LITTLE_ENDIAN)
        when (item.dtype.lowercase()) {
            "float32" -> bb.putFloat(text.toFloatOrNull() ?: 0f)
            "int32" -> bb.putInt(text.toIntOrNull() ?: 0)
            "uint32" -> bb.putInt((text.toLongOrNull() ?: 0L).toInt())
            "int16" -> bb.putShort((text.toIntOrNull() ?: 0).toShort())
            "uint16" -> bb.putShort((text.toIntOrNull() ?: 0).toShort())
            "int8" -> bb.put((text.toIntOrNull() ?: 0).toByte())
            "uint8" -> bb.put((text.toIntOrNull() ?: 0).toByte())
            "int64" -> bb.putLong(text.toLongOrNull() ?: 0L)
            "uint64" -> bb.putLong(text.toLongOrNull() ?: 0L)
            "user64", "user48", "user8" -> {
                val bytes = text.toByteArray(Charsets.US_ASCII)
                val n = minOf(bytes.size, item.byteLength)
                bb.put(bytes, 0, n)
            }
            else -> {}
        }
        return bb.array()
    }
}
