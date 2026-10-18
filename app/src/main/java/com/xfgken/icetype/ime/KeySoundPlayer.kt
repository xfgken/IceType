package com.xfgken.icetype.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.xfgken.icetype.R
import com.xfgken.icetype.core.AppLog

/**
 * 按键音播放器（三种音效）。
 *
 * 素材来自微信输入法，随包内置在 `res/raw`：
 *  - [Kind.CLICK]  打字：26 个字母、符号、表情
 *  - [Kind.ACTION] 功能键：大小写、数字键盘上的按键、空格、回车、
 *                  按钮栏所有按钮、数字键盘页的表情键与符号键
 *  - [Kind.DELETE] 删除键（退格，含长按连删）
 *
 * 音量由 `play(kind, volume)` 直接控制，所以「按键音音量」滑块对三种音效统一生效。
 */
object KeySoundPlayer {

    /** 三种按键音。 */
    enum class Kind { CLICK, ACTION, DELETE }

    private var soundPool: SoundPool? = null
    private val ids = HashMap<Kind, Int>()
    private val loadedKinds = HashSet<Kind>()

    /** 任一音效是否已就绪（未就绪时调用方可以退回系统音效）。 */
    val isReady: Boolean get() = loadedKinds.isNotEmpty()

    fun init(context: Context) {
        if (soundPool != null) return
        runCatching {
            val pool = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // 用媒体通道，这样音量滑块才有意义，也跟随媒体音量
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            val resIds = mapOf(
                Kind.CLICK to R.raw.key_press_click,
                Kind.ACTION to R.raw.key_press_action,
                Kind.DELETE to R.raw.key_press_delete,
            )
            resIds.forEach { (kind, res) ->
                ids[kind] = pool.load(context.applicationContext, res, 1)
            }
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) return@setOnLoadCompleteListener
                ids.entries.firstOrNull { it.value == sampleId }?.let { loadedKinds.add(it.key) }
            }
            soundPool = pool
        }
        AppLog.i(AppLog.Category.UI, "按键音素材：click/action/delete（内置 res/raw）")
    }

    /** 重新加载素材。 */
    fun reload(context: Context) {
        release()
        init(context)
    }

    /** 播放某个音效；volume 取 0f–1f。 */
    fun play(kind: Kind, volume: Float) {
        val pool = soundPool ?: return
        val id = ids[kind] ?: return
        if (!loadedKinds.contains(kind)) return
        val v = volume.coerceIn(0f, 1f)
        if (v <= 0f) return
        runCatching { pool.play(id, v, v, 1, 0, 1f) }
    }

    /** 兼容旧调用：等价于播放打字音。 */
    fun play(volume: Float) = play(Kind.CLICK, volume)

    fun release() {
        runCatching { soundPool?.release() }
        soundPool = null
        ids.clear()
        loadedKinds.clear()
    }
}