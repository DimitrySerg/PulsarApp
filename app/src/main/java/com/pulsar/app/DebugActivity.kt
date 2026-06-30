package com.pulsar.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Страница «Отладка» — показывает отправленные и полученные байты обмена в hex-формате. */
class DebugActivity : AppCompatActivity() {

    private lateinit var adapter: LogAdapter
    private lateinit var rvLog: RecyclerView
    private lateinit var tvEmpty: View
    private val handler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 700L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, refreshIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        rvLog = findViewById(R.id.rvLog)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = LogAdapter()
        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = adapter

        findViewById<android.widget.Button>(R.id.btnClearLog).setOnClickListener {
            TransactionLog.clear()
            refresh()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refresh() {
        val all = TransactionLog.all()
        adapter.submit(all)
        tvEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        rvLog.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
    }
}
