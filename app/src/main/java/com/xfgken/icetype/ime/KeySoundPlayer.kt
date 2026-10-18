package com.xfgken.icetype.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
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
    /** 只用于查询系统音效通道的音量档位（绝对音量补偿），不持有 Activity / Service。 */
    private var appContext: Context? = null
    private val ids = HashMap<Kind, Int>()
    private val loadedKinds = HashSet<Kind>()

    /** 任一音效是否已就绪（未就绪时调用方可以退回系统音效）。 */
    val isReady: Boolean get() = loadedKinds.isNotEmpty()

    fun init(context: Context) {
        if (soundPool != null) return
        appContext = context.applicationContext
        runCatching {
            val pool = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // 按键音走「系统音效」通道（Sonification），与主流输入法一致：
                        // 音量由系统音量控制，不会因为媒体音量被调到 0 / 拉大而异常。
                        // 音色仍是自带的三套素材，相对音量由「按键音音量」设置控制。
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
        val v = effectiveVolume(volume)
        if (v <= 0f) return
        runCatching { pool.play(id, v, v, 1, 0, 1f) }
    }

    /** 兼容旧调用：等价于播放打字音。 */
    fun play(volume: Float) = play(Kind.CLICK, volume)

    /**
     * 「绝对音量」补偿。
     *
     * 系统混音器一定会把播放音量再乘一次「系统音效通道」的档位，所以应用侧传的 0~1
     * 天然是相对值。这里读取当前档位并反算回去：最终响度 ≈ 设置里的百分比，
     * 系统音量被调小也不会把按键音一起变小声。
     *
     * 两个极限情况补偿不了（系统限制，无权限绕过）：
     *  - 系统音效音量已经为 0（静音），乘什么都是 0；
     *  - 档位很低时反算值会超过 1.0，被 SoundPool 截到 1.0。
     */
    private fun effectiveVolume(volume: Float): Float {
        val v = volume.coerceIn(0f, 1f)
        val ctx = appContext ?: return v
        val ratio = runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
            val cur = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
            if (max <= 0) 1f else cur.toFloat() / max.toFloat()
        }.getOrDefault(1f)
        if (ratio <= 0.05f) return v // 系统音效几乎静音：补偿不了，按原值播放
        return (v / ratio).coerceIn(0f, 1f)
    }

    fun release() {
        runCatching { soundPool?.release() }
        soundPool = null
        ids.clear()
        loadedKinds.clear()
    }
}