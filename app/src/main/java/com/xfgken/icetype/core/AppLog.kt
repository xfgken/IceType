package com.xfgken.icetype.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 应用诊断日志。
 *
 * 隐私约束（对应开发文档第 13 节）：
 * - 只记录初始化、词库部署、Rime 引擎错误、崩溃摘要与界面操作类别；
 * - **不记录**用户输入内容、密码、剪贴板正文、完整候选上下文；
 * - 落盘前对应用私有路径做脱敏处理。
 */
object AppLog {

    /** 日志类别。文档要求日志默认输出全部信息，并单独提供“用户界面/用户操作”类别。 */
    enum class Category(val label: String) {
        INIT("初始化"),
        DEPLOY("词库部署"),
        ENGINE("Rime 引擎"),
        UI("用户界面"),
        INPUT("输入行为"),
        CLIPBOARD("剪贴板"),
        PRIVACY("隐私"),
        ERROR("错误"),
    }

    private const val TAG = "IceType"
    private const val MAX_MEMORY_ENTRIES = 1500
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 // 单文件上限 2MB

    private val buffer = ArrayDeque<Entry>()
    /**
     * SimpleDateFormat 不是线程安全的。
     *
     * 日志页在主线程渲染（会调 Entry.format()），而 append() 在后台线程落盘时也调它，
     * 共用同一个实例会出现时间戳错乱甚至抛异常 —— 所以每个线程各持一份。
     */
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var sessionStart = System.currentTimeMillis()

    data class Entry(
        val timestamp: Long,
        val category: Category,
        val message: String,
        val level: Int,
    ) {
        fun format(): String =
            "[${timeFormat.get()!!.format(Date(timestamp))}] ${category.label} ${message}"
    }

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "icetype.log")
        sessionStart = System.currentTimeMillis()
        append(Entry(System.currentTimeMillis(), Category.INIT, "应用启动，日志系统就绪", Log.INFO))
        append(
            Entry(
                System.currentTimeMillis(),
                Category.PRIVACY,
                "日志策略：本地存储，不含输入内容/密码/剪贴板正文，导出前需用户确认",
                Log.INFO,
            ),
        )
    }

    fun d(category: Category, message: String) = append(Entry(now(), category, message, Log.DEBUG))

    fun i(category: Category, message: String) = append(Entry(now(), category, message, Log.INFO))

    fun w(category: Category, message: String) = append(Entry(now(), category, message, Log.WARN))

    /** 记录异常摘要：类名 + 消息 + cause 链 + 前若干行栈。 */
    fun throwable(category: Category, message: String, t: Throwable) {
        val sb = StringBuilder(message)
        sb.append(" | ").append(t.javaClass.name).append(": ").append(t.message ?: "(无消息)")
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sb.append(" <- 由 ").append(cause.javaClass.name).append(": ").append(cause.message ?: "(无消息)")
            cause = cause.cause
            depth++
        }
        t.stackTrace.take(12).forEach { sb.append("\n    at ").append(it.toString()) }
        append(Entry(now(), category, sb.toString(), Log.ERROR))
    }

    private fun now() = System.currentTimeMillis()

    @Synchronized
    private fun append(entry: Entry) {
        buffer.addLast(entry)
        while (buffer.size > MAX_MEMORY_ENTRIES) buffer.removeFirst()

        val levelTag = when (entry.level) {
            Log.ERROR -> Log.ERROR
            Log.WARN -> Log.WARN
            else -> Log.INFO
        }
        runCatching { Log.println(levelTag, TAG, entry.format()) }

        val file = logFile ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) {
                val rotated = File(file.parentFile, "icetype.log.1")
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(entry.format() + "\n")
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = buffer.toList()

    /** 导出文本（用于用户主动导出前的预览）。 */
    @Synchronized
    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("IceType 诊断日志\n")
        sb.append("会话开始: ").append(timeFormat.get()!!.format(Date(sessionStart))).append('\n')
        sb.append("条目数: ").append(buffer.size).append("\n\n")
        buffer.forEach { sb.append(it.format()).append('\n') }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        runCatching {
            logFile?.delete()
            File(logFile?.parentFile, "icetype.log.1").delete()
        }
        i(Category.UI, "日志已清除")
    }

    /**
     * 脱敏：把应用私有目录替换为占位符，避免在日志/导出中泄露完整路径与用户名。
     * 文档要求日志中隐藏敏感路径、令牌和个人信息。
     */
    fun sanitize(text: String, filesDir: String?, cacheDir: String?): String {
        var out = text
        filesDir?.let { out = out.replace(it, "«私有目录»") }
        cacheDir?.let { out = out.replace(it, "«缓存目录»") }
        out = out.replace(Regex("/data/user/0/[^/\\s]+"), "«私有目录»")
        out = out.replace(Regex("(?i)(ghp_|token=)[A-Za-z0-9_\\-]{8,}"), "$1«已隐藏»")
        return out
    }
}