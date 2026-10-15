package com.example.myplan.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Each recording owns its AudioRecord; an old worker cannot read a new session's microphone. */
class AudioRecorder(private val context: android.content.Context) {
    interface RecordListener {
        fun onSpeechStarted()
        fun onSpeechEnded(pcmData: ByteArray)
        fun onSilenceTimeout()
        fun onError(msg: String)
    }
    private data class Session(val record: AudioRecord, val running: AtomicBoolean = AtomicBoolean(true))
    @Volatile private var session: Session? = null
    private var listener: RecordListener? = null
    fun setListener(l: RecordListener) { listener = l }
    fun isPermissionGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun startListening() {
        stopListening()
        if (!isPermissionGranted()) { listener?.onError("录音权限未授予"); return }
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) { listener?.onError("无法获取录音缓冲区"); return }
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size * 2)
        } catch (e: SecurityException) { listener?.onError("录音权限未授予"); return }
        catch (e: Exception) { listener?.onError("麦克风打开失败"); return }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); listener?.onError("录音器初始化失败"); return
        }
        val current = Session(record)
        session = current
        try { record.startRecording() }
        catch (e: Exception) { session = null; record.release(); listener?.onError("无法开始录音"); return }
        Thread {
            try {
                val buffer = ByteArray(size)
                val speech = ByteArrayOutputStream()
                var silentFrames = 0
                var speechFrames = 0
                val started = System.nanoTime()
                while (current.running.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (!current.running.get()) break
                    if (read <= 0) { if (session === current) listener?.onError("录音读取失败"); break }
                    var sum = 0L
                    for (i in 0 until (read - 1) step 2) {
                        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 255)).toShort().toLong()
                        sum += sample * sample
                    }
                    val loud = kotlin.math.sqrt(sum.toDouble() / (read / 2).coerceAtLeast(1)) > 500
                    if (loud) {
                        if (speechFrames == 0 && session === current) listener?.onSpeechStarted()
                        speechFrames++; silentFrames = 0
                    } else if (speechFrames > 0) silentFrames++
                    if (speechFrames > 0) speech.write(buffer, 0, read)
                    if (silentFrames >= 25) {
                        if (session === current) {
                            if (speechFrames >= 5) listener?.onSpeechEnded(speech.toByteArray())
                            else listener?.onError("语音太短，请重新点击麦克风")
                        }
                        break
                    }
                    if ((System.nanoTime() - started) >= 30_000_000_000L) {
                        if (session === current) listener?.onSilenceTimeout()
                        break
                    }
                }
            } catch (e: Exception) {
                if (current.running.get() && session === current) listener?.onError("录音中断")
            } finally {
                current.running.set(false)
                try { record.stop() } catch (_: Exception) { }
                record.release()
            }
        }.start()
    }

    @Synchronized fun stopListening() {
        val current = session ?: return
        session = null
        current.running.set(false)
        try { current.record.stop() } catch (_: Exception) { }
    }
}
