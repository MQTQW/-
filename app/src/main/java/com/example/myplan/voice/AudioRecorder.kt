package com.example.myplan.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/**
 * 本地录音器——用 AudioRecord 采集 PCM，RMS 静音检测。
 * 不需要 Google 语音引擎，纯本地硬件录音。
 */
class AudioRecorder(private val context: android.content.Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // 静音阈值：RMS 低于此值视为安静
        private const val SILENCE_THRESHOLD = 500
        // 连续静音多少帧判定为"说完了"
        private const val SILENCE_FRAMES_STOP = 25
        // 最小有效语音帧数（降低以支持"不用了"等短应答）
        private const val MIN_SPEECH_FRAMES = 5
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var listener: RecordListener? = null

    interface RecordListener {
        fun onSpeechStarted()
        fun onSpeechEnded(pcmData: ByteArray)
        fun onSilenceTimeout()
        fun onError(msg: String)
    }

    fun setListener(l: RecordListener) { listener = l }

    fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun startListening() {
        stopListening()  // 先停掉旧的录音线程
        if (!isPermissionGranted()) {
            listener?.onError("录音权限未授予")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            listener?.onError("无法获取录音缓冲区")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING,
                bufferSize * 2
            )
        } catch (e: Exception) {
            listener?.onError("麦克风打开失败: ${e.message}")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            listener?.onError("录音器初始化失败")
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        Log.d(TAG, "Recording started")

        // 在后台线程读取音频数据
        Thread {
            val buffer = ByteArray(bufferSize)
            val speechBuffer = mutableListOf<Byte>()
            var silenceCount = 0
            var totalFrames = 0
            var speechStarted = false
            var speechFrames = 0

            while (isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead <= 0) continue

                // 计算 RMS（音量）
                var sum = 0L
                for (i in 0 until bytesRead step 2) {
                    val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                    sum += (sample * sample).toLong()
                }
                val rms = kotlin.math.sqrt(sum.toDouble() / (bytesRead / 2)).toInt()

                if (rms > SILENCE_THRESHOLD) {
                    // 检测到语音
                    if (!speechStarted) {
                        speechStarted = true
                        listener?.onSpeechStarted()
                        Log.d(TAG, "Speech started (RMS=$rms)")
                    }
                    silenceCount = 0
                    speechFrames++
                    speechBuffer.addAll(buffer.take(bytesRead).toByteArray().toList())
                } else if (speechStarted) {
                    // 语音进行中，检测到静音
                    silenceCount++
                    speechBuffer.addAll(buffer.take(bytesRead).toByteArray().toList())

                    if (silenceCount >= SILENCE_FRAMES_STOP) {
                        // 连续静音 → 用户说完了
                        Log.d(TAG, "Speech ended, frames=$speechFrames")
                        if (speechFrames >= MIN_SPEECH_FRAMES) {
                            val pcm = speechBuffer.toByteArray()
                            listener?.onSpeechEnded(pcm)
                        } else {
                            listener?.onError("语音太短")
                        }
                        // 重置
                        speechBuffer.clear()
                        speechStarted = false
                        speechFrames = 0
                        silenceCount = 0
                    }
                }

                totalFrames++
                // 30 秒超时
                if (totalFrames > (SAMPLE_RATE / bufferSize) * 30) {
                    if (!speechStarted) {
                        listener?.onSilenceTimeout()
                    }
                    speechBuffer.clear()
                    speechStarted = false
                    speechFrames = 0
                    silenceCount = 0
                    totalFrames = 0
                }
            }
        }.start()
    }

    fun stopListening() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "Recording stopped")
    }
}
