package com.nous.hermesvoice.net

import android.util.Log
import com.nous.hermesvoice.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
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
    // OkHttp — без прокси (обходим VPN-прокси, которые блокируют прямые соединения)
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Клиент с очень длинным таймаутом для проблемных сетей (VPN)
    private val longTimeoutClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Расширенный тест соединения.
     * Проверяет: DNS, TCP-соединение, HTTP-ответ.
     * Возвращает подробный отчёт.
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.trimEnd('/')
            AppLogger.i(TAG, "=== Network diagnostic ===")
            AppLogger.i(TAG, "URL: $url")

            // Шаг 1: парсим URL
            val parsedUrl: URL
            try {
                parsedUrl = URL(url)
                AppLogger.d(TAG, "Host: ${parsedUrl.host}, Port: ${parsedUrl.port}, Protocol: ${parsedUrl.protocol}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Invalid URL: ${e.message}")
                return@withContext "❌ Неверный URL: ${e.message}"
            }

            val host = parsedUrl.host
            val port = if (parsedUrl.port > 0) parsedUrl.port else if (parsedUrl.protocol == "https") 443 else 80

            // Шаг 2: DNS resolution
            AppLogger.i(TAG, "Step 1: DNS lookup for $host")
            val addresses: Array<InetAddress> = try {
                InetAddress.getAllByName(host)
            } catch (e: Exception) {
                AppLogger.e(TAG, "DNS failed: ${e.message}")
                return@withContext "❌ DNS: не удалось разрешить $host — ${e.message}"
            }

            if (addresses.isEmpty()) {
                AppLogger.e(TAG, "DNS returned no addresses")
                return@withContext "❌ DNS: не найдено адресов для $host"
            }

            val dnsInfo = addresses.joinToString(", ") { "${it.hostAddress} (${if (it is java.net.Inet4Address) "IPv4" else "IPv6"})" }
            AppLogger.i(TAG, "DNS resolved: $dnsInfo")

            // Шаг 3: TCP connect
            AppLogger.i(TAG, "Step 2: TCP connect to $host:$port")
            var tcpOk = false
            var tcpError = ""
            for (addr in addresses) {
                try {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(addr, port), 5000)
                    sock.close()
                    tcpOk = true
                    AppLogger.i(TAG, "TCP OK to ${addr.hostAddress}:$port")
                    break
                } catch (e: Exception) {
                    tcpError = e.message ?: e.toString()
                    AppLogger.w(TAG, "TCP failed to ${addr.hostAddress}:$port — $tcpError")
                }
            }

            if (!tcpOk) {
                AppLogger.e(TAG, "All TCP attempts failed")
                return@withContext "❌ TCP: не удалось подключиться к $host:$port — $tcpError"
            }

            // Шаг 4: HTTP GET /v1/models
            AppLogger.i(TAG, "Step 3: HTTP GET $url/v1/models")
            val testUrl = "$url/v1/models"
            val requestBuilder = Request.Builder()
                .url(testUrl)
                .get()

            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
                AppLogger.d(TAG, "Using API key: ${apiKey.take(8)}…")
            } else {
                AppLogger.d(TAG, "No API key, sending without Authorization")
            }

            val request = requestBuilder.build()

            // Сначала пробуем с коротким таймаутом
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()?.take(300) ?: ""
                    AppLogger.d(TAG, "HTTP ${response.code} ${response.message}")
                    AppLogger.d(TAG, "Response body: $body")

                    if (response.isSuccessful) {
                        AppLogger.i(TAG, "✅ Connection OK")
                        "✅ Соединение установлено"
                    } else if (response.code == 401) {
                        AppLogger.w(TAG, "HTTP 401 — invalid API key")
                        "❌ HTTP 401 — неверный API ключ"
                    } else {
                        AppLogger.w(TAG, "HTTP ${response.code}")
                        "❌ HTTP ${response.code}: $body"
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Short timeout failed: ${e.message}")
                AppLogger.i(TAG, "Retrying with 30s timeout…")

                // Пробуем с длинным таймаутом
                try {
                    longTimeoutClient.newCall(request).execute().use { response ->
                        val body = response.body?.string()?.take(300) ?: ""
                        AppLogger.d(TAG, "HTTP ${response.code} ${response.message} (long timeout)")
                        AppLogger.d(TAG, "Response body: $body")

                        if (response.isSuccessful) {
                            AppLogger.i(TAG, "✅ Connection OK (long timeout)")
                            "✅ Соединение установлено"
                        } else if (response.code == 401) {
                            "❌ HTTP 401 — неверный API ключ"
                        } else {
                            "❌ HTTP ${response.code}: $body"
                        }
                    }
                } catch (e2: Exception) {
                    AppLogger.e(TAG, "Both attempts failed: ${e2.message}")
                    val msg = e2.message ?: e2.toString()
                    if (msg.length > 200) msg.take(200) + "…" else msg
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Test failed: ${e.message}")
            val msg = e.message ?: e.toString()
            if (msg.length > 200) msg.take(200) + "…" else msg
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
