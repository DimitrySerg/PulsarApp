package com.pulsar.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запись в журнале обмена — для страницы «Отладка». */
data class LogEntry(
    val time: String,
    val title: String,      // например: "Чтение параметра №5 (ke_mcu_reset_count)"
    val sentHex: String,
    val recvHex: String,
    val status: String,
    val ok: Boolean
)

/** Простой потокобезопасный журнал обмена (хранится в памяти процесса, очищается вручную). */
object TransactionLog {
    private val entries = mutableListOf<LogEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private const val MAX_ENTRIES = 500

    @Synchronized
    fun add(title: String, sent: ByteArray, recv: ByteArray, status: String, ok: Boolean) {
        entries.add(
            LogEntry(
                time = timeFmt.format(Date()),
                title = title,
                sentHex = toHex(sent),
                recvHex = toHex(recv),
                status = status,
                ok = ok
            )
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    @Synchronized
    fun all(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    fun toHex(buf: ByteArray): String =
        if (buf.isEmpty()) "—" else buf.joinToString(" ") { "%02X".format(it) }
}
