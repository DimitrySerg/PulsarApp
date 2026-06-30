package com.pulsar.app

/**
 * Прямой порт протокола "Пульсар" из pulsar.cpp / pulsar.h.
 * Формат пакета:
 *  [addr 4 байта, big-endian][func 1 байт][len 1 байт][payload...][id 0x01,0x00][crc16 2 байта, little-endian]
 *  len = 10 + payload.size  (10 = 6 байт заголовка-без-payload + 4 байта концевых: id(2)+crc(2))
 *
 * Коды функций (см. pulsar.h):
 */
object Func {
    const val CODE = 0x91
    const val READ = 0x0A
    const val WRITE = 0x0B
    const val CHAN_READ = 0x01
    const val CHAN_WRITE = 0x03
    const val JOUR_READ = 0x0C
    const val ARCH_READ = 0x06
    const val POLY_READ = 0x92
    const val TIME_READ = 0x04
    const val TIME_WRITE = 0x05
    const val LINEAR_POLY_READ = 0x80
    const val LINEAR_POLY_WRITE = 0x81
}

/** Результат разбора пакета ответа прибора. */
data class PulsarPacket(
    var devAddr: Int = 0,
    var func: Int = 0,
    var len: Int = 0,          // длина полезной нагрузки (без служебных байт)
    var payload: ByteArray = ByteArray(0),
    var error: Int = 1,        // 0 = OK, иначе код ошибки (см. decodeError)
    var packetShift: Int = 0
)

object PulsarProtocol {

    /** CRC16 (Modbus, poly 0xA001, init 0xFFFF) — идентичен pulsar_crc() в pulsar.cpp. */
    fun crc16(buf: ByteArray, length: Int): Int {
        if (length <= 0) return 0
        var result = 0xFFFF
        for (j in 0 until length) {
            result = result xor (buf[j].toInt() and 0xFF)
            for (i in 0 until 8) {
                result = if (result and 1 != 0) (result ushr 1) xor 0xA001 else result ushr 1
            }
        }
        return result and 0xFFFF
    }

    /** Кодирование запроса. devAddr, func и payload предоставляются вызывающей стороной. */
    fun encodePacket(devAddr: Int, func: Int, payload: ByteArray): ByteArray {
        val len = 10 + payload.size
        val buf = ByteArray(len)

        buf[0] = ((devAddr ushr 24) and 0xFF).toByte()
        buf[1] = ((devAddr ushr 16) and 0xFF).toByte()
        buf[2] = ((devAddr ushr 8) and 0xFF).toByte()
        buf[3] = (devAddr and 0xFF).toByte()
        buf[4] = (func and 0xFF).toByte()
        buf[5] = (len and 0xFF).toByte() // длина пакета однобайтовая, как в исходном коде (макс. 255)

        for (i in payload.indices) buf[6 + i] = payload[i]

        buf[6 + payload.size] = 1 // ID
        buf[7 + payload.size] = 0 // ID

        val crc = crc16(buf, len - 2)
        buf[len - 2] = (crc and 0xFF).toByte()
        buf[len - 1] = ((crc ushr 8) and 0xFF).toByte()

        return buf
    }

    /**
     * Разбор накопленного буфера приёма. Логика идентична pulsar_parse_packet():
     * ищем валидный пакет, отбрасывая мусорные байты с начала, если CRC не совпадает.
     * Возвращает null, если пакет ещё не полон (нужно ждать ещё байты).
     */
    fun tryParsePacket(buffer: MutableList<Byte>): PulsarPacket? {
        var shift = 0
        while (true) {
            if (buffer.size < 6) return null // пакет не полон

            val declaredLen = buffer[5].toInt() and 0xFF

            if (buffer.size < declaredLen) return null // пакет не полон по заявленной длине

            val arr = buffer.toByteArray()
            if (declaredLen >= 10 && crcCheck(arr, declaredLen)) {
                // нашли валидный пакет
                val result = PulsarPacket()
                result.packetShift = shift
                result.devAddr = ((arr[0].toInt() and 0xFF) shl 24) or
                        ((arr[1].toInt() and 0xFF) shl 16) or
                        ((arr[2].toInt() and 0xFF) shl 8) or
                        (arr[3].toInt() and 0xFF)
                result.func = arr[4].toInt() and 0xFF
                if (declaredLen < 10) {
                    result.error = 3
                    return result
                }
                val payloadLen = declaredLen - 10
                result.payload = arr.copyOfRange(6, 6 + payloadLen)
                result.len = payloadLen
                result.error = 0

                // Ошибка на уровне Пульсар: func==0 и есть 1 байт кода ошибки
                if (result.func == 0 && result.len > 0) {
                    result.error = result.payload[0].toInt() and 0xFF
                }
                return result
            } else {
                if (buffer.isEmpty()) return null
                buffer.removeAt(0)
                shift++
                if (buffer.size < 6) return null
            }
        }
    }

    private fun crcCheck(buf: ByteArray, length: Int): Boolean {
        val crcPack = ((buf[length - 1].toInt() and 0xFF) shl 8) or (buf[length - 2].toInt() and 0xFF)
        val crc = crc16(buf, length - 2)
        return crc == crcPack
    }

    fun decodeError(error: Int): String = when (error) {
        0x00 -> "ОК"
        0x01 -> "Отсутствует запрашиваемый код функции"
        0x02 -> "Ошибка в битовой маске запроса"
        0x03 -> "Ошибочная длина запроса"
        0x04 -> "Отсутствует параметр"
        0x05 -> "Запись заблокирована, требуется авторизация"
        0x06 -> "Записываемое значение (параметр) находится вне заданного диапазона"
        0x07 -> "Отсутствует запрашиваемый тип архива"
        0x08 -> "Превышение максимального количества архивных значений за один пакет"
        0xFF -> "Нет ответа от прибора"
        else -> "Неизвестная ошибка ($error)"
    }

    // ---- Построение payload для типовых операций (см. pulsar_read_param/_write_param/_read_chann/_write_chann) ----

    fun buildReadParamPayload(paramNum: Int): ByteArray {
        return byteArrayOf((paramNum and 0xFF).toByte(), ((paramNum ushr 8) and 0xFF).toByte())
    }

    fun buildWriteParamPayload(paramNum: Int, value: ByteArray): ByteArray {
        val out = ByteArray(2 + value.size)
        out[0] = (paramNum and 0xFF).toByte()
        out[1] = ((paramNum ushr 8) and 0xFF).toByte()
        System.arraycopy(value, 0, out, 2, value.size)
        return out
    }

    fun buildReadChannelsPayload(mask: Long): ByteArray {
        return byteArrayOf(
            (mask and 0xFF).toByte(),
            ((mask ushr 8) and 0xFF).toByte(),
            ((mask ushr 16) and 0xFF).toByte(),
            ((mask ushr 24) and 0xFF).toByte()
        )
    }

    fun buildWriteChannelPayload(channNum: Int, value: Float): ByteArray {
        val mask = 1L shl channNum
        val maskBytes = buildReadChannelsPayload(mask)
        val valBits = java.lang.Float.floatToIntBits(value)
        val valBytes = byteArrayOf(
            (valBits and 0xFF).toByte(),
            ((valBits ushr 8) and 0xFF).toByte(),
            ((valBits ushr 16) and 0xFF).toByte(),
            ((valBits ushr 24) and 0xFF).toByte()
        )
        return maskBytes + valBytes
    }
}
