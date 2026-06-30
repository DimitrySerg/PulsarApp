package com.pulsar.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogAdapter(private val items: MutableList<LogEntry> = mutableListOf()) :
    RecyclerView.Adapter<LogAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val tvSent: TextView = view.findViewById(R.id.tvSent)
        val tvRecv: TextView = view.findViewById(R.id.tvRecv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        // показываем новые записи сверху
        val item = items[items.size - 1 - position]
        holder.tvTime.text = item.time
        holder.tvTitle.text = item.title
        holder.tvStatus.text = item.status
        holder.tvStatus.setTextColor(if (item.ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        holder.tvSent.text = "TX: ${item.sentHex}"
        holder.tvRecv.text = "RX: ${item.recvHex}"
    }

    fun submit(newItems: List<LogEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
