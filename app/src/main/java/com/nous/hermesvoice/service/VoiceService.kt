package com.nous.hermesvoice.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nous.hermesvoice.HermesVoiceApp
import com.nous.hermesvoice.R
import com.nous.hermesvoice.data.HermesSettings
import com.nous.hermesvoice.data.SettingsRepository
import com.nous.hermesvoice.net.HermesClient
import com.nous.hermesvoice.tts.TtsManager
import com.nous.hermesvoice.ui.MainActivity
import com.nous.hermesvoice.vosk.VoskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.app.NotificationManager

/**
 * Состояния голосового ассистента (state machine).
 */
enum class VoiceState {
    IDLE,           // Ожидание wake word
    LISTENING,      // Запрос пользователя (свободная речь)
    THINKING,       // Запрос к Hermes API
    SPEAKING,       // Озвучка ответа
    ERROR           // Ошибка
}

/**
 * Сообщение в логе диалога (для UI).
 */
data class ChatMessage(
    val role: String,   // "user" | "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Главный foreground service.
 *
 * State machine:
 *   IDLE → (wake word detected) → LISTENING
 *   LISTENING → (silence / final result) → THINKING
 *   THINKING → (Hermes response) → SPEAKING
 *   SPEAKING → (TTS done) → IDLE
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var vosk: VoskManager
    private lateinit var tts: TtsManager
    private var hermesClient: HermesClient? = null
    private val mutex = Mutex()

    // State machine — доступно из UI через Companion
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    var currentSettings: HermesSettings? = null
    private var listenJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        // WakeLock — чтобы CPU не засыпал при выключенном экране
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesVoice::WakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 часов

        settingsRepo = SettingsRepository(this)

        vosk = VoskManager(
            context = this,
            onPartialResult = { partial ->
                if (_state.value == VoiceState.LISTENING && partial.isNotEmpty()) {
                    Log.d(TAG, "Partial: $partial")
                }
            },
            onFinalResult = { final ->
                handleFinalResult(final)
            },
            onTimeout = {
                Log.w(TAG, "Vosk timeout — restarting wake word mode")
                scope.launch {
                    mutex.withLock {
                        vosk.startWakeWord(currentSettings?.wakeWord ?: "эй гермес")
                    }
                }
            }
        )

        tts = TtsManager(this)
        tts.onDone = {
            if (_state.value == VoiceState.SPEAKING) {
                Log.i(TAG, "TTS done → IDLE")
                transitionTo(VoiceState.IDLE)
            }
        }

        // Загружаем настройки и стартуем
        scope.launch {
            val settings = settingsRepo.settings.first()
            currentSettings = settings
            initAndStart(settings)
        }
    }

    private suspend fun initAndStart(settings: HermesSettings) {
        // Инициализация Vosk
        val lang = settings.modelLang
        val ok = vosk.init(lang)
        if (!ok) {
            Log.e(TAG, "Vosk init failed")
            transitionTo(VoiceState.ERROR)
            return
        }

        // Создаём Hermes-клиент
        hermesClient = HermesClient(settings.serverUrl, settings.apiKey)

        // Стартуем wake word режим
        startWakeWordMode(settings.wakeWord)
    }

    private fun startWakeWordMode(wakeWord: String) {
        transitionTo(VoiceState.IDLE)
        Log.i(TAG, "Starting wake word mode: \"$wakeWord\"")

        listenJob = scope.launch {
            mutex.withLock {
                vosk.startWakeWord(wakeWord)
            }
        }
    }

    private fun startListeningMode() {
        transitionTo(VoiceState.LISTENING)
        Log.i(TAG, "Starting free-form listening")

        listenJob = scope.launch {
            mutex.withLock {
                vosk.startFreeForm()
            }
        }
    }

    /**
     * Обработка финального результата распознавания.
     * В wake word режиме — переход к listening.
     * В listening режиме — отправка запроса Hermes.
     */
    private fun handleFinalResult(text: String) {
        if (text.isBlank()) return

        val state = _state.value
        Log.i(TAG, "Final result [$state]: \"$text\"")

        when (state) {
            VoiceState.IDLE -> {
                // Проверяем wake word
                val wakeWord = currentSettings?.wakeWord ?: "эй гермес"
                if (text.contains(wakeWord)) {
                    Log.i(TAG, "Wake word detected → LISTENING")
                    // Короткий звуковой сигнал / вибрация (TODO)
                    startListeningMode()
                } else {
                    // Перезапускаем wake word режим
                    scope.launch { mutex.withLock { vosk.startWakeWord(wakeWord) } }
                }
            }

            VoiceState.LISTENING -> {
                // Это запрос пользователя
                if (text.isNotBlank() && text.length > 1) {
                    // Добавляем в лог
                    _messages.value = _messages.value + ChatMessage("user", text)
                    // Останавливаем Vosk
                    vosk.stop()
                    // Отправляем Hermes
                    sendToHermes(text)
                } else {
                    // Пустой результат — возвращаемся к wake word
                    scope.launch {
                        mutex.withLock {
                            vosk.startWakeWord(currentSettings?.wakeWord ?: "эй гермес")
                        }
                    }
                }
            }

            else -> { /* игнорируем */ }
        }
    }

    private fun sendToHermes(prompt: String) {
        transitionTo(VoiceState.THINKING)
        listenJob?.cancel()

        scope.launch(Dispatchers.IO) {
            try {
                val client = hermesClient ?: throw Exception("Hermes client not initialized")

                // Streaming-режим: озвучиваем по предложениям
                transitionTo(VoiceState.SPEAKING)
                val fullResponse = client.chatStream(prompt) { token ->
                    tts.feedToken(token)
                }
                tts.flush()

                // Добавляем ответ в лог
                if (fullResponse.isNotBlank()) {
                    _messages.value = _messages.value + ChatMessage("assistant", fullResponse)
                }

                Log.i(TAG, "Hermes response: ${fullResponse.take(100)}…")

            } catch (e: Exception) {
                Log.e(TAG, "Hermes request failed", e)
                _messages.value = _messages.value + ChatMessage("assistant", "Ошибка: ${e.message}")
                tts.speak("Произошла ошибка при обращении к серверу.")
                transitionTo(VoiceState.ERROR)
                delay(2000)
            }

            // Возврат к wake word режиму
            transitionTo(VoiceState.IDLE)
            scope.launch {
                mutex.withLock {
                    vosk.startWakeWord(currentSettings?.wakeWord ?: "эй гермес")
                }
            }
        }
    }

    private fun transitionTo(newState: VoiceState) {
        Log.d(TAG, "State: ${_state.value} → $newState")
        _state.value = newState
        updateNotification(newState)
    }

    // =================== Notification ===================

    private fun createNotification(state: VoiceState): Notification {
        val text = when (state) {
            VoiceState.IDLE -> getString(R.string.notif_idle)
            VoiceState.LISTENING -> getString(R.string.notif_listening)
            VoiceState.THINKING -> getString(R.string.notif_thinking)
            VoiceState.SPEAKING -> getString(R.string.notif_speaking)
            VoiceState.ERROR -> "⚠️ Ошибка"
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, HermesVoiceApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(state: VoiceState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, createNotification(state))
    }

    // =================== Binder (для UI) ===================

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // =================== Lifecycle ===================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, createNotification(VoiceState.IDLE))
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        wakeLock?.release()
        wakeLock = null
        vosk.close()
        tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // =================== Companion (для UI) ===================

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIF_ID = 42

        // Доступ к state flow из UI (через Service reference)
        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}