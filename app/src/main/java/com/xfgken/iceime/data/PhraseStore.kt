package com.xfgken.iceime.data

import android.content.Context
import com.xfgken.iceime.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 常用语（自定义短语）本地存储。
 * 数据只保存在应用私有目录，不上传云端；支持增删改与排序。
 */
object PhraseStore {

    data class Phrase(val id: String, val text: String, val pinned: Boolean)

    private const val FILE_NAME = "phrases.json"
    private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "data").apply { mkdirs() }
        file = File(dir, FILE_NAME)
    }

    fun list(): MutableList<Phrase> {
        val f = file ?: return mutableListOf()
        if (!f.isFile) return mutableListOf()
        return try {
            val array = JSONArray(f.readText())
            val out = mutableListOf<Phrase>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                out.add(
                    Phrase(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        text = o.optString("text"),
                        pinned = o.optBoolean("pinned", false),
                    ),
                )
            }
            out
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.ERROR, "读取常用语失败，已忽略损坏文件", t)
            mutableListOf()
        }
    }

    private fun write(items: List<Phrase>) {
        val f = file ?: return
        try {
            val array = JSONArray()
            items.forEach { p ->
                array.put(
                    JSONObject().apply {
                        put("id", p.id)
                        put("text", p.text)
                        put("pinned", p.pinned)
                    },
                )
            }
            f.writeText(array.toString())
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.ERROR, "写入常用语失败", t)
        }
    }

    fun add(text: String): Boolean {
        if (text.isBlank()) return false
        val items = list()
        items.add(0, Phrase(UUID.randomUUID().toString(), text, false))
        write(items)
        AppLog.i(AppLog.Category.UI, "新增常用语（长度 ${text.length}）")
        return true
    }

    fun update(id: String, text: String) {
        if (text.isBlank()) return
        write(list().map { if (it.id == id) it.copy(text = text) else it })
    }

    fun remove(id: String) {
        write(list().filterNot { it.id == id })
    }

    fun togglePin(id: String) {
        write(list().map { if (it.id == id) it.copy(pinned = !it.pinned) else it })
    }

    /** 固定项优先展示。 */
    fun ordered(): List<Phrase> {
        val items = list()
        return items.filter { it.pinned } + items.filterNot { it.pinned }
    }
}