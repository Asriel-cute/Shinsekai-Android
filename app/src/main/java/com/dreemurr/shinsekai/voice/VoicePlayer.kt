package com.dreemurr.shinsekai.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

// 每次播放替换旧实例，异常语音跳过。
class VoicePlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, speed: Float, volume: Float) {
        stop()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
            mp.setOnPreparedListener { p ->
                try {
                    p.playbackParams = p.playbackParams.setSpeed(speed)
                    p.start()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // 消费错误，避免系统弹窗。
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            stop() // 启动失败也释放实例。
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
    }

    fun release() = stop()
}
