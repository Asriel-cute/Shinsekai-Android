package com.dreemurr.shinsekai.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

// 循环 BGM 播放器，与台词 VoicePlayer 相互独立；音量实时可调，异常静默。
class BgmPlayer {
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var volume = 0.3f

    // 同一曲目不重启循环；切曲时替换实例。
    fun play(file: File) {
        if (currentFile == file && player != null) return
        stop()
        try {
            val mp = MediaPlayer()
            player = mp
            currentFile = file
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.setOnPreparedListener { p ->
                try {
                    p.start()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // 消费错误（个别 ROM 缺 FLAC 解码器），避免系统弹窗。
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            stop() // 启动失败也释放实例。
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        try {
            player?.setVolume(volume, volume)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        currentFile = null
    }
}
