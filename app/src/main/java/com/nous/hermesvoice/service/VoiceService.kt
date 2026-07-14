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
import com.nous.hermesvoice.stt.GoogleSttManager
import com.nous.hermesvoice.tts.EdgeTtsManager
import com.nous.hermesvoice.tts.TtsManager
import com.nous.hermesvoice.ui.MainActivity
import com.nous.hermesvoice.util.AppLogger
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

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsRepo: SettingsRepository
    private var vosk: VoskManager? = null
    private var googleStt: GoogleSttManager? = null
    private var androidTts: TtsManager? = null
    private var edgeTts: EdgeTtsManager? = null
    private var hermesClient: HermesClient? = null
    private val mutex = Mutex()

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    var currentSettings: HermesSettings? = null
    private var listenJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesVoice::WakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)

        settingsRepo = SettingsRepository(this)

        // Инициализируем все менеджеры
        vosk = VoskManager(
            context = this,
            onPartialResult = { partial ->
                if (_state.value == VoiceState.LISTENING && partial.isNotEmpty()) {
                    AppLogger.d(TAG, "Vosk partial: $partial")
                }
            },
            onFinalResultCb = { final -> handleFinalResult(final) },
            onTimeout = { onSttTimeout() }
        )

        googleStt = GoogleSttManager(this).apply {
            onResult = { text -> handleFinalResult(text) }
            onTimeout = { onSttTimeout() }
            onError = { msg ->
                AppLogger.e(TAG, "Google STT error: $msg")
                scope.launch {
                    transitionTo(VoiceState.IDLE)
                    startListening()
                }
            }
        }

        androidTts = TtsManager(this).apply {
            onDone = { onTtsDone() }
        }

        edgeTts = EdgeTtsManager().apply {
            onDone = { onTtsDone() }
        }

        scope.launch {
            val settings = settingsRepo.settings.first()
            currentSettings = settings
            initService(settings)
        }
    }

    private fun onTtsDone() {
        AppLogger.i(TAG, "TTS done, state=${_state.value}")
        scope.launch {
            if (_state.value == VoiceState.SPEAKING) {
                AppLogger.i(TAG, "TTS done → restart listening")
                transitionTo(VoiceState.IDLE)
                startListening()
            }
        }
    }

    private fun onSttTimeout() {
        AppLogger.w(TAG, "STT timeout — restarting")
        scope.launch {
            stopStt()
            transitionTo(VoiceState.IDLE)
            startListening()
        }
    }

    private suspend fun initService(settings: HermesSettings) {
        // Инициализируем выбранный STT
        val sttOk = when (settings.sttProvider) {
            "google" -> googleStt?.init(settings.modelLang) ?: false
            else -> vosk?.init(settings.modelLang) ?: false
        }
        if (!sttOk) {
            AppLogger.e(TAG, "STT init failed for provider: ${settings.sttProvider}")
            transitionTo(VoiceState.ERROR)
            return
        }

        hermesClient = HermesClient(settings.serverUrl, settings.apiKey)
        startListening()
    }

    fun startListening() {
        if (_state.value != VoiceState.IDLE) {
            AppLogger.w(TAG, "startListening ignored: state=${_state.value}")
            return
        }
        transitionTo(VoiceState.LISTENING)
        val settings = currentSettings ?: return
        AppLogger.i(TAG, "Starting listening (STT: ${settings.sttProvider})")

        listenJob = scope.launch {
            mutex.withLock {
                when (settings.sttProvider) {
                    "google" -> googleStt?.startListening(settings.modelLang)
                    else -> vosk?.startFreeForm()
                }
            }
        }
    }

    private fun stopStt() {
        vosk?.stop()
        googleStt?.stop()
    }

    fun updateSettings(settings: HermesSettings) {
        currentSettings = settings
        hermesClient = HermesClient(settings.serverUrl, settings.apiKey)
        AppLogger.i(TAG, "Settings updated: stt=${settings.sttProvider}, tts=${settings.ttsProvider}")
    }

    private fun handleFinalResult(text: String) {
        if (text.isBlank()) return

        val state = _state.value
        AppLogger.i(TAG, "Final result [$state]: \"$text\"")

        if (state == VoiceState.LISTENING) {
            if (text.isNotBlank() && text.length > 1) {
                AppLogger.i(TAG, "Recognized text, sending to Hermes")
                _messages.value = _messages.value + ChatMessage("user", text)
                stopStt()
                sendToHermes(text)
            } else {
                AppLogger.w(TAG, "Empty/too short result, restarting")
                scope.launch {
                    stopStt()
                    transitionTo(VoiceState.IDLE)
                    startListening()
                }
            }
        }
    }

    private fun sendToHermes(prompt: String) {
        transitionTo(VoiceState.THINKING)
        listenJob?.cancel()

        scope.launch(Dispatchers.IO) {
            try {
                val client = hermesClient ?: throw Exception("Hermes client not initialized")
                val settings = currentSettings ?: throw Exception("No settings")

                transitionTo(VoiceState.SPEAKING)

                if (settings.ttsProvider == "edge") {
                    // Edge TTS: ждём полный ответ, потом синтезируем
                    val fullResponse = client.chat(prompt)
                    if (fullResponse.isNotBlank()) {
                        _messages.value = _messages.value + ChatMessage("assistant", fullResponse)
                        edgeTts?.speak(fullResponse, settings.modelLang)
                    }
                    AppLogger.i(TAG, "Hermes response: ${fullResponse.take(100)}…")
                } else {
                    // Android TTS: streaming
                    val fullResponse = client.chatStream(prompt) { token ->
                        androidTts?.feedToken(token)
                    }
                    androidTts?.flush()
                    if (fullResponse.isNotBlank()) {
                        _messages.value = _messages.value + ChatMessage("assistant", fullResponse)
                    }
                    AppLogger.i(TAG, "Hermes response: ${fullResponse.take(100)}…")
                }

            } catch (e: Exception) {
                AppLogger.e(TAG, "Hermes request failed: ${e.message}")
                _messages.value = _messages.value + ChatMessage("assistant", "Ошибка: ${e.message}")
                androidTts?.speak("Произошла ошибка при обращении к серверу.")
                transitionTo(VoiceState.ERROR)
                delay(2000)
                transitionTo(VoiceState.IDLE)
                startListening()
            }
        }
    }

    private fun transitionTo(newState: VoiceState) {
        AppLogger.d(TAG, "State: ${_state.value} → $newState")
        _state.value = newState
        updateNotification(newState)
    }

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

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, createNotification(VoiceState.IDLE))
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        wakeLock?.release()
        wakeLock = null
        vosk?.close()
        googleStt?.close()
        androidTts?.shutdown()
        edgeTts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIF_ID = 42

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
