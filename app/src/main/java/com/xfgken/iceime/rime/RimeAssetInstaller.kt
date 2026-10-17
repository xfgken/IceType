package com.xfgken.iceime.rime

import android.content.Context
import android.content.res.AssetManager
import com.xfgken.iceime.core.AppLog
import java.io.File

/**
 * 把 APK 内 assets/rime 目录下的 Rime 资源部署到应用私有目录。
 *
 * 目录职责划分（文档 3.1：资源升级不得覆盖用户词频/配置）：
 *  - `rime_shared`：只读共享资源（方案、词典、Lua 脚本、opencc），随资源版本整体替换；
 *  - `rime_user`  ：用户数据（user.yaml、用户词典 userdb），**永不**被资源升级删除。
 */
object RimeAssetInstaller {

    /** 资源版本号：改动 assets/rime 内容时必须递增，用于触发一次安全重装（不递增的话改动不会生效）。 */
    const val ASSET_VERSION = 7

    private const val ASSET_ROOT = "rime"
    private const val VERSION_MARKER = ".icetype_asset_version"

    data class Progress(val currentFile: String, val copied: Int, val total: Int) {
        val percent: Int get() = if (total <= 0) 0 else (copied * 100 / total)
    }

    fun sharedDir(context: Context): File = File(context.filesDir, "rime_shared")

    fun userDir(context: Context): File = File(context.filesDir, "rime_user")

    fun isInstalled(context: Context): Boolean {
        val marker = File(sharedDir(context), VERSION_MARKER)
        return marker.isFile &&
            (marker.readText().trim().toIntOrNull() ?: -1) == ASSET_VERSION
    }

    /** 统计资源文件数量，用于进度显示。 */
    private fun countAssets(am: AssetManager, path: String): Int {
        val children = am.list(path) ?: return 0
        if (children.isEmpty()) return 1
        var n = 0
        for (c in children) n += countAssets(am, "$path/$c")
        return n
    }

    private fun copyRecursive(
        am: AssetManager,
        assetPath: String,
        target: File,
        progress: ((Progress) -> Unit)?,
        counter: IntArray,
        total: Int,
    ) {
        val children = am.list(assetPath) ?: return
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            counter[0]++
            progress?.invoke(Progress(assetPath.removePrefix("$ASSET_ROOT/"), counter[0], total))
            return
        }
        target.mkdirs()
        for (c in children) {
            copyRecursive(am, "$assetPath/$c", File(target, c), progress, counter, total)
        }
    }

    /**
     * 安装/更新共享资源。**不会**触碰 userDir。
     * @return 成功与否；失败原因写入日志。
     */
    fun install(context: Context, onProgress: ((Progress) -> Unit)? = null): Boolean {
        val shared = sharedDir(context)
        return try {
            AppLog.i(AppLog.Category.DEPLOY, "开始释放内置 Rime 资源（资源版本 $ASSET_VERSION）")
            // 整体替换共享目录，保证被移除的旧方案不会残留
            if (shared.exists()) shared.deleteRecursively()
            shared.mkdirs()

            val am = context.assets
            val total = countAssets(am, ASSET_ROOT)
            if (total <= 0) {
                AppLog.w(AppLog.Category.DEPLOY, "assets/$ASSET_ROOT 为空，疑似打包遗漏")
                return false
            }
            copyRecursive(am, ASSET_ROOT, shared, onProgress, IntArray(1), total)
            File(shared, VERSION_MARKER).writeText(ASSET_VERSION.toString())
            seedUserColdWordTables(context)
            AppLog.i(AppLog.Category.DEPLOY, "内置资源释放完成，共 $total 个文件")
            true
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.DEPLOY, "释放内置 Rime 资源失败", t)
            false
        }
    }

    /**
     * 冷词词表（drop / hide / reduce_freq）必须**可写**，而 rime_shared 每次资源升级都会整体替换。
     * 因此把三张空词表复制到 rime_user/lua/cold_word_drop/（用户目录优先加载）：
     * 之后用户的长按操作写入这里，升级 APK 不会覆盖，也不会丢。
     */
    private fun seedUserColdWordTables(context: Context) {
        val src = File(File(sharedDir(context), "lua"), "cold_word_drop")
        val dst = File(File(userDir(context), "lua"), "cold_word_drop")
        if (!src.isDirectory) return
        dst.mkdirs()
        for (name in listOf("drop_words.lua", "hide_words.lua", "reduce_freq_words.lua")) {
            val s = File(src, name)
            val d = File(dst, name)
            if (!s.isFile || d.exists()) continue // 已存在则尊重用户数据，绝不覆盖
            runCatching { d.writeText(s.readText()) }
        }
        AppLog.i(AppLog.Category.DEPLOY, "冷词词表已就位：${dst.absolutePath}")
    }

    /** 删除共享资源（重置初始化的一部分）。 */
    fun wipeShared(context: Context) {
        runCatching { sharedDir(context).deleteRecursively() }
        AppLog.w(AppLog.Category.DEPLOY, "已清除共享资源目录")
    }

    /** 删除用户数据：会丢失用户词频与自定义配置，调用方必须先二次确认。 */
    fun wipeUser(context: Context) {
        runCatching { userDir(context).deleteRecursively() }
        AppLog.w(AppLog.Category.DEPLOY, "已清除用户数据目录（用户词频已重置）")
    }

    /** 统计共享资源目录体积，供“词库与语言”页面显示。 */
    fun sizeOfShared(context: Context): Long {
        val dir = sharedDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}