package com.nous.hermesvoice.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.hermesvoice.data.HermesSettings
import com.nous.hermesvoice.data.SettingsRepository
import com.nous.hermesvoice.service.ChatMessage
import com.nous.hermesvoice.service.VoiceService
import com.nous.hermesvoice.service.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var voiceService: VoiceService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? VoiceService.LocalBinder
            voiceService = binder?.getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startAndBindService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesVoiceApp()
        }
    }

    @Composable
    private fun HermesVoiceApp() {
        val context = LocalContext.current
        val settingsRepo = remember { SettingsRepository(context) }
        val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = HermesSettings())
        val scope = rememberCoroutineScope()

        var serviceRunning by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }

        // Подписка на state и messages из VoiceService
        val serviceState by (voiceService?.state ?: MutableStateFlow(VoiceState.IDLE))
            .collectAsStateWithLifecycle(initialValue = VoiceState.IDLE)
        val messages by (voiceService?.messages ?: MutableStateFlow(emptyList()))
            .collectAsStateWithLifecycle(initialValue = emptyList())

        MaterialTheme(
            colorScheme = darkColorScheme()
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Hermes Voice",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                    }

                    if (showSettings) {
                        SettingsPanel(
                            settings = settings,
                            onSave = { newSettings ->
                                scope.launch {
                                    settingsRepo.update { newSettings }
                                }
                            }
                        )
                    } else {
                        // Main content
                        MainContent(
                            settings = settings,
                            serviceRunning = serviceRunning,
                            serviceState = serviceState,
                            messages = messages,
                            permissionsGranted = checkPermissions(),
                            onRequestPermissions = { requestPermissions() },
                            onToggleService = {
                                if (serviceRunning) {
                                    VoiceService.stop(context)
                                    serviceRunning = false
                                } else {
                                    if (checkPermissions()) {
                                        startAndBindService()
                                        serviceRunning = true
                                    } else {
                                        requestPermissions()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MainContent(
        settings: HermesSettings,
        serviceRunning: Boolean,
        serviceState: VoiceState,
        messages: List<ChatMessage>,
        permissionsGranted: Boolean,
        onRequestPermissions: () -> Unit,
        onToggleService: () -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            // Status indicator
            StatusCard(serviceRunning = serviceRunning, serviceState = serviceState)

            // Start/Stop button
            Button(
                onClick = onToggleService,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (serviceRunning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    if (serviceRunning) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (serviceRunning) "Остановить" else "Запустить",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (!permissionsGranted) {
                Text(
                    "Нужно разрешение на микрофон",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = onRequestPermissions) {
                    Text("Выдать разрешения")
                }
            }

            // Server info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Сервер: ${settings.serverUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("Wake word: «${settings.wakeWord}»", style = MaterialTheme.typography.bodySmall)
                    Text("Язык: ${if (settings.modelLang == "ru") "Русский" else "English"}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // Chat log
            Text("История диалога:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Диалог будет здесь после запуска",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(messages) { msg ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.role == "user")
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    if (msg.role == "user") "Вы" else "Hermes",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusCard(serviceRunning: Boolean, serviceState: VoiceState) {
        val (bgColor, icon, text) = when {
            !serviceRunning -> Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                Icons.Default.MicOff,
                "Ассистент остановлен"
            )
            serviceState == VoiceState.IDLE -> Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                Icons.Default.Mic,
                "Ожидание: «${currentSettings()?.wakeWord ?: "эй гермес"}»"
            )
            serviceState == VoiceState.LISTENING -> Triple(
                MaterialTheme.colorScheme.tertiaryContainer,
                Icons.Default.GraphicEq,
                "🎤 Слушаю…"
            )
            serviceState == VoiceState.THINKING -> Triple(
                MaterialTheme.colorScheme.primaryContainer,
                Icons.Default.Psychology,
                "🧠 Думаю…"
            )
            serviceState == VoiceState.SPEAKING -> Triple(
                MaterialTheme.colorScheme.secondaryContainer,
                Icons.Default.VolumeUp,
                "🔊 Говорю…"
            )
            serviceState == VoiceState.ERROR -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                Icons.Default.Error,
                "⚠️ Ошибка"
            )
            else -> Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                Icons.Default.MicOff,
                "Ассистент остановлен"
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Row(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    private fun currentSettings(): HermesSettings? = voiceService?.currentSettings

    @Composable
    private fun SettingsPanel(
        settings: HermesSettings,
        onSave: (HermesSettings) -> Unit
    ) {
        var serverUrl by remember { mutableStateOf(settings.serverUrl) }
        var apiKey by remember { mutableStateOf(settings.apiKey) }
        var wakeWord by remember { mutableStateOf(settings.wakeWord) }
        var modelLang by remember { mutableStateOf(settings.modelLang) }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("URL сервера Hermes") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API ключ (Bearer Token)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            OutlinedTextField(
                value = wakeWord,
                onValueChange = { wakeWord = it.lowercase() },
                label = { Text("Ключевая фраза") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Language selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = modelLang == "ru",
                    onClick = { modelLang = "ru" },
                    label = { Text("Русский") }
                )
                FilterChip(
                    selected = modelLang == "en",
                    onClick = { modelLang = "en" },
                    label = { Text("English") }
                )
            }

            Button(
                onClick = { onSave(HermesSettings(serverUrl, apiKey, wakeWord, modelLang)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Сохранить")
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        } else PackageManager.PERMISSION_GRANTED
        return mic == PackageManager.PERMISSION_GRANTED && notif == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startAndBindService() {
        VoiceService.start(this)
        bindService(Intent(this, VoiceService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}