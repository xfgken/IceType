package com.xfgken.iceime.rime

import android.content.Context
import com.xfgken.iceime.core.AppLog
import com.xfgken.iceime.core.IcePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rime 引擎统一入口。
 *
 * 负责：原生库加载 → 资源释放 → librime 初始化 → 维护（部署）→ 会话与候选查询。
 * 首次启动自动完成，用户无需手动导入词库；部署失败会保留可读原因并可重试。
 */
object RimeEngine {

    /** X11 keysym —— librime 使用 X11 键码体系。 */
    object Key {
        const val BACKSPACE = 0xFF08
        const val TAB = 0xFF09
        const val SPACE = 0x0020
        const val LEFT = 0xFF51
        const val UP = 0xFF52
        const val END = 0xFF57
        const val COMMA = 0x002C
    }

    /** 修饰键掩码，与 librime `key_table.h` 中的 X11 派生常量一致。 */
    object Modifier {
        const val SHIFT = 1 shl 0
        const val LOCK = 1 shl 1
    }

    enum class State { IDLE, INSTALLING, DEPLOYING, READY, FAILED }

    data class DeployState(
        val state: State = State.IDLE,
        val message: String = "",
        val percent: Int = 0,
        val engineVersion: String = "",
    ) {
        val isReady: Boolean get() = state == State.READY
        val isBusy: Boolean get() = state == State.INSTALLING || state == State.DEPLOYING
    }

    data class Candidate(val text: String, val comment: String)

    data class RimeContext(
        val preedit: String,
        val cursor: Int,
        val pageSize: Int,
        val pageNo: Int,
        val isLastPage: Boolean,
        val highlighted: Int,
        val selectKeys: String,
        val commitPreview: String,
        val candidates: List<Candidate>,
    ) {
        val isEmpty: Boolean get() = preedit.isEmpty() && candidates.isEmpty()
    }

    data class RimeStatus(
        val schemaId: String,
        val schemaName: String,
        val composing: Boolean,
        val asciiMode: Boolean,
        val fullShape: Boolean,
        val simplified: Boolean,
        val traditional: Boolean,
        val asciiPunct: Boolean,
    )

    data class Schema(val id: String, val name: String)

    private val _deploy = MutableStateFlow(DeployState())
    val deploy: StateFlow<DeployState> = _deploy.asStateFlow()

    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    var engineVersion: String = ""
        private set

    @Volatile
    var lastDeployDetail: String = ""
        private set

    /** 供“词库与语言”页面展示：内置方案列表。 */
    @Volatile
    private var cachedSchemas: List<Schema> = emptyList()

    fun schemas(): List<Schema> = cachedSchemas

    /**
     * 引擎自检 + 资源部署 + librime 初始化。可安全重复调用。
     * 关键约束：资源版本未变化时**不**重装共享目录，也不会动用户数据。
     */
    suspend fun ensureReady(context: Context, forceFullDeploy: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (initialized && !forceFullDeploy) return@synchronized true

                if (!RimeNative.ensureLoaded()) {
                    _deploy.value = DeployState(
                        State.FAILED,
                        "原生库加载失败：${RimeNative.loadError ?: "未知原因"}",
                    )
                    return@synchronized false
                }

                engineVersion = runCatching { RimeNative.locked.nativeVersion() }.getOrDefault("(未知)")

                // 1) 资源释放（仅在版本不一致时）
                if (!RimeAssetInstaller.isInstalled(context)) {
                    _deploy.value = DeployState(State.INSTALLING, "正在释放内置词库资源…", 0, engineVersion)
                    val ok = RimeAssetInstaller.install(context) { p ->
                        _deploy.value = DeployState(
                            State.INSTALLING,
                            "释放资源：${p.currentFile}",
                            p.percent,
                            engineVersion,
                        )
                    }
                    if (!ok) {
                        _deploy.value = DeployState(State.FAILED, "内置资源释放失败，请检查应用存储空间", 0, engineVersion)
                        return@synchronized false
                    }
                }

                val shared = RimeAssetInstaller.sharedDir(context)
                val user = RimeAssetInstaller.userDir(context).apply { mkdirs() }
                val logDir = File(context.cacheDir, "rime-log").apply { mkdirs() }

                if (!RimeNative.locked.nativeSetup(
                        shared.absolutePath,
                        user.absolutePath,
                        logDir.absolutePath,
                        ENGINE_DISTRIBUTION_VERSION,
                    )
                ) {
                    _deploy.value = DeployState(State.FAILED, "Rime 初始化参数设置失败（librime 不可用）", 0, engineVersion)
                    return@synchronized false
                }
                RimeNative.locked.nativeInitialize(ENGINE_DISTRIBUTION_VERSION)
                initialized = true
                drainNotifications()
                AppLog.i(AppLog.Category.ENGINE, "librime 已初始化，版本 $engineVersion")
            }

            // 2) 部署（首次或需要时）。放在锁外，避免长任务阻塞其它调用。
            val settings = IcePrefs.current()
            val needDeploy = forceFullDeploy || !settings.lastDeployOk
            if (needDeploy) {
                deployBlocking(context, full = true)
            } else {
                // 增量检查：只校验不重建（有变化才重编译方案）
                deployBlocking(context, full = false)
            }
            refreshSchemas()
            _deploy.value = _deploy.value.copy(
                state = if (_deploy.value.state == State.FAILED) State.FAILED else State.READY,
                percent = 100,
                engineVersion = engineVersion,
            )
            _deploy.value.state != State.FAILED
        }

    /** 同步部署；在 IO 线程调用。 */
    private fun deployBlocking(context: Context, full: Boolean): Boolean {
        _deploy.value = _deploy.value.copy(
            state = State.DEPLOYING,
            message = if (full) "正在部署词库并编译方案（首次较慢）…" else "正在校验词库版本…",
            engineVersion = engineVersion,
        )
        val started = System.currentTimeMillis()
        return try {
            val ok = RimeNative.locked.nativeStartMaintenance(full)
            drainNotifications()
            // start_maintenance 返回 false 表示“无需维护/已有维护线程”，并非失败；
            // 完整部署（full=true）时才是异常情况。
            if (!ok && full) {
                AppLog.w(AppLog.Category.DEPLOY, "请求完整部署但 start_maintenance 返回 false")
            }
            RimeNative.locked.nativeJoinMaintenance()
            drainNotifications()
            val cost = System.currentTimeMillis() - started
            val message = "词库部署完成，用时 ${cost / 1000.0} 秒" +
                if (lastDeployDetail.isNotEmpty()) "（$lastDeployDetail）" else ""
            AppLog.i(AppLog.Category.DEPLOY, message)
            logBuildArtifacts(context)
            IcePrefs.setDeployResult(RimeAssetInstaller.ASSET_VERSION, true, message)
            _deploy.value = _deploy.value.copy(state = State.READY, message = message, percent = 100)
            true
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.DEPLOY, "词库部署失败", t)
            val message = "词库部署失败：${t.javaClass.simpleName}: ${t.message}"
            IcePrefs.setDeployResult(RimeAssetInstaller.ASSET_VERSION, false, message)
            _deploy.value = _deploy.value.copy(state = State.FAILED, message = message)
            false
        }
    }

    /**
     * 部署完成后列出编译产物（userDataDir/build）。
     * 用于确认英文次翻译器 melt_eng 的词库是否真的编译成功——缺 melt_eng.*.bin 时
     * 中文模式下不会有任何英文单词候选（这是「打 goo 出不来 good」的根因）。
     */
    private fun logBuildArtifacts(context: Context) {
        runCatching {
            val buildDir = File(RimeAssetInstaller.userDir(context), "build")
            val names = buildDir.listFiles()?.sortedBy { it.name }?.joinToString("，") {
                "${it.name}(${it.length() / 1024}KB)"
            }
            AppLog.i(AppLog.Category.DEPLOY, "编译产物：${names ?: "目录不存在"}")
        }.onFailure { AppLog.throwable(AppLog.Category.DEPLOY, "读取编译产物失败", it) }
    }

    /** 把 native 侧排队的引擎通知取出来写入日志与部署状态。 */
    private fun drainNotifications() {
        var guard = 0
        while (guard++ < 64) {
            val packed = runCatching { RimeNative.locked.nativePollNotification() }.getOrNull() ?: return
            val separator = packed.indexOf('\u0001')
            val type = if (separator >= 0) packed.substring(0, separator) else packed
            val value = if (separator >= 0) packed.substring(separator + 1) else ""
            lastDeployDetail = "$type: $value"
            AppLog.i(AppLog.Category.DEPLOY, "Rime 通知 [$type] $value")
            if (type == "deploy") {
                _deploy.value = _deploy.value.copy(message = "$type: $value")
            }
        }
    }

    /** 用户主动重试部署。 */
    suspend fun retryDeploy(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!initialized && !ensureReady(context)) return@withContext false
        deployBlocking(context, full = true)
    }

    /** 重置初始化：清共享资源 + 用户数据后重新部署（调用方必须先二次确认）。 */
    suspend fun resetAndRedeploy(context: Context, alsoWipeUser: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (initialized) {
                    runCatching { RimeNative.locked.nativeFinalize() }
                    initialized = false
                }
                RimeAssetInstaller.wipeShared(context)
                if (alsoWipeUser) RimeAssetInstaller.wipeUser(context)
                IcePrefs.setDeployResult(0, false, "已重置初始化")
            }
            ensureReady(context, forceFullDeploy = true)
        }

    private fun refreshSchemas() {
        cachedSchemas = runCatching {
            JsonMini.parseArray(RimeNative.locked.nativeGetSchemaList()).map {
                Schema(it.str("id"), it.str("name"))
            }
        }.getOrDefault(emptyList())
        if (cachedSchemas.isNotEmpty()) {
            AppLog.i(
                AppLog.Category.ENGINE,
                "可用方案：${cachedSchemas.joinToString("、") { it.id }}",
            )
        }
    }

    /** 引擎是否已完成初始化（可创建会话）。 */
    val isReady: Boolean get() = initialized

    // —— 会话 ——

    fun createSession(): Long {
        if (!initialized) return 0L
        val id = runCatching { RimeNative.locked.nativeCreateSession() }.getOrDefault(0L)
        if (id == 0L) AppLog.w(AppLog.Category.ENGINE, "创建 Rime 会话失败")
        return id
    }

    fun destroySession(session: Long) {
        if (session == 0L) return
        runCatching { RimeNative.locked.nativeDestroySession(session) }
    }

    fun cleanupStaleSessions() {
        if (!initialized) return
        runCatching { RimeNative.locked.nativeCleanupStaleSessions() }
    }

    /**
     * 处理一个按键。
     * @return 是否被引擎消费（未消费时前端可按原生行为处理，例如英文直接上屏）
     */
    fun processKey(session: Long, keycode: Int, modifier: Int = 0): Boolean {
        if (!initialized || session == 0L) return false
        return runCatching { RimeNative.locked.nativeProcessKey(session, keycode, modifier) }
            .getOrDefault(false)
    }

    fun selectCandidate(session: Long, index: Int): Boolean {
        if (!initialized || session == 0L) return false
        return runCatching { RimeNative.locked.nativeSelectCandidateOnPage(session, index) }
            .getOrDefault(false)
    }

    fun highlightOnPage(session: Long, index: Int): Boolean {
        if (!initialized || session == 0L) return false
        return runCatching { RimeNative.locked.nativeHighlightOnPage(session, index) }.getOrDefault(false)
    }

    fun changePage(session: Long, backward: Boolean): Boolean {
        if (!initialized || session == 0L) return false
        return runCatching { RimeNative.locked.nativeChangePage(session, backward) }.getOrDefault(false)
    }

    /** 取提交文本；无提交返回 null。 */
    fun commit(session: Long): String? {
        if (!initialized || session == 0L) return null
        return runCatching { RimeNative.locked.nativeGetCommit(session) }.getOrNull()
    }

    fun commitComposition(session: Long): Boolean {
        if (!initialized || session == 0L) return false
        return runCatching { RimeNative.locked.nativeCommitComposition(session) }.getOrDefault(false)
    }

    fun clearComposition(session: Long) {
        if (!initialized || session == 0L) return
        runCatching { RimeNative.locked.nativeClearComposition(session) }
    }

    fun context(session: Long): RimeContext? {
        if (!initialized || session == 0L) return null
        val raw = runCatching { RimeNative.locked.nativeGetContext(session) }.getOrDefault("")
        val map = JsonMini.parseObject(raw) ?: return null
        val candidates = map.list("candidates").map {
            Candidate(it.str("text"), it.str("comment"))
        }
        return RimeContext(
            preedit = map.str("preedit"),
            cursor = map.int("cursor"),
            pageSize = map.int("pageSize"),
            pageNo = map.int("pageNo"),
            isLastPage = map.bool("isLastPage"),
            highlighted = map.int("highlighted"),
            selectKeys = map.str("selectKeys"),
            commitPreview = map.str("commitPreview"),
            candidates = candidates,
        )
    }

    fun status(session: Long): RimeStatus? {
        if (!initialized || session == 0L) return null
        val raw = runCatching { RimeNative.locked.nativeGetStatus(session) }.getOrDefault("")
        val map = JsonMini.parseObject(raw) ?: return null
        return RimeStatus(
            schemaId = map.str("schemaId"),
            schemaName = map.str("schemaName"),
            composing = map.bool("composing"),
            asciiMode = map.bool("asciiMode"),
            fullShape = map.bool("fullShape"),
            simplified = map.bool("simplified"),
            traditional = map.bool("traditional"),
            asciiPunct = map.bool("asciiPunct"),
        )
    }

    fun setOption(session: Long, name: String, value: Boolean) {
        if (!initialized || session == 0L) return
        runCatching { RimeNative.locked.nativeSetOption(session, name, value) }
    }



    fun currentSchema(session: Long): String {
        if (!initialized || session == 0L) return ""
        return runCatching { RimeNative.locked.nativeGetCurrentSchema(session) }.getOrDefault("")
    }

    /** 关闭时把用户词频刷回磁盘，避免丢失学习结果。 */
    fun syncUserData() {
        if (!initialized) return
        runCatching { RimeEngine.nativeSyncUserDataCompat() }
    }

    private fun nativeSyncUserDataCompat(): Boolean =
        runCatching { RimeNative.locked.nativeSyncUserData() }.getOrDefault(false)

    /**
     * 写入用户补丁 `rime_ice.custom.yaml`。
     *
     * 只包含用户显式开启的项；未开启的项不写入，因此不会破坏用户手工维护的补丁内容之外的行为。
     * 写入后立即执行一次部署，使补丁生效。
     */
    suspend fun applyUserPatches(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = IcePrefs.current()
        val lines = mutableListOf<String>()
        // 候选词每页 6 个（与键盘候选栏一致）
        lines += "  menu/page_size: 6"
        if (settings.englishCandidatesPriority) {
            // 实测有效：把英文词典权重抬高后，hel 的首屏候选变为 held/hell/help 等英文词
            lines += "  melt_eng/initial_quality: 3.0"
            lines += "  cn_en/initial_quality: 2.6"
        }
        if (!settings.learningEnabled) {
            // 主翻译器默认开启用户词典；这里关闭它以实现“停止词频学习”
            lines += "  translator/enable_user_dict: false"
        }
        val file = File(RimeAssetInstaller.userDir(context), SCHEMA_PATCH_FILE)
        try {
            file.parentFile?.mkdirs()
            file.writeText("patch:\n" + lines.joinToString("\n") + "\n")
            AppLog.i(AppLog.Category.ENGINE, "已写入用户补丁，共 ${lines.size} 项")
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.ENGINE, "写入用户补丁失败", t)
            return@withContext false
        }
        if (!initialized && !ensureReady(context)) return@withContext false
        deployBlocking(context, full = true)
    }


    /** 学习开关的便捷入口。 */
    suspend fun setLearningEnabled(context: Context, enabled: Boolean): Boolean {
        IcePrefs.edit { it.putBoolean("learningEnabled", enabled) }
        return applyUserPatches(context)
    }



    private const val ENGINE_DISTRIBUTION_VERSION = "1.0.0"

    /** 用户补丁文件名：rime_ice 方案的补丁。 */
    private const val SCHEMA_PATCH_FILE = "rime_ice.custom.yaml"
}