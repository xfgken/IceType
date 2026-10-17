package com.xfgken.iceime.rime

import com.xfgken.iceime.core.AppLog

/**
 * librime 的 JNI 桥接声明（实现见 `app/src/main/cpp/icetype_rime.cpp`）。
 *
 * 注意：方法签名必须与 C++ 侧 `Java_com_xfgken_iceime_rime_RimeNative_nativeXxx` 完全一致，
 * ProGuard 规则中已对 `RimeNative` 做整体 keep。
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
    external fun nativePollNotification(): String?


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

    external fun nativeVersion(): String

    external fun nativeSetup(
        sharedDir: String,
        userDir: String,
        logDir: String,
        version: String,
    ): Boolean

    external fun nativeInitialize(version: String): Boolean

    external fun nativeFinalize()

    external fun nativeStartMaintenance(full: Boolean): Boolean

    external fun nativeJoinMaintenance()

    external fun nativeIsMaintenanceMode(): Boolean

    external fun nativeDeploy(): Boolean

    external fun nativeDeploySchema(file: String): Boolean

    external fun nativeCreateSession(): Long

    external fun nativeDestroySession(session: Long): Boolean

    external fun nativeCleanupStaleSessions()

    external fun nativeProcessKey(session: Long, keycode: Int, mask: Int): Boolean

    external fun nativeGetInput(session: Long): String

    external fun nativeGetCaretPos(session: Long): Int

    external fun nativeSetCaretPos(session: Long, pos: Int)

    external fun nativeSetInput(session: Long, input: String): Boolean

    external fun nativeSelectCandidate(session: Long, index: Int): Boolean

    external fun nativeSelectCandidateOnPage(session: Long, index: Int): Boolean

    external fun nativeDeleteCandidateOnPage(session: Long, index: Int): Boolean

    external fun nativeHighlightOnPage(session: Long, index: Int): Boolean

    external fun nativeChangePage(session: Long, backward: Boolean): Boolean

    external fun nativeCommitComposition(session: Long): Boolean

    external fun nativeClearComposition(session: Long)

    external fun nativeGetCommit(session: Long): String?

    external fun nativeGetContext(session: Long): String

    external fun nativeGetStatus(session: Long): String

    external fun nativeSetOption(session: Long, name: String, value: Boolean)

    external fun nativeGetOption(session: Long, name: String): Boolean

    external fun nativeSetProperty(session: Long, name: String, value: String)

    external fun nativeGetProperty(session: Long, name: String): String

    external fun nativeGetSchemaList(): String

    external fun nativeSelectSchema(session: Long, schemaId: String): Boolean

    external fun nativeGetCurrentSchema(session: Long): String

    external fun nativeSyncUserData(): Boolean

    external fun nativeGetSharedDataDir(): String

    external fun nativeGetUserDataDir(): String
}