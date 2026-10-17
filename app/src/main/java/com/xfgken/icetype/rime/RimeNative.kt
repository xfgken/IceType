package com.xfgken.icetype.rime

import com.xfgken.icetype.core.AppLog

/**
 * librime 的 JNI 桥接声明（实现见 `app/src/main/cpp/icetype_rime.cpp`）。
 *
 * 注意：方法签名必须与 C++ 侧 `Java_com_xfgken_icetype_rime_RimeNative_nativeXxx` 完全一致，
 * ProGuard 规则中已对 `RimeNative` 做整体 keep。
 *
 * **所有** native 方法都是 private，只通过 [locked] 暴露：rime 的 Service（引擎创建、部署、
 * 会话生命周期）不是线程安全的，必须串行调用，否则会在 Lua 组件构造阶段破坏 lua_State，
 * 表现为 `lua_load` 空指针或 `lua_pcallk` 之后 longjmp 触发 SIGILL（已在真机 tombstone 复现）。
 */
object RimeNative {

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    /**
     * 取出一条引擎通知（type + "\u0001" + value）；无通知时返回 null。
     *
     * 设计说明：早期实现使用 `GetStaticMethodID` 注册 JNI 回调，在真机上触发了
     * ART 的 `ClassLinker::InitializeClass → PrettyDescriptor` 空指针崩溃（已由 tombstone 确认）。
     * 现在改为 native 侧入队、Kotlin 侧轮询，完全不涉及 JNI 类查找与跨线程回调。
     */
    private external fun nativePollNotification(): String?

    /** 加载原生库；失败不抛出，交由上层显示可读错误与重试入口。 */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("icetype_rime")
                loaded = true
                loadError = null
                true
            } catch (t: Throwable) {
                loadError = "${t.javaClass.simpleName}: ${t.message}"
                AppLog.throwable(AppLog.Category.ERROR, "加载 libicetype_rime.so 失败", t)
                false
            }
        }
    }

    val isLoaded: Boolean get() = loaded

    /** 所有 librime 调用的串行锁（可重入由 synchronized 保证同线程递归安全）。 */
    private val apiLock = Any()

    // —— native 声明（仅本文件可见） ——

    private external fun nativeVersion(): String

    private external fun nativeSetup(
        sharedDir: String,
        userDir: String,
        logDir: String,
        version: String,
    ): Boolean

    private external fun nativeInitialize(version: String): Boolean

    private external fun nativeFinalize()

    private external fun nativeStartMaintenance(full: Boolean): Boolean

    private external fun nativeJoinMaintenance()

    private external fun nativeIsMaintenanceMode(): Boolean

    private external fun nativeDeploy(): Boolean

    private external fun nativeDeploySchema(file: String): Boolean

    private external fun nativeCreateSession(): Long

    private external fun nativeDestroySession(session: Long): Boolean

    private external fun nativeCleanupStaleSessions()

    private external fun nativeProcessKey(session: Long, keycode: Int, mask: Int): Boolean

    private external fun nativeGetInput(session: Long): String

    private external fun nativeGetCaretPos(session: Long): Int

    private external fun nativeSetCaretPos(session: Long, pos: Int)

    private external fun nativeSetInput(session: Long, input: String): Boolean

    private external fun nativeSelectCandidate(session: Long, index: Int): Boolean

    private external fun nativeSelectCandidateOnPage(session: Long, index: Int): Boolean

    private external fun nativeDeleteCandidateOnPage(session: Long, index: Int): Boolean

    private external fun nativeHighlightOnPage(session: Long, index: Int): Boolean

    private external fun nativeChangePage(session: Long, backward: Boolean): Boolean

    private external fun nativeCommitComposition(session: Long): Boolean

    private external fun nativeClearComposition(session: Long)

    private external fun nativeGetCommit(session: Long): String?

    private external fun nativeGetContext(session: Long): String

    private external fun nativeGetStatus(session: Long): String

    private external fun nativeSetOption(session: Long, name: String, value: Boolean)

    private external fun nativeGetOption(session: Long, name: String): Boolean

    private external fun nativeSetProperty(session: Long, name: String, value: String)

    private external fun nativeGetProperty(session: Long, name: String): String

    private external fun nativeGetSchemaList(): String

    private external fun nativeSelectSchema(session: Long, schemaId: String): Boolean

    private external fun nativeGetCurrentSchema(session: Long): String

    private external fun nativeSyncUserData(): Boolean

    private external fun nativeGetSharedDataDir(): String

    private external fun nativeGetUserDataDir(): String

    /**
     * 加锁后的 librime 调用入口 —— 上层（[RimeEngine]）只能走这里。
     *
     * 一把全局锁把所有「引擎创建 / 部署 / 会话增删 / 按键与候选读取」串起来：
     * 输入法服务、设置页自检、压力自测即使同时发起调用，也不会再并发进入 librime。
     */
    object locked {

        fun nativePollNotification(): String? =
            synchronized(apiLock) { RimeNative.nativePollNotification() }

        fun nativeVersion(): String =
            synchronized(apiLock) { RimeNative.nativeVersion() }

        fun nativeSetup(
            sharedDir: String,
            userDir: String,
            logDir: String,
            version: String,
        ): Boolean = synchronized(apiLock) {
            RimeNative.nativeSetup(sharedDir, userDir, logDir, version)
        }

        fun nativeInitialize(version: String): Boolean =
            synchronized(apiLock) { RimeNative.nativeInitialize(version) }

        fun nativeFinalize() = synchronized(apiLock) { RimeNative.nativeFinalize() }

        fun nativeStartMaintenance(full: Boolean): Boolean =
            synchronized(apiLock) { RimeNative.nativeStartMaintenance(full) }

        fun nativeJoinMaintenance() = synchronized(apiLock) { RimeNative.nativeJoinMaintenance() }

        fun nativeIsMaintenanceMode(): Boolean =
            synchronized(apiLock) { RimeNative.nativeIsMaintenanceMode() }

        fun nativeDeploy(): Boolean = synchronized(apiLock) { RimeNative.nativeDeploy() }

        fun nativeDeploySchema(file: String): Boolean =
            synchronized(apiLock) { RimeNative.nativeDeploySchema(file) }

        fun nativeCreateSession(): Long = synchronized(apiLock) { RimeNative.nativeCreateSession() }

        fun nativeDestroySession(session: Long): Boolean =
            synchronized(apiLock) { RimeNative.nativeDestroySession(session) }

        fun nativeCleanupStaleSessions() = synchronized(apiLock) { RimeNative.nativeCleanupStaleSessions() }

        fun nativeProcessKey(session: Long, keycode: Int, mask: Int): Boolean =
            synchronized(apiLock) { RimeNative.nativeProcessKey(session, keycode, mask) }

        fun nativeGetInput(session: Long): String =
            synchronized(apiLock) { RimeNative.nativeGetInput(session) }

        fun nativeGetCaretPos(session: Long): Int =
            synchronized(apiLock) { RimeNative.nativeGetCaretPos(session) }

        fun nativeSetCaretPos(session: Long, pos: Int) =
            synchronized(apiLock) { RimeNative.nativeSetCaretPos(session, pos) }

        fun nativeSetInput(session: Long, input: String): Boolean =
            synchronized(apiLock) { RimeNative.nativeSetInput(session, input) }

        fun nativeSelectCandidate(session: Long, index: Int): Boolean =
            synchronized(apiLock) { RimeNative.nativeSelectCandidate(session, index) }

        fun nativeSelectCandidateOnPage(session: Long, index: Int): Boolean =
            synchronized(apiLock) { RimeNative.nativeSelectCandidateOnPage(session, index) }

        fun nativeDeleteCandidateOnPage(session: Long, index: Int): Boolean =
            synchronized(apiLock) { RimeNative.nativeDeleteCandidateOnPage(session, index) }

        fun nativeHighlightOnPage(session: Long, index: Int): Boolean =
            synchronized(apiLock) { RimeNative.nativeHighlightOnPage(session, index) }

        fun nativeChangePage(session: Long, backward: Boolean): Boolean =
            synchronized(apiLock) { RimeNative.nativeChangePage(session, backward) }

        fun nativeCommitComposition(session: Long): Boolean =
            synchronized(apiLock) { RimeNative.nativeCommitComposition(session) }

        fun nativeClearComposition(session: Long) =
            synchronized(apiLock) { RimeNative.nativeClearComposition(session) }

        fun nativeGetCommit(session: Long): String? =
            synchronized(apiLock) { RimeNative.nativeGetCommit(session) }

        fun nativeGetContext(session: Long): String =
            synchronized(apiLock) { RimeNative.nativeGetContext(session) }

        fun nativeGetStatus(session: Long): String =
            synchronized(apiLock) { RimeNative.nativeGetStatus(session) }

        fun nativeSetOption(session: Long, name: String, value: Boolean) =
            synchronized(apiLock) { RimeNative.nativeSetOption(session, name, value) }

        fun nativeGetOption(session: Long, name: String): Boolean =
            synchronized(apiLock) { RimeNative.nativeGetOption(session, name) }

        fun nativeSetProperty(session: Long, name: String, value: String) =
            synchronized(apiLock) { RimeNative.nativeSetProperty(session, name, value) }

        fun nativeGetProperty(session: Long, name: String): String =
            synchronized(apiLock) { RimeNative.nativeGetProperty(session, name) }

        fun nativeGetSchemaList(): String =
            synchronized(apiLock) { RimeNative.nativeGetSchemaList() }

        fun nativeSelectSchema(session: Long, schemaId: String): Boolean =
            synchronized(apiLock) { RimeNative.nativeSelectSchema(session, schemaId) }

        fun nativeGetCurrentSchema(session: Long): String =
            synchronized(apiLock) { RimeNative.nativeGetCurrentSchema(session) }

        fun nativeSyncUserData(): Boolean = synchronized(apiLock) { RimeNative.nativeSyncUserData() }

        fun nativeGetSharedDataDir(): String =
            synchronized(apiLock) { RimeNative.nativeGetSharedDataDir() }

        fun nativeGetUserDataDir(): String =
            synchronized(apiLock) { RimeNative.nativeGetUserDataDir() }
    }
}