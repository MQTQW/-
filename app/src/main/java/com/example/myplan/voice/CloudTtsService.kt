package com.example.myplan.voice

import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端 TTS——火山引擎语音合成 V1。
 * 文字 → 火山引擎 → MP3 → MediaPlayer 播放。
 */
object CloudTtsService {

    private const val TAG = "CloudTts"
    private const val TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

    private var apiKey: String? = null
    fun init(key: String) { apiKey = key }

    private var currentPlayer: MediaPlayer? = null
    private var currentFile: File? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun speak(text: String, done: () -> Unit, error: (String) -> Unit) {
        stop()
        val token = generation.get()
        val key = apiKey
        if (key.isNullOrBlank()) { error("API Key not set"); return }
        Thread {
            try {
                val audio = requestTts(text, key)
                main.post {
                    if (generation.get() == token) {
                        if (audio == null) error("TTS request failed")
                        else playMp3(audio, token, done, error)
                    }
                }
            } catch (e: Exception) {
                main.post { if (generation.get() == token) error(e.message ?: "TTS failed") }
            }
        }.start()
    }

    fun stop() {
        generation.incrementAndGet()
        releasePlayer()
    }

    private fun releasePlayer() {
        try { currentPlayer?.release() } catch (_: Exception) { }
        currentPlayer = null
        currentFile?.delete()
        currentFile = null
    }

    private fun requestTts(text: String, key: String): ByteArray? {
        try {
            val body = JSONObject().apply {
                put("user", JSONObject().apply { put("uid", "myplan") })
                put("req_params", JSONObject().apply {
                    put("text", text)
                    put("speaker", "zh_female_xiaohe_uranus_bigtts")
                    put("audio_params", JSONObject().apply {
                        put("format", "mp3")
                        put("sample_rate", 24000)
                        put("speech_rate", 0)
                    })
                })
            }

            val url = URL(TTS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Api-Key", key)
            conn.setRequestProperty("X-Api-Resource-Id", "seed-tts-2.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val fullB64 = StringBuilder()
                val reader = conn.inputStream.bufferedReader()
                reader.forEachLine { line ->
                    try {
                        val json = JSONObject(line)
                        val data = json.optString("data", "")
                        if (data.isNotEmpty()) fullB64.append(data)
                    } catch (_: Exception) {}
                }
                if (fullB64.isEmpty()) { Log.e(TAG, "TTS: no data accumulated"); return null }
                val mp3 = android.util.Base64.decode(fullB64.toString(), android.util.Base64.DEFAULT)
                Log.d(TAG, "TTS decoded: ${mp3.size} bytes mp3 from ${fullB64.length} chars b64")
                return mp3.takeIf { it.size > 500 }
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "TTS HTTP ${conn.responseCode}: ${err.take(200)}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS request failed", e)
            return null
        }
    }

    private fun playMp3(bytes: ByteArray, token: Int, done: () -> Unit, error: (String) -> Unit) {
        if (generation.get() != token) return
        try {
            val file = File.createTempFile("myplan_tts", ".mp3")
            currentFile = file
            file.writeBytes(bytes)
            currentPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    if (generation.get() == token) { releasePlayer(); done() }
                }
                setOnErrorListener { _, _, _ ->
                    if (generation.get() == token) { releasePlayer(); error("Playback error") }
                    true
                }
                setOnPreparedListener { if (generation.get() == token) it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            releasePlayer()
            if (generation.get() == token) error(e.message ?: "Playback failed")
        }
    }
}
