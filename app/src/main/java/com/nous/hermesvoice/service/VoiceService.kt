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
    private lateinit var vosk: VoskManager
    private lateinit var tts: TtsManager
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

        vosk = VoskManager(
            context = this,
            onPartialResult = { partial ->
                if (_state.value == VoiceState.LISTENING && partial.isNotEmpty()) {
                    AppLogger.d(TAG, "Partial: $partial")
                }
            },
            onFinalResultCb = { final ->
                handleFinalResult(final)
            },
            onTimeout = {
                AppLogger.w(TAG, "Vosk timeout — restarting")
                scope.launch {
                    mutex.withLock { vosk.stop() }
                    transitionTo(VoiceState.IDLE)
                    startListening()
                }
            }
        )

        tts = TtsManager(this)
        tts.onDone = {
            AppLogger.i(TAG, "TTS onDone called, state=${_state.value}")
            // onDone может вызываться из любого потока, переключаемся на scope
            scope.launch {
                if (_state.value == VoiceState.SPEAKING) {
                    AppLogger.i(TAG, "TTS done → restart listening")
                    transitionTo(VoiceState.IDLE)
                    startListening()
                }
            }
        }

        scope.launch {
            val settings = settingsRepo.settings.first()
            currentSettings = settings
            initService(settings)
        }
    }

    private suspend fun initService(settings: HermesSettings) {
        val lang = settings.modelLang
        val ok = vosk.init(lang)
        if (!ok) {
            AppLogger.e(TAG, "Vosk init failed")
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
        AppLogger.i(TAG, "Starting free-form listening")

        listenJob = scope.launch {
            mutex.withLock {
                vosk.startFreeForm()
            }
        }
    }

    fun updateSettings(settings: HermesSettings) {
        currentSettings = settings
        hermesClient = HermesClient(settings.serverUrl, settings.apiKey)
        AppLogger.i(TAG, "Settings updated: server=${settings.serverUrl}, lang=${settings.modelLang}")
    }

    private fun handleFinalResult(text: String) {
        if (text.isBlank()) return

        val state = _state.value
        AppLogger.i(TAG, "Final result [$state]: \"$text\"")

        if (state == VoiceState.LISTENING) {
            if (text.isNotBlank() && text.length > 1) {
                AppLogger.i(TAG, "Recognized text, sending to Hermes")
                _messages.value = _messages.value + ChatMessage("user", text)
                vosk.stop()
                sendToHermes(text)
            } else {
                AppLogger.w(TAG, "Empty/too short result, restarting")
                scope.launch {
                    mutex.withLock { vosk.stop() }
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

                transitionTo(VoiceState.SPEAKING)
                val fullResponse = client.chatStream(prompt) { token ->
                    tts.feedToken(token)
                }
                tts.flush()

                if (fullResponse.isNotBlank()) {
                    _messages.value = _messages.value + ChatMessage("assistant", fullResponse)
                }

                AppLogger.i(TAG, "Hermes response: ${fullResponse.take(100)}…")

            } catch (e: Exception) {
                AppLogger.e(TAG, "Hermes request failed: ${e.message}")
                _messages.value = _messages.value + ChatMessage("assistant", "Ошибка: ${e.message}")
                tts.speak("Произошла ошибка при обращении к серверу.")
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
        vosk.close()
        tts.shutdown()
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
