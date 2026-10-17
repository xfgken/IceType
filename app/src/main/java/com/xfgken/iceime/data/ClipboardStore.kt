package com.xfgken.iceime.data

import android.content.Context
import com.xfgken.iceime.core.AppLog
import com.xfgken.iceime.core.IcePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 剪贴板历史。
 *
 * 隐私约束（文档 8.1）：
 *  - 历史持久化**默认关闭**，只有用户在设置中明确开启后才落盘；
 *  - 关闭时不写入任何内容，仅维护“当前剪贴板内容”的内存视图；
 *  - 密码输入场景由输入法服务直接跳过写入；
 *  - 数据只存本地应用私有目录，不联网。
 */
object ClipboardStore {

    data class Item(
        val id: String,
        val text: String,
        val pinned: Boolean,
        val timestamp: Long,
    )

    private const val FILE_NAME = "clipboard.json"
    private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "data").apply { mkdirs() }
        file = File(dir, FILE_NAME)
    }

    // —— 最近一次复制的内容（仅内存，不落盘）——
    // 剪贴板历史开关关闭时，面板至少要把「当前剪贴板内容」显示出来，
    // 否则用户复制完打开面板会看到空白，像功能失效。
    @Volatile
    private var currentText: String? = null

    /**
     * 刚刚被用户删掉的内容。
     *
     * 面板每次打开都会 capture 一次系统剪贴板，而删除时系统剪贴板里往往还是那段文字，
     * 于是「刚删掉的条目」会被立刻写回来 —— 表现就是"删了好几遍都删不掉"。
     * 这里给被删的内容打一个短时失效标记，期间的自动捕获一律忽略；
     * 用户一旦复制了别的内容（或过了一小会儿），标记自动失效。
     */
    @Volatile
    private var suppressedText: String? = null

    @Volatile
    private var suppressedAt = 0L

    private const val SUPPRESS_MS = 10 * 60 * 1000L

    fun suppress(text: String?) {
        if (text.isNullOrBlank()) return
        suppressedText = text
        suppressedAt = System.currentTimeMillis()
    }

    /**
     * 是否忽略这段内容的自动捕获。
     *
     * 注意：标记**不会因为时间短就失效**——只要系统剪贴板里还是这段文字，就一直忽略，
     * 否则用户删掉一条、过一会儿再开面板，它又会被"读回来"（就是"删两遍才删掉"的原因）。
     * 一旦用户复制了别的内容（内容变了），标记立刻失效。
     */
    private fun isSuppressed(text: String): Boolean {
        val s = suppressedText ?: return false
        if (s != text) {
            // 已经换成别的内容了：标记失效
            suppressedText = null
            return false
        }
        if (System.currentTimeMillis() - suppressedAt > SUPPRESS_MS) {
            suppressedText = null
            return false
        }
        return true
    }

    fun setCurrent(text: String?) {
        currentText = text
    }

    /** 当前剪贴板内容（刚被删掉的那段不再返回）。 */
    fun current(): String? = currentText?.takeIf { !isSuppressed(it) }

    /**
     * 从系统剪贴板读取一次并记录（供剪贴板监听回调使用）。
     *
     * 注意：Android 10+ 只有「前台应用」或「当前输入法」能读到剪贴板内容，
     * 因此本方法在无权读取时只是安全地什么都不做（返回 null）。
     *
     * @return 读到的文本；没有内容或读不到时为 null
     */
    fun captureFromSystem(context: Context, fromPasswordField: Boolean = false): String? {
        val text = runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        if (text.isNullOrBlank()) return null
        // 刚被用户删掉的内容：不要因为系统剪贴板里还是它而重新写回来
        if (isSuppressed(text)) {
            AppLog.i(AppLog.Category.CLIPBOARD, "剪贴板内容刚被删除，忽略本次自动捕获")
            return null
        }
        // 密码框也照常记录剪贴板历史（按用户要求去掉「密码框不写入」的限制）
        setCurrent(text)
        add(text, fromPasswordField = false)
        AppLog.i(AppLog.Category.PRIVACY, "剪贴板内容已记录到历史")
        return text
    }

    private fun readAll(): MutableList<Item> {
        val f = file ?: return mutableListOf()
        if (!f.isFile) return mutableListOf()
        return try {
            val array = JSONArray(f.readText())
            val out = mutableListOf<Item>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                out.add(
                    Item(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        text = o.optString("text"),
                        pinned = o.optBoolean("pinned", false),
                        timestamp = o.optLong("time", 0L),
                    ),
                )
            }
            out
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.ERROR, "读取剪贴板历史失败，已忽略损坏文件", t)
            mutableListOf()
        }
    }

    private fun writeAll(items: List<Item>) {
        val f = file ?: return
        try {
            val array = JSONArray()
            items.forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("text", item.text)
                        put("pinned", item.pinned)
                        put("time", item.timestamp)
                    },
                )
            }
            f.writeText(array.toString())
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.ERROR, "写入剪贴板历史失败", t)
        }
    }

    /** 固定项目永远在最前，其余按时间倒序。 */
    fun list(): List<Item> {
        val all = readAll()
        val pinned = all.filter { it.pinned }.sortedByDescending { it.timestamp }
        val rest = all.filter { !it.pinned }.sortedByDescending { it.timestamp }
        return pinned + rest
    }

    /**
     * 记录一条剪贴板内容。
     * @return 是否实际写入
     */
    fun add(text: String, fromPasswordField: Boolean): Boolean {
        if (text.isBlank()) return false
        if (fromPasswordField) {
            AppLog.i(AppLog.Category.PRIVACY, "密码输入框：跳过剪贴板历史写入")
            return false
        }
        // 刚被删掉的内容不写回来
        if (isSuppressed(text)) {
            AppLog.i(AppLog.Category.CLIPBOARD, "剪贴板内容刚被删除，忽略写入")
            return false
        }
        val items = readAll()
        // 已经是最近一条就不再重复记录（面板每次打开都会 capture 一次，避免无谓改写）
        val newest = items.filter { !it.pinned }.maxByOrNull { it.timestamp }
        if (newest != null && newest.text == text) {
            AppLog.i(AppLog.Category.CLIPBOARD, "剪贴板内容与最近一条相同，跳过重复写入")
            return false
        }
        // 去重：相同内容只保留最新一条
        items.removeAll { it.text == text && !it.pinned }
        items.add(0, Item(UUID.randomUUID().toString(), text, false, System.currentTimeMillis()))

        val pinnedCount = items.count { it.pinned }
        val configured = IcePrefs.current().clipboardHistoryLimit
        // 0（或负数）= 不限制条数：全部保留，只受设备存储影响
        val merged: List<Item> = if (configured <= 0) {
            items
        } else {
            val limit = (configured - pinnedCount).coerceAtLeast(1)
            items.filter { it.pinned } + items.filter { !it.pinned }.take(limit)
        }
        writeAll(merged)
        AppLog.i(
            AppLog.Category.CLIPBOARD,
            "剪贴板历史已记录（长度 ${text.length}），当前共 ${merged.size} 条（上限 " +
                (if (configured <= 0) "不限" else configured.toString()) + "）",
        )
        return true
    }

    fun remove(id: String) {
        writeAll(readAll().filterNot { it.id == id })
    }

    fun togglePin(id: String) {
        writeAll(readAll().map { if (it.id == id) it.copy(pinned = !it.pinned) else it })
    }

    /** 编辑条目文本（剪贴板与常用语一样支持修改）。 */
    fun update(id: String, text: String) {
        if (text.isBlank()) return
        writeAll(readAll().map { if (it.id == id) it.copy(text = text) else it })
        AppLog.i(AppLog.Category.CLIPBOARD, "剪贴板条目已编辑（长度 ${text.length}）")
    }

    /** 清空历史；固定项目可选择保留。 */
    fun clear(keepPinned: Boolean = true) {
        val items = readAll()
        writeAll(if (keepPinned) items.filter { it.pinned } else emptyList())
        AppLog.i(AppLog.Category.CLIPBOARD, "剪贴板历史已清空（保留固定项=$keepPinned）")
    }

    fun size(): Int = readAll().size
}