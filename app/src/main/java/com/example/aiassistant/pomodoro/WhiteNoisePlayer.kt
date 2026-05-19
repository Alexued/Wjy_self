package com.example.aiassistant.pomodoro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator

class WhiteNoisePlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var toneGen: ToneGenerator? = null
    private var currentType: String = ""
    private var volume: Float = 0.6f
    private var playing: Boolean = false
    private var useFallback: Boolean = false

    fun setNoiseType(type: String) {
        if (type == currentType) return
        val wasPlaying = playing
        stop()
        currentType = type
        useFallback = !hasAssetFile(type)
        if (wasPlaying && type.isNotEmpty()) {
            play()
        }
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        try { mediaPlayer?.setVolume(volume, volume) } catch (_: Exception) {}
    }

    fun play() {
        if (currentType.isEmpty()) return
        if (playing) return

        releasePlayer()

        if (useFallback) {
            playFallbackTone()
            return
        }

        val assetPath = "white_noise/$currentType.mp3"
        val afd = try { context.assets.openFd(assetPath) } catch (_: Exception) {
            useFallback = true
            playFallbackTone()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            playing = true
        } catch (e: Exception) {
            e.printStackTrace()
            releasePlayer()
        } finally {
            try { afd.close() } catch (_: Exception) {}
        }
    }

    fun pause() {
        if (useFallback) {
            releaseTone()
        } else {
            try { mediaPlayer?.pause() } catch (_: Exception) {}
        }
        playing = false
    }

    fun stop() {
        releasePlayer()
        releaseTone()
        playing = false
    }

    fun release() = stop()

    fun isPlaying(): Boolean = playing

    fun hasAssetFile(type: String): Boolean {
        return try {
            context.assets.openFd("white_noise/$type.mp3").close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun playFallbackTone() {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            playing = true
            // 300ms 后自动清理 ToneGenerator
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                releaseTone()
                playing = false
            }, 300)
        } catch (_: Exception) {
            releaseTone()
        }
    }

    private fun releasePlayer() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun releaseTone() {
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
    }
}
