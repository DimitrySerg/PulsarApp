package com.pulsar.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.delay
import java.io.IOException

/** Параметры передачи по com-порту (аналог pulsar_params_t из pulsar.h). */
data class SerialSettings(
    var baudRate: Int = 9600,
    var dataBits: Int = 8,
    var stopBits: Int = UsbSerialPort.STOPBITS_1,
    // Parity: 0=None,1=Odd,2=Even — как в Win32 DCB.Parity
    var parity: Int = UsbSerialPort.PARITY_NONE,
    var respNum: Int = 3,          // количество перезапросов при отсутствии ответа
    var ansTimeoutMs: Int = 500,   // тайм-аут ожидания ответа, мс
    var devAddr: Int = 1           // адрес прибора (devAddr в pulsar_data_t)
)

/** Результат одной транзакции обмена. */
data class TransactionResult(
    val ok: Boolean,
    val error: Int,
    val errorText: String,
    val payload: ByteArray = ByteArray(0),
    val rawSent: ByteArray = ByteArray(0),
    val rawRecv: ByteArray = ByteArray(0)
)

/**
 * Управление USB-serial соединением и обмен по протоколу "Пульсар".
 *
 * ВАЖНО: на Android нет нативных COM-портов. Реальный последовательный обмен
 * возможен только через USB-OTG адаптер USB-RS232/RS485 (FTDI, CP210x, CH340, PL2303
 * или встроенный CDC-ACM), подключённый к разъёму OTG телефона/планшета.
 * Эта обёртка использует библиотеку usb-serial-for-android в качестве USB host driver,
 * что эквивалентно CreateFileA()/SetCommState()/WriteFile()/ReadFile() в pulsar.cpp.
 */
class PulsarTransport(private val context: Context) {

    private var port: UsbSerialPort? = null
    var portName: String = ""
        private set

    fun listAvailablePorts(): List<Pair<UsbDevice, UsbSerialDriver>> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        return drivers.map { it.device to it }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, pendingIntent: android.app.PendingIntent) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        manager.requestPermission(device, pendingIntent)
    }

    /** Открыть порт по выбранному устройству (аналог pulsar_init_comport). */
    @Throws(IOException::class)
    fun open(device: UsbDevice, driver: UsbSerialDriver, settings: SerialSettings) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(device) ?: throw IOException("Не удалось открыть USB-устройство (нет разрешения?)")
        val p = driver.ports[0]
        p.open(connection)
        p.setParameters(settings.baudRate, settings.dataBits, settings.stopBits, settings.parity)
        port = p
        portName = device.deviceName
    }

    fun close() {
        try {
            port?.close()
        } catch (_: Exception) {
        }
        port = null
        portName = ""
    }

    fun isOpen(): Boolean = port != null

    /**
     * Транзакция запрос/ответ — порт pulsar_transaction() из pulsar.cpp:
     * формируем пакет, отправляем, читаем байты до распознавания валидного пакета
     * или истечения тайм-аута; при неудаче повторяем до resp_num раз.
     */
    suspend fun transaction(func: Int, payload: ByteArray, settings: SerialSettings, logTitle: String = ""): TransactionResult {
        val p = port ?: run {
            TransactionLog.add(logTitle.ifEmpty { "func=0x%02X".format(func) }, ByteArray(0), ByteArray(0), "Порт не открыт", false)
            return TransactionResult(false, 0xFF, PulsarProtocol.decodeError(0xFF))
        }

        val sendBuf = PulsarProtocol.encodePacket(settings.devAddr, func, payload)
        var lastRecv = ByteArray(0)

        for (attempt in 1..settings.respNum) {
            try {
                p.write(sendBuf, 500)
            } catch (e: IOException) {
                val msg = "Ошибка записи в порт: ${e.message}"
                TransactionLog.add(logTitle.ifEmpty { "func=0x%02X".format(func) }, sendBuf, ByteArray(0), msg, false)
                return TransactionResult(false, 0xFF, msg, rawSent = sendBuf)
            }

            val recvBuffer = mutableListOf<Byte>()
            val startTime = System.currentTimeMillis()
            val readChunk = ByteArray(256)

            while (System.currentTimeMillis() - startTime <= settings.ansTimeoutMs) {
                val n = try {
                    p.read(readChunk, 50)
                } catch (e: IOException) {
                    val msg = "Ошибка чтения из порта: ${e.message}"
                    TransactionLog.add(logTitle.ifEmpty { "func=0x%02X".format(func) }, sendBuf, lastRecv, msg, false)
                    return TransactionResult(false, 0xFF, msg, rawSent = sendBuf)
                }
                if (n > 0) {
                    for (i in 0 until n) recvBuffer.add(readChunk[i])
                    lastRecv = recvBuffer.toByteArray()
                    val parsed = PulsarProtocol.tryParsePacket(recvBuffer)
                    if (parsed != null && parsed.error == 0) {
                        val title = logTitle.ifEmpty { "func=0x%02X".format(func) }
                        TransactionLog.add(title, sendBuf, lastRecv, "ОК", true)
                        return TransactionResult(true, 0, PulsarProtocol.decodeError(0), parsed.payload, sendBuf, lastRecv)
                    } else if (parsed != null && parsed.error != 1 && parsed.error != 2) {
                        // Распознанный пакет с кодом ошибки протокола Пульсар
                        val title = logTitle.ifEmpty { "func=0x%02X".format(func) }
                        val errText = PulsarProtocol.decodeError(parsed.error)
                        TransactionLog.add(title, sendBuf, lastRecv, errText, false)
                        return TransactionResult(false, parsed.error, errText, parsed.payload, sendBuf, lastRecv)
                    }
                } else {
                    delay(5)
                }
            }
            // тайм-аут — пробуем следующую попытку (resp_num)
        }

        val title = logTitle.ifEmpty { "func=0x%02X".format(func) }
        val errText = PulsarProtocol.decodeError(0xFF)
        TransactionLog.add(title, sendBuf, lastRecv, errText, false)
        return TransactionResult(false, 0xFF, errText, rawSent = sendBuf, rawRecv = lastRecv)
    }

    // ---- Высокоуровневые операции, аналогичные функциям из pulsar.h ----

    suspend fun readParam(item: ParamItem, settings: SerialSettings): TransactionResult =
        transaction(Func.READ, PulsarProtocol.buildReadParamPayload(item.addrInt), settings,
            "Чтение параметра №${item.num} (${item.varName})")

    suspend fun writeParam(item: ParamItem, value: ByteArray, settings: SerialSettings): TransactionResult =
        transaction(Func.WRITE, PulsarProtocol.buildWriteParamPayload(item.addrInt, value), settings,
            "Запись параметра №${item.num} (${item.varName})")

    suspend fun readChannel(item: ParamItem, settings: SerialSettings): TransactionResult =
        transaction(Func.CHAN_READ, PulsarProtocol.buildReadChannelsPayload(1L shl item.addrInt), settings,
            "Чтение канала №${item.num} (${item.varName})")

    suspend fun readChannelsMask(mask: Long, settings: SerialSettings): TransactionResult =
        transaction(Func.CHAN_READ, PulsarProtocol.buildReadChannelsPayload(mask), settings,
            "Чтение каналов по маске 0x%X".format(mask))

    suspend fun writeChannel(item: ParamItem, value: Float, settings: SerialSettings): TransactionResult =
        transaction(Func.CHAN_WRITE, PulsarProtocol.buildWriteChannelPayload(item.addrInt, value), settings,
            "Запись канала №${item.num} (${item.varName})")
}
