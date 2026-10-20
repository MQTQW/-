package com.example.myplan.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.Ringtone
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.example.myplan.R

/** Owns the player and audio focus for one bounded alarm session. */
class AlarmSoundPlayer(private val context: Context, private val onEvent: (String) -> Unit = {}, private val onFailure: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var candidates = emptyList<Uri>()
    private var nextSource = 0
    private var prepared = false
    private var focused = false
    private var focusRequested = false
    private var active = false
    private var ducked = false
    private var systemRingtone: Ringtone? = null
    private val verifyRingtone = Runnable {
        if (active) {
            if (systemRingtone?.isPlaying == true) onEvent("ringtone_started") else trySystemRingtone()
        }
    }
    private val prepareTimeout = Runnable { tryNextSource() }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(ATTRIBUTES)
        .setOnAudioFocusChangeListener { change ->
            if (!active) return@setOnAudioFocusChangeListener
            onEvent("focus_change_$change")
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focused = true; ducked = false; player?.setVolume(1f, 1f); systemRingtone?.volume = 1f
                    if (prepared) player?.start()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true; player?.setVolume(.25f, .25f); systemRingtone?.volume = .25f
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
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
        focusRequested = true
        onEvent(if (focused) "focus_granted" else "focus_denied")
        if (!focused) {
            // Android 15 can deny focus even to a cold-started alarm FGS.
            // Use the public ringtone API for the alarm, without changing app-ops,
            // system volume or Do Not Disturb. Do not compete with a phone call.
            if (audio.mode != AudioManager.MODE_NORMAL) { onEvent("call_audio_busy"); onFailure(); return }
            android.util.Log.i("AlarmSoundPlayer", "Focus denied; trying system alarm ringtone")
            trySystemRingtone()
            return
        }
        tryNextSource()
    }

    private fun trySystemRingtone() {
        handler.removeCallbacks(verifyRingtone)
        systemRingtone?.stop(); systemRingtone = null
        if (!active) return
        while (nextSource < candidates.size) {
            try {
                onEvent("ringtone_loading")
                val ringtone = RingtoneManager.getRingtone(context, candidates[nextSource++]) ?: continue
                systemRingtone = ringtone
                ringtone.audioAttributes = ATTRIBUTES
                ringtone.isLooping = true
                ringtone.volume = if (ducked) .25f else 1f
                ringtone.play()
                onEvent("ringtone_requested")
                handler.postDelayed(verifyRingtone, 250)
                android.util.Log.i("AlarmSoundPlayer", "System alarm ringtone requested")
                return
            } catch (_: Exception) { onEvent("ringtone_source_failed"); systemRingtone?.stop(); systemRingtone = null }
        }
        stop(); onFailure()
    }

    private fun tryNextSource() {
        handler.removeCallbacks(prepareTimeout)
        handler.removeCallbacks(verifyRingtone)
        systemRingtone?.stop(); systemRingtone = null
        player?.release(); player = null; prepared = false
        if (nextSource >= candidates.size) { stop(); onFailure(); return }
        val source = candidates[nextSource++]
        val current = MediaPlayer()
        player = current
        current.setAudioAttributes(ATTRIBUTES)
        current.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        current.isLooping = true
        if (ducked) current.setVolume(.25f, .25f)
        current.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            handler.removeCallbacks(prepareTimeout)
            prepared = true
            onEvent("audio_prepared")
            if (focused) it.start()
            if (focused) onEvent("audio_started")
            android.util.Log.i("AlarmSoundPlayer", "Alarm audio started; fallback=${nextSource == candidates.size}")
        }
        current.setOnErrorListener { mp, _, _ ->
            if (player === mp) tryNextSource()
            true
        }
        try {
            onEvent("source_opening")
            current.setDataSource(context, source)
            onEvent("source_opened")
            current.prepareAsync()
            handler.postDelayed(prepareTimeout, 5_000)
        } catch (_: Exception) { onEvent("source_failed"); tryNextSource() }
    }

    fun stop() {
        if (active) onEvent("audio_stopped")
        active = false
        handler.removeCallbacks(prepareTimeout)
        handler.removeCallbacks(verifyRingtone)
        systemRingtone?.stop(); systemRingtone = null
        player?.release(); player = null; prepared = false
        if (focusRequested) audio.abandonAudioFocusRequest(focus)
        focused = false; focusRequested = false; ducked = false
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
