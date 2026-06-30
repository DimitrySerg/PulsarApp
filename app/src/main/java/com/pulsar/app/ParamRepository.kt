package com.pulsar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Загружает список параметров и каналов из assets/pulsar_data.json (сформирован из pulsar_parameters_channels.xlsx). */
object ParamRepository {

    fun load(context: Context): List<ParamItem> {
        val text = context.assets.open("pulsar_data.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val items = mutableListOf<ParamItem>()

        items += parseArray(root.optJSONArray("parameters"), ItemKind.PARAMETER)
        items += parseArray(root.optJSONArray("channels"), ItemKind.CHANNEL)

        return items
    }

    private fun parseArray(arr: JSONArray?, kind: ItemKind): List<ParamItem> {
        val out = mutableListOf<ParamItem>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += ParamItem(
                kind = kind,
                num = o.optInt("num", i + 1),
                name = o.optString("name"),
                varName = o.optString("varname"),
                addr = o.optString("addr"),
                dtype = o.optString("dtype"),
                min = o.opt("min")?.toString() ?: "",
                max = o.opt("max")?.toString() ?: "",
                note = o.optString("note"),
                readAccess = o.optString("read_access"),
                writeAccess = o.optString("write_access")
            )
        }
        return out
    }
}
