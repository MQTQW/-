package com.example.myplan.voice

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 云端 STT——火山引擎大模型 ASR（WebSocket 二进制协议）。
 * 使用流式输入模式 (bigmodel_nostream)：发送全量音频 → 返回完整识别结果。
 */
object CloudSttService {

    private const val TAG = "CloudStt"
    private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"

    private var apiKey: String? = null
    fun init(key: String) { apiKey = key }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun recognize(pcm: ByteArray): CloudSttResult? = withContext(Dispatchers.IO) {
        val key = apiKey ?: return@withContext null
        try {
          withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { cont ->
                val reqId = UUID.randomUUID().toString()
                val request = Request.Builder().url(WS_URL)
                    .header("X-Api-Key", key)
                    .header("X-Api-Resource-Id", "volc.seedasr.sauc.duration")
                    .header("X-Api-Request-Id", reqId)
                    .header("X-Api-Sequence", "-1")
                    .build()

                var completed = false
                val resultText = StringBuilder()

                val socket = client.newWebSocket(request, object : WebSocketListener() {

                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.d(TAG, "WS connected")
                        // Send full client request
                        val config = JSONObject().apply {
                            put("user", JSONObject().apply { put("uid", "myplan") })
                            put("audio", JSONObject().apply {
                                put("format", "pcm")
                                put("rate", 16000)
                                put("bits", 16)
                                put("channel", 1)
                                put("codec", "raw")
                                put("language", "zh-CN")
                            })
                            put("request", JSONObject().apply {
                                put("model_name", "bigmodel")
                                put("enable_itn", true)
                                put("enable_punc", true)
                            })
                        }
                        sendMessage(ws, config.toString().toByteArray(), true, false)
                        // Send audio data
                        sendMessage(ws, pcm, false, false)
                        // Send end-of-stream (negative sequence)
                        sendMessage(ws, ByteArray(0), false, true)
                    }

                    override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                        if (completed || !cont.isActive) return
                        try {
                            val payload = extractPayload(bytes.toByteArray())
                            if (payload != null && payload.isNotEmpty()) {
                                val json = String(payload)
                                Log.d(TAG, "Result: ${json.take(300)}")
                                val text = parseResult(json)
                                if (text.isNotBlank()) {
                                    resultText.append(text)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse err", e)
                        }
                    }

                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "WS closing: $code $reason")
                        ws.close(1000, null)
                        finish(if (resultText.isNotEmpty()) resultText.toString() else null)
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "WS closed: $code $reason")
                        finish(if (resultText.isNotEmpty()) resultText.toString() else null)
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "WS fail: ${t.message}")
                        if (response != null) Log.e(TAG, "  code=${response.code}")
                        finish(null)
                    }

                    private fun finish(text: String?) {
                        if (completed) return
                        completed = true
                        if (text != null) {
                            cont.resume(CloudSttResult(text, "volc-ws", 0.95f), null)
                        } else {
                            cont.resume(null, null)
                        }
                    }
                })
                cont.invokeOnCancellation { socket.cancel() }
            }
          }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "WS exception", e)
            null
        }
    }

    private fun sendMessage(ws: WebSocket, data: ByteArray, isJson: Boolean, isLast: Boolean) {
        val header = ByteArray(4)
        // Byte 0: version(4bits=0001) + header_size(4bits=0001)
        header[0] = 0x11.toByte()
        // Byte 1: msg_type(4bits) + flags(4bits)
        if (isJson) {
            header[1] = 0x10.toByte()  // type=0001(full client req), flags=0000
        } else {
            header[1] = if (isLast) 0x22.toByte() else 0x20.toByte()
            // type=0010(audio only), flags=0010(last without seq) or 0000
        }
        // Byte 2: serialization(4bits=0001 for JSON, 0000 for raw) + compression(4bits=0000)
        header[2] = if (isJson) 0x10.toByte() else 0x00.toByte()
        // Byte 3: reserved
        header[3] = 0x00.toByte()

        val payloadSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
        val frame = ByteArray(header.size + payloadSize.size + data.size)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(payloadSize, 0, frame, header.size, payloadSize.size)
        System.arraycopy(data, 0, frame, header.size + payloadSize.size, data.size)
        ws.send(frame.toByteString())
    }

    private fun extractPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 8) return null
        val headerByte1 = frame[1].toInt() and 0xFF
        val msgType = (headerByte1 shr 4) and 0x0F
        val flags = headerByte1 and 0x0F
        // Only process server responses (type 9 = full server response, 15 = error)
        if (msgType != 9 && msgType != 15) return null

        // Check if response has sequence number (flags & 1)
        val seqOffset = if ((flags and 1) != 0) 4 else 0
        val sizeOffset = 4 + seqOffset  // header(4) + optional sequence(4)
        if (frame.size < sizeOffset + 4) return null
        val payloadSize = ByteBuffer.wrap(frame, sizeOffset, 4).order(ByteOrder.BIG_ENDIAN).int
        if (payloadSize <= 0 || sizeOffset + 4 + payloadSize > frame.size) return null
        return frame.copyOfRange(sizeOffset + 4, sizeOffset + 4 + payloadSize)
    }

    private fun parseResult(json: String): String {
        return try {
            val r = JSONObject(json)
            r.optJSONObject("result")?.optString("text") ?: ""
        } catch (e: Exception) { "" }
    }
}

data class CloudSttResult(val text: String, val engine: String, val confidence: Float)
