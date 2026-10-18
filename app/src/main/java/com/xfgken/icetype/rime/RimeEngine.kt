package com.xfgken.icetype.rime

import android.content.Context
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.core.IcePrefs
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
        /** Ctrl（kControlMask = 1 << 2）：冷词「降频 / 隐藏」使用。 */
        const val CONTROL = 1 shl 2
        const val ALT = 1 shl 3
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

    /** 初始化 + 一次部署是否都已走完（只置 true，不在运行期回退）。 */
    @Volatile
    private var deployFinished = false

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
                // 注意这里还要求 deployFinished：只看 initialized 的话，部署还在跑时
                // 第二个调用方会「秒返回 true」并立刻建会话，用半成品索引取候选。
                if (initialized && deployFinished && !forceFullDeploy) return@synchronized true

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
                // 部署前先落盘用户补丁（模糊音 / 学习开关等），保证首次启动即生效
                writeUserPatch(context)

                // 2) 部署（首次或需要时）。和初始化在同一把锁里：部署期间其它调用方必须等待，
                // 而不是拿到「已初始化」就往下走（那正是首次安装时打出乱字的根因）。
                val settings = IcePrefs.current()
                val needDeploy = forceFullDeploy || !settings.lastDeployOk
                if (needDeploy) {
                    deployBlocking(context, full = true)
                } else {
                    // 增量检查：只校验不重建（有变化才重编译方案）
                    deployBlocking(context, full = false)
                }
                deployFinished = true
                refreshSchemas()
                _deploy.value = _deploy.value.copy(
                    state = if (_deploy.value.state == State.FAILED) State.FAILED else State.READY,
                    percent = 100,
                    engineVersion = engineVersion,
                )
                _deploy.value.state != State.FAILED
            }
        }

    /**
     * 是否正在部署 / 重编译。
     *
     * 维护期间词典与方案正在被重写，此时用同一个会话继续取候选会「对不上字」
     * （用户看到的表现是「打出来的字完全不对」）。输入法服务在 true 时要暂停送键，
     * 部署完成后再重建会话。
     */
    @Volatile
    var isDeploying: Boolean = false
        private set

    /** 本次部署是不是完整部署（首次安装 / 重置 / 资源升级）。 */
    @Volatile
    var deployFull: Boolean = false
        private set

    private val _deployDonePending = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * 部署已结束、等用户点「确定」。
     *
     * 用 StateFlow 而不是让界面自己 remember：部署跑在 Application 的协程里，
     * Activity 重建后 remember 会丢，完成面板就会「闪一下就没」。
     */
    val deployDonePending: kotlinx.coroutines.flow.StateFlow<Boolean> = _deployDonePending

    private fun markDeployDone(pending: Boolean) {
        // 只存进程内状态：重启应用后不会再弹上一次的完成面板
        _deployDonePending.value = pending
    }

    /** 用户点了「确定」。 */
    fun clearDeployDonePending() {
        runCatching {
            val frames = Throwable().stackTrace.take(6).joinToString(" <- ") { it.toString() }
            AppLog.w(AppLog.Category.UI, "清除完成面板标记，调用栈：$frames")
        }
        markDeployDone(false)
    }

    /** 本次部署的开始时间（毫秒）。 */
    @Volatile
    var deployStartedAt: Long = 0L
        private set

    /**
     * 是否需要「暂停送键」。
     *
     * 不是所有部署都要拦：没有变化时的增量校验只要几毫秒，拦下来反而会误弹
     * 「正在重编译词库」并把用户正当的输入会话拆掉。所以只有「完整部署」、
     * 或「已经跑了 1 秒以上」才真正阻断输入。
     */
    val blockingInput: Boolean
        get() = isDeploying &&
            (deployFull || System.currentTimeMillis() - deployStartedAt >= 1000L)

    private fun deployBlocking(context: Context, full: Boolean): Boolean {
        _deploy.value = _deploy.value.copy(
            state = State.DEPLOYING,
            message = if (full) "正在部署词库并编译方案（首次较慢）…" else "正在校验词库版本…",
            // 编译阶段 librime 不提供百分比：置 -1 表示「未知」，
            // 界面据此显示滑动的不确定进度条（以前会沿用资源释放阶段的 100%，看着像已经完成）。
            percent = -1,
            engineVersion = engineVersion,
        )
        val started = System.currentTimeMillis()
        deployFull = full
        deployStartedAt = started
        isDeploying = true
        return try {
            val ok = RimeNative.locked.nativeStartMaintenance(full)
            drainNotifications()
            // start_maintenance 返回 false 表示“无需维护/已有维护线程”，并非失败；
            // 完整部署（full=true）时才是异常情况。
            if (!ok && full) {
                AppLog.w(AppLog.Category.DEPLOY, "请求完整部署但 start_maintenance 返回 false")
            }
            joinMaintenanceWithProgress(context, started)
            drainNotifications()
            val cost = System.currentTimeMillis() - started
            val message = "词库部署完成，用时 ${cost / 1000.0} 秒" +
                if (lastDeployDetail.isNotEmpty()) "（$lastDeployDetail）" else ""
            AppLog.i(AppLog.Category.DEPLOY, message)
            logBuildArtifacts(context)
            IcePrefs.setDeployResult(RimeAssetInstaller.ASSET_VERSION, true, message)
            // 只有「完整部署」或确实编译了很久才弹完成面板：
            // 平时冷启动只是几毫秒的增量校验，弹一下又消失非常打扰。
            if (full || cost > 2000L) markDeployDone(true)
            _deploy.value = _deploy.value.copy(state = State.READY, message = message, percent = 100)
            isDeploying = false
            true
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.DEPLOY, "词库部署失败", t)
            val message = "词库部署失败：${t.javaClass.simpleName}: ${t.message}"
            IcePrefs.setDeployResult(RimeAssetInstaller.ASSET_VERSION, false, message)
            markDeployDone(true)
            _deploy.value = _deploy.value.copy(state = State.FAILED, message = message)
            isDeploying = false
            false
        }
    }

    /**
     * 等待维护/编译结束，同时**按真实产物**输出进度。
     *
     * 为什么不能靠时间凑日志：librime 的编译日志在构建时被关掉了（`-DENABLE_LOGGING=OFF`），
     * `nativeJoinMaintenance` 又是阻塞调用 —— 直接等的话整个编译期间一条日志都没有。
     * 但编译过程本身会不断往 `userDir/build` 写文件（prism / table / reverse），
     * 所以这里盯着这个目录：谁刚出现、谁在变大，就按实际发生的事输出一行，
     * 例如「开始编译 rime_ice.prism.bin」「编译 rime_ice.table.bin：23.4 MB」。
     */
    private fun joinMaintenanceWithProgress(context: Context, startedAt: Long) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            runCatching { RimeNative.locked.nativeJoinMaintenance() }
            finished.set(true)
        }
        worker.isDaemon = true
        worker.start()

        val buildDir = File(RimeAssetInstaller.userDir(context), "build")
        val seenBytes = HashMap<String, Long>()
        val lastLogAt = HashMap<String, Long>()
        AppLog.i(AppLog.Category.DEPLOY, "词库维护已启动，开始编译方案与词典")

        while (!finished.get()) {
            runCatching {
                val files = buildDir.listFiles()?.filter { it.isFile }.orEmpty()
                val now = System.currentTimeMillis()
                for (f in files) {
                    val size = f.length()
                    val prev = seenBytes[f.name]
                    if (prev == null) {
                        seenBytes[f.name] = size
                        lastLogAt[f.name] = now
                        AppLog.i(AppLog.Category.DEPLOY, "开始生成 ${f.name}")
                        _deploy.value = _deploy.value.copy(message = "正在编译 ${f.name}")
                    } else if (size != prev && now - (lastLogAt[f.name] ?: 0L) >= 1500L) {
                        seenBytes[f.name] = size
                        lastLogAt[f.name] = now
                        val human = humanSize(size)
                        AppLog.i(AppLog.Category.DEPLOY, "生成 ${f.name}：$human")
                        _deploy.value = _deploy.value.copy(message = "正在编译 ${f.name}（$human）")
                    }
                }
            }
            runCatching { Thread.sleep(400) }
        }
        runCatching { worker.join() }
        val cost = System.currentTimeMillis() - startedAt
        if (cost > 1200) {
            AppLog.i(AppLog.Category.DEPLOY, "词库维护结束，用时 ${cost / 1000.0} 秒")
        }
    }

    /** 人类可读的体积（KB / MB）。 */
    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
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
        synchronized(lock) { deployBlocking(context, full = true) }
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
        if (!writeUserPatch(context)) return@withContext false
        if (!initialized && !ensureReady(context)) return@withContext false
        deployBlocking(context, full = true)
    }

    /**
     * 只写补丁文件（不部署），供初始化阶段在部署之前调用，
     * 保证「首次启动」时就带上当前的模糊音等设置，而不是等用户去动一次开关。
     */
    fun writeUserPatch(context: Context): Boolean {
        val settings = IcePrefs.current()
        val lines = mutableListOf<String>()
        // 每页候选数：候选栏是整条横向滚动，20 个既有足够备选又不卡
        lines += "  menu/page_size: 20"
        if (settings.englishCandidatesPriority) {
            // 实测有效：把英文词典权重抬高后，hel 的首屏候选变为 held/hell/help 等英文词
            lines += "  melt_eng/initial_quality: 3.0"
            lines += "  cn_en/initial_quality: 2.6"
        }
        if (!settings.learningEnabled) {
            // 主翻译器默认开启用户词典；这里关闭它以实现“停止词频学习”
            lines += "  translator/enable_user_dict: false"
        }
        // 模糊音已固化在 rime_ice.schema.yaml 的 speller/algebra 里（强制开启，无开关），
        // 这里不再生成任何 algebra 补丁，避免运行时改配置触发 20 秒重编译。
        val file = File(RimeAssetInstaller.userDir(context), SCHEMA_PATCH_FILE)
        val content = "patch:\n" + lines.joinToString("\n") + "\n"
        return try {
            file.parentFile?.mkdirs()
            // 内容没变就不写：否则每次启动都刷新 mtime，会让 rime 误判「配置比编译产物新」而重编
            if (file.isFile && file.readText() == content) {
                AppLog.i(AppLog.Category.ENGINE, "用户补丁无变化，跳过写入（${lines.size} 项）")
                return true
            }
            file.writeText(content)
            AppLog.i(AppLog.Category.ENGINE, "已写入用户补丁，共 ${lines.size} 项")
            true
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.ENGINE, "写入用户补丁失败", t)
            false
        }
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