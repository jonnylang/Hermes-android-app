package com.nous.hermesvoice.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP-клиент для Hermes API Server.
 * OpenAI-совместимый endpoint: POST /v1/chat/completions
 *
 * Поддерживает streaming (SSE) и обычный режим.
 */
class HermesClient(
    private val baseUrl: String,
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Проверка соединения: GET /v1/models
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    /**
     * Отправка сообщения и получение ответа (без streaming).
     * Возвращает текст ответа ассистента.
     */
    suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "hermes-agent")
            put("messages", messages)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.body?.string()?.take(500)}")
            }
            val body = response.body ?: throw Exception("Empty response body")
            val json = JSONObject(body.string())
            val content = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            content
        }
    }

    /**
     * Streaming-режим: читает SSE-ответ построчно.
     * Вызывает onToken для каждого текстового кусочка (для TTS-озвучки по частям).
     */
    suspend fun chatStream(
        prompt: String,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "hermes-agent")
            put("messages", messages)
            put("stream", true)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val fullText = StringBuilder()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.body?.string()?.take(500)}")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val source = body.source()
            val lineBuffer = StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break

                // SSE: пустая строка = конец события, строка "data: ..." = данные
                if (!line.startsWith("data: ")) {
                    continue
                }

                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = JSONObject(data)
                    val choices = chunk.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) {
                            val token = delta.optString("content", "")
                            if (token.isNotEmpty()) {
                                fullText.append(token)
                                onToken(token)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // пропуск неполных/невалидных чанков
                    Log.w(TAG, "Skipping malformed SSE chunk: ${data.take(100)}")
                }
            }
        }

        fullText.toString()
    }

    companion object {
        private const val TAG = "HermesClient"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}