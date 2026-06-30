package com.pulsar.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ParamAdapter(
    private val items: MutableList<ParamItem>,
    private val onRead: (ParamItem) -> Unit,
    private val onWrite: (ParamItem, String) -> Unit
) : RecyclerView.Adapter<ParamAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val etValue: EditText = view.findViewById(R.id.etValue)
        val btnRead: ImageButton = view.findViewById(R.id.btnRead)
        val btnWrite: ImageButton = view.findViewById(R.id.btnWrite)
        var watcher: android.text.TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_param, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val kindLabel = if (item.kind == ItemKind.PARAMETER) "Парам." else "Канал"
        holder.tvName.text = "№${item.num} [$kindLabel] ${item.name}"
        holder.tvMeta.text = "${item.varName}  адрес=${item.addr}  тип=${item.dtype}  чтение:${item.readAccess}  запись:${item.writeAccess}"

        if (item.lastValue.isNotEmpty() || item.lastStatus.isNotEmpty()) {
            holder.tvStatus.visibility = View.VISIBLE
            holder.tvStatus.text = if (item.lastStatus.isEmpty()) "= ${item.lastValue}" else "= ${item.lastValue}  (${item.lastStatus})"
            holder.tvStatus.setTextColor(if (item.lastStatus.isEmpty() || item.lastStatus == "ОК") 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        } else {
            holder.tvStatus.visibility = View.GONE
        }

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = item.selected
        holder.cbSelect.setOnCheckedChangeListener { _, checked -> item.selected = checked }

        holder.etValue.setText(if (item.inputValue.isNotEmpty()) item.inputValue else item.lastValue)
        holder.etValue.tag = item
        holder.etValue.removeTextChangedListener(holder.watcher)
        holder.watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                (holder.etValue.tag as? ParamItem)?.inputValue = s?.toString() ?: ""
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        holder.etValue.addTextChangedListener(holder.watcher)

        holder.btnRead.setOnClickListener { onRead(item) }
        holder.btnWrite.setOnClickListener { onWrite(item, holder.etValue.text.toString()) }
    }

    fun updateItem(item: ParamItem) {
        val idx = items.indexOfFirst { it.kind == item.kind && it.num == item.num }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun setFiltered(newItems: List<ParamItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun selectAll(select: Boolean) {
        items.forEach { it.selected = select }
        notifyDataSetChanged()
    }

    fun selectedItems(): List<ParamItem> = items.filter { it.selected }
}
