package com.nous.hermesvoice.tts

import android.util.Log
import com.nous.hermesvoice.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Edge TTS через Microsoft API.
 * Использует публичный endpoint для синтеза речи.
 */
class EdgeTtsManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var isSpeaking = false
    var onDone: (() -> Unit)? = null

    /**
     * Синтезирует речь через Edge TTS API.
     * Возвращает аудиофайл (WAV/MP3).
     */
    suspend fun speak(text: String, lang: String = "ru"): Boolean = withContext(Dispatchers.IO) {
        try {
            val voice = if (lang == "ru") "ru-RU-DmitryNeural" else "en-US-JennyNeural"
            val url = "https://api-edge-tts.nousresearch.com/v1/tts"

            val body = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("rate", "+20%")
            }

            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "Edge TTS HTTP ${response.code}")
                return@withContext false
            }

            // Сохраняем аудио во временный файл и воспроизводим
            val audioBytes = response.body?.bytes()
            if (audioBytes == null || audioBytes.isEmpty()) {
                AppLogger.e(TAG, "Edge TTS empty response")
                return@withContext false
            }

            // TODO: воспроизвести audioBytes через MediaPlayer
            // Пока заглушка — просто логируем
            AppLogger.i(TAG, "Edge TTS: ${text.take(50)}… (${audioBytes.size} bytes)")
            isSpeaking = true
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Edge TTS failed: ${e.message}")
            false
        }
    }

    fun stop() {
        isSpeaking = false
    }

    fun shutdown() {
        stop()
    }

    companion object {
        private const val TAG = "EdgeTtsManager"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
