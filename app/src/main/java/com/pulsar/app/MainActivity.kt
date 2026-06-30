package com.pulsar.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.pulsar.app.USB_PERMISSION"

    private lateinit var transport: PulsarTransport
    private lateinit var adapter: ParamAdapter
    private var allItems: MutableList<ParamItem> = mutableListOf()

    private var availablePorts: List<Pair<UsbDevice, UsbSerialDriver>> = emptyList()
    private var pendingConnectIndex: Int = -1

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    // ---- views ----
    private lateinit var spPort: android.widget.Spinner
    private lateinit var spBaud: android.widget.Spinner
    private lateinit var spParity: android.widget.Spinner
    private lateinit var etDevAddr: android.widget.EditText
    private lateinit var etRespNum: android.widget.EditText
    private lateinit var etTimeout: android.widget.EditText
    private lateinit var tvConnStatus: android.widget.TextView
    private lateinit var etFilter: android.widget.EditText
    private lateinit var rvParams: RecyclerView
    private lateinit var progressBar: android.widget.ProgressBar

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && pendingConnectIndex in availablePorts.indices) {
                        doConnect(pendingConnectIndex)
                    } else {
                        toast("Доступ к USB-устройству не предоставлен")
                    }
                    pendingConnectIndex = -1
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transport = PulsarTransport(this)

        spPort = findViewById(R.id.spPort)
        spBaud = findViewById(R.id.spBaud)
        spParity = findViewById(R.id.spParity)
        etDevAddr = findViewById(R.id.etDevAddr)
        etRespNum = findViewById(R.id.etRespNum)
        etTimeout = findViewById(R.id.etTimeout)
        tvConnStatus = findViewById(R.id.tvConnStatus)
        etFilter = findViewById(R.id.etFilter)
        rvParams = findViewById(R.id.rvParams)
        progressBar = findViewById(R.id.progressBar)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupBaudSpinner()
        setupParitySpinner()
        refreshPortList()

        allItems = ParamRepository.load(this).toMutableList()
        adapter = ParamAdapter(allItems.toMutableList(), ::onReadItem, ::onWriteItem)
        rvParams.layoutManager = LinearLayoutManager(this)
        rvParams.adapter = adapter

        findViewById<android.widget.Button>(R.id.btnRefreshPorts).setOnClickListener { refreshPortList() }
        findViewById<android.widget.Button>(R.id.btnConnect).setOnClickListener { onConnectClicked() }
        findViewById<android.widget.Button>(R.id.btnDebug).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btnSelectAll).setOnClickListener {
            val anySelected = adapter.selectedItems().isNotEmpty()
            adapter.selectAll(!anySelected)
        }
        findViewById<android.widget.Button>(R.id.btnReadSelected).setOnClickListener { readMany(adapter.selectedItems()) }
        findViewById<android.widget.Button>(R.id.btnWriteSelected).setOnClickListener { writeMany(adapter.selectedItems()) }
        findViewById<android.widget.Button>(R.id.btnReadAll).setOnClickListener { readMany(allItems) }

        etFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                val filtered = if (q.isEmpty()) allItems else allItems.filter {
                    it.name.lowercase().contains(q) || it.varName.lowercase().contains(q) || it.addr.lowercase().contains(q)
                }
                adapter.setFiltered(filtered)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        transport.close()
    }

    private fun setupBaudSpinner() {
        val rates = listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
        spBaud.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, rates)
        spBaud.setSelection(rates.indexOf(9600))
    }

    private fun setupParitySpinner() {
        val names = listOf("Нет", "Нечётный", "Чётный")
        spParity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun parityValue(): Int = when (spParity.selectedItemPosition) {
        1 -> UsbSerialPort.PARITY_ODD
        2 -> UsbSerialPort.PARITY_EVEN
        else -> UsbSerialPort.PARITY_NONE
    }

    private fun refreshPortList() {
        availablePorts = transport.listAvailablePorts()
        val names = if (availablePorts.isEmpty())
            listOf("USB-COM адаптер не найден (подключите через USB-OTG)")
        else
            availablePorts.map { (device, driver) -> "${device.deviceName}  [${driver.javaClass.simpleName}]" }
        spPort.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun currentSettings(): SerialSettings {
        return SerialSettings(
            baudRate = (spBaud.selectedItem as? Int) ?: 9600,
            parity = parityValue(),
            devAddr = etDevAddr.text.toString().toIntOrNull() ?: 1,
            respNum = etRespNum.text.toString().toIntOrNull() ?: 3,
            ansTimeoutMs = etTimeout.text.toString().toIntOrNull() ?: 500
        )
    }

    private fun onConnectClicked() {
        if (transport.isOpen()) {
            transport.close()
            tvConnStatus.text = "Порт не подключен"
            return
        }
        val index = spPort.selectedItemPosition
        if (index !in availablePorts.indices) {
            toast("Нет доступных USB-COM устройств")
            return
        }
        val (device, _) = availablePorts[index]
        if (transport.hasPermission(device)) {
            doConnect(index)
        } else {
            pendingConnectIndex = index
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            )
            transport.requestPermission(device, pi)
        }
    }

    private fun doConnect(index: Int) {
        val (device, driver) = availablePorts[index]
        try {
            transport.open(device, driver, currentSettings())
            tvConnStatus.text = "Подключено: ${device.deviceName}, ${currentSettings().baudRate} бод"
        } catch (e: Exception) {
            tvConnStatus.text = "Ошибка подключения: ${e.message}"
            toast("Ошибка подключения: ${e.message}")
        }
    }

    // ---- операции чтения/записи ----

    private fun onReadItem(item: ParamItem) {
        uiScope.launch { performRead(item) }
    }

    private fun onWriteItem(item: ParamItem, text: String) {
        item.inputValue = text
        uiScope.launch { performWrite(item, text) }
    }

    private suspend fun performRead(item: ParamItem) {
        if (!transport.isOpen()) {
            toast("Порт не открыт")
            return
        }
        progressBar.visibility = View.VISIBLE
        val settings = currentSettings()
        val result = withContext(Dispatchers.IO) {
            if (item.kind == ItemKind.PARAMETER) transport.readParam(item, settings)
            else transport.readChannel(item, settings)
        }
        progressBar.visibility = View.GONE

        if (result.ok) {
            item.lastValue = if (item.kind == ItemKind.CHANNEL) ValueCodec.decodeChannelValue(result.payload) else ValueCodec.decode(item, result.payload)
            item.lastStatus = "ОК"
        } else {
            item.lastValue = ""
            item.lastStatus = result.errorText
        }
        adapter.updateItem(item)
    }

    private suspend fun performWrite(item: ParamItem, text: String) {
        if (!transport.isOpen()) {
            toast("Порт не открыт")
            return
        }
        if (item.writeAccess.contains("Запрещено", ignoreCase = true)) {
            toast("Запись параметра «${item.name}» запрещена")
            return
        }
        progressBar.visibility = View.VISIBLE
        val settings = currentSettings()
        val result = withContext(Dispatchers.IO) {
            if (item.kind == ItemKind.PARAMETER) {
                val bytes = ValueCodec.encode(item, text)
                transport.writeParam(item, bytes, settings)
            } else {
                transport.writeChannel(item, text.toFloatOrNull() ?: 0f, settings)
            }
        }
        progressBar.visibility = View.GONE

        item.lastStatus = if (result.ok) "Записано" else result.errorText
        if (result.ok) item.lastValue = text
        adapter.updateItem(item)
    }

    private fun readMany(items: List<ParamItem>) {
        if (items.isEmpty()) {
            toast("Нет выбранных элементов")
            return
        }
        if (!transport.isOpen()) {
            toast("Порт не открыт")
            return
        }
        uiScope.launch {
            progressBar.visibility = View.VISIBLE
            for (item in items) {
                performRead(item)
            }
            progressBar.visibility = View.GONE
            toast("Чтение завершено (${items.size} шт.)")
        }
    }

    private fun writeMany(items: List<ParamItem>) {
        if (items.isEmpty()) {
            toast("Нет выбранных элементов")
            return
        }
        if (!transport.isOpen()) {
            toast("Порт не открыт")
            return
        }
        uiScope.launch {
            progressBar.visibility = View.VISIBLE
            for (item in items) {
                if (item.inputValue.isNotEmpty()) {
                    performWrite(item, item.inputValue)
                }
            }
            progressBar.visibility = View.GONE
            toast("Запись завершена (${items.size} шт.)")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
