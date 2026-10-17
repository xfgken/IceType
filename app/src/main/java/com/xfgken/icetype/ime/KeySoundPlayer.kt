package com.xfgken.icetype.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.xfgken.icetype.core.AppLog
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 按键音播放器。
 *
 * 为什么不用 `AudioManager.playSoundEffect(FX_KEY_CLICK)`：
 * 那个接口的音量完全由系统「触摸提示音」通道决定，**应用侧无法调节**，
 * 所以做不出用户要求的音量滑块。
 *
 * 这里自己合成一段很短的「嗒」声（约 15ms 的衰减正弦），用 SoundPool 播放，
 * 音量由 `play(volume)` 直接控制 —— 既能做 0–100% 滑块，也不依赖任何音频素材。
 */
object KeySoundPlayer {

    private var soundPool: SoundPool? = null
    private var soundId = 0
    private var loaded = false

    /** 用户指定的按键音文件（优先使用），以及实际存在的备用位置。 */
    private val soundFileCandidates = listOf(
        "/storage/emulated/0/music/key_press_click.wav",
        "/storage/emulated/0/AppProjects/music/key_press_click.wav",
    )

    /** 随包内置的同一份素材（外部文件读不到时用它，保证任何设备上都有声音）。 */
    private const val BUNDLED_SOUND = "sounds/key_press_click.wav"

    private const val SAMPLE_RATE = 22050
    private const val DURATION_MS = 15

    /** 是否已就绪（未就绪时调用方可以退回系统音效）。 */
    val isReady: Boolean get() = loaded && soundId != 0

    fun init(context: Context) {
        if (soundPool != null) return
        runCatching {
            val file = resolveSoundFile(context) ?: return
            val pool = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // 用媒体通道，这样我们的音量滑块才有意义，也跟随媒体音量
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            val id = pool.load(file.absolutePath, 1)
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (sampleId == id && status == 0) {
                    soundId = id
                    loaded = true
                }
            }
            soundPool = pool
        }
    }

    /**
     * 解析按键音文件：
     *  1. 用户指定的 `/storage/emulated/0/music/key_press_click.wav`（存在且可读就用它）；
     *  2. 备用位置 `/storage/emulated/0/AppProjects/music/key_press_click.wav`；
     *  3. 随包内置素材（释放到 cache）；
     *  4. 实在都没有，才退回自己合成的「嗒」声。
     */
    private fun resolveSoundFile(context: Context): File? {
        soundFileCandidates.forEach { path ->
            val f = File(path)
            if (f.isFile && f.length() > 0L && f.canRead()) {
                AppLog.i(AppLog.Category.UI, "按键音使用素材：$path（${f.length()} 字节）")
                return f
            }
        }
        val bundled = File(context.cacheDir, "key_press_click.wav")
        if (!bundled.isFile || bundled.length() == 0L) {
            runCatching {
                context.assets.open(BUNDLED_SOUND).use { input ->
                    bundled.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
        }
        if (bundled.isFile && bundled.length() > 0L) {
            AppLog.i(AppLog.Category.UI, "按键音使用内置素材（${bundled.length()} 字节）")
            return bundled
        }
        val synth = File(context.cacheDir, "icetype_keyclick.wav")
        if (!synth.isFile || synth.length() == 0L) synth.writeBytes(buildClickWav())
        AppLog.w(AppLog.Category.UI, "未找到按键音素材，回退到合成音")
        return synth
    }

    /** 重新加载素材（例如刚拿到存储读取权限后）。 */
    fun reload(context: Context) {
        release()
        init(context)
    }

    /** 播放按键音；volume 取 0f–1f。 */
    fun play(volume: Float) {
        val pool = soundPool ?: return
        if (!isReady) return
        val v = volume.coerceIn(0f, 1f)
        if (v <= 0f) return
        runCatching { pool.play(soundId, v, v, 1, 0, 1f) }
    }

    fun release() {
        runCatching { soundPool?.release() }
        soundPool = null
        soundId = 0
        loaded = false
    }

    /**
     * 合成一小段 WAV：16bit / 单声道 / 22050Hz。
     * 波形是「高频正弦 × 指数衰减」，听起来就是干净的按键嗒声。
     */
    private fun buildClickWav(): ByteArray {
        val count = SAMPLE_RATE * DURATION_MS / 1000
        val pcm = ByteArray(count * 2)
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            // 起始极短淡入，避免爆音
            val attack = (i / (SAMPLE_RATE * 0.0005)).coerceAtMost(1.0)
            val decay = exp(-t * 90.0)
            val wave = sin(2 * PI * 2400.0 * t) * 0.9 + sin(2 * PI * 5200.0 * t) * 0.25
            val sample = (wave * decay * attack * 0.7 * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val header = ByteArray(44)
        fun putAscii(offset: Int, s: String) {
            s.forEachIndexed { k, c -> header[offset + k] = c.code.toByte() }
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + pcm.size)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // 单声道
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2) // byte rate
        putShort(32, 2) // block align
        putShort(34, 16) // bits
        putAscii(36, "data")
        putInt(40, pcm.size)
        return header + pcm
    }
}
