package com.pulsar.app

/** Тип записи: параметр (FUNC_READ/FUNC_WRITE) или канал (CHAN_READ/CHAN_WRITE). */
enum class ItemKind { PARAMETER, CHANNEL }

data class ParamItem(
    val kind: ItemKind,
    val num: Int,           // № по порядку в таблице (для канала — также номер бита маски)
    val name: String,
    val varName: String,
    val addr: String,       // адрес в hex как в таблице (для параметра — номер параметра, для канала — номер бита)
    val dtype: String,
    val min: String,
    val max: String,
    val note: String,
    val readAccess: String,
    val writeAccess: String
) {
    /** Числовой адрес: для параметра — uint16 номер параметра, для канала — номер бита маски. */
    val addrInt: Int by lazy {
        val s = addr.trim().removePrefix("0x").removePrefix("0X")
        s.toIntOrNull(16) ?: num
    }

    val byteLength: Int by lazy {
        when (dtype.lowercase()) {
            "float32", "int32", "uint32" -> 4
            "int16", "uint16" -> 2
            "int8", "uint8" -> 1
            "int64", "uint64", "user64" -> 8
            "user48" -> 6
            "user8" -> 1
            else -> 4
        }
    }

    /** Значение, прочитанное/записанное последним обменом (для отображения в таблице). */
    var lastValue: String = ""
    var lastStatus: String = ""
    var selected: Boolean = false
    /** Текст, введённый пользователем для записи (используется при групповой записи). */
    var inputValue: String = ""
}
