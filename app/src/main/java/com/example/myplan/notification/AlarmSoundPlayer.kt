package com.example.myplan.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.example.myplan.R

/** Owns the player and audio focus for one bounded alarm session. */
class AlarmSoundPlayer(private val context: Context, private val onFailure: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var candidates = emptyList<Uri>()
    private var nextSource = 0
    private var prepared = false
    private var focused = false
    private var ownsFocus = false
    private var active = false
    private val prepareTimeout = Runnable { tryNextSource() }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(ATTRIBUTES)
        .setOnAudioFocusChangeListener { change ->
            if (!active) return@setOnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> { focused = true; if (prepared) player?.start() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    focused = false; if (prepared) player?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS -> { stop(); onFailure() }
            }
        }.build()

    fun start(sound: String) {
        stop()
        if (sound == "silent") return
        active = true
        candidates = sources(context, sound)
        nextSource = 0
        focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        ownsFocus = focused
        if (!focused) { onFailure(); return }
        tryNextSource()
    }

    private fun tryNextSource() {
        handler.removeCallbacks(prepareTimeout)
        player?.release(); player = null; prepared = false
        if (nextSource >= candidates.size) { stop(); onFailure(); return }
        val source = candidates[nextSource++]
        val current = MediaPlayer()
        player = current
        current.setAudioAttributes(ATTRIBUTES)
        current.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        current.isLooping = true
        current.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            handler.removeCallbacks(prepareTimeout)
            prepared = true
            if (focused) it.start()
            android.util.Log.i("AlarmSoundPlayer", "Alarm audio started; fallback=${nextSource == candidates.size}")
        }
        current.setOnErrorListener { mp, _, _ ->
            if (player === mp) tryNextSource()
            true
        }
        try {
            current.setDataSource(context, source)
            current.prepareAsync()
            handler.postDelayed(prepareTimeout, 5_000)
        } catch (_: Exception) { tryNextSource() }
    }

    fun stop() {
        active = false
        handler.removeCallbacks(prepareTimeout)
        player?.release(); player = null; prepared = false
        if (ownsFocus) audio.abandonAudioFocusRequest(focus)
        focused = false; ownsFocus = false
    }

    companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

        fun sources(context: Context, sound: String): List<Uri> {
            if (sound == "silent") return emptyList()
            val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val selected = if (sound == "default") default else Uri.parse(sound)
            return listOf(selected, default, Uri.parse("android.resource://${context.packageName}/${R.raw.fallback_alarm}")).distinct()
        }
    }
}
