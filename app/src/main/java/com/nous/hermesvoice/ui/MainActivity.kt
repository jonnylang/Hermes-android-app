package com.nous.hermesvoice.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.nous.hermesvoice.net.HermesClient
import com.nous.hermesvoice.service.ChatMessage
import com.nous.hermesvoice.service.VoiceService
import com.nous.hermesvoice.service.VoiceState
import com.nous.hermesvoice.util.AppLogger
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
        var showLogs by remember { mutableStateOf(false) }

        // Локальное состояние для UI — обновляем через LaunchedEffect
        var serviceState by remember { mutableStateOf(VoiceState.IDLE) }
        var messages by remember { mutableStateOf(emptyList<ChatMessage>()) }

        // Подписываемся на state и messages из сервиса
        LaunchedEffect(voiceService) {
            voiceService?.state?.collect { serviceState = it }
        }
        LaunchedEffect(voiceService) {
            voiceService?.messages?.collect { messages = it }
        }

        MaterialTheme(
            colorScheme = darkColorScheme()
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Hermes Voice",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        if (!showSettings && !showLogs) {
                            Row {
                                IconButton(onClick = { showLogs = true }) {
                                    Icon(Icons.Default.BugReport, contentDescription = "Логи")
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Настройки")
                                }
                            }
                        }
                    }

                    when {
                        showLogs -> LogsPanel(onBack = { showLogs = false })
                        showSettings -> SettingsPanel(
                            settings = settings,
                            onSave = { newSettings ->
                                scope.launch {
                                    settingsRepo.update { newSettings }
                                    voiceService?.updateSettings(newSettings)
                                }
                            },
                            onBack = { showSettings = false }
                        )
                        else -> MainContent(
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
    private fun LogsPanel(onBack: () -> Unit) {
        val logEntries = remember { mutableStateListOf<AppLogger.LogEntry>() }
        val listState = rememberLazyListState()
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            while (true) {
                logEntries.clear()
                logEntries.addAll(AppLogger.getEntries())
                kotlinx.coroutines.delay(2000)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Логи", style = MaterialTheme.typography.headlineSmall)
                Row {
                    IconButton(onClick = {
                        val text = AppLogger.getEntries().joinToString("\n") { entry ->
                            "[${entry.timestamp}] [${entry.level}] [${entry.tag}] ${entry.message}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Hermes Logs", text))
                        Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать логи")
                    }
                    IconButton(onClick = {
                        AppLogger.clear()
                        logEntries.clear()
                    }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить логи")
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (logEntries.isEmpty()) {
                    item {
                        Text(
                            "Логов пока нет",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(logEntries) { entry ->
                        val color = when (entry.level) {
                            "E" -> MaterialTheme.colorScheme.error
                            "W" -> MaterialTheme.colorScheme.tertiary
                            "I" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = "[${entry.timestamp}] [${entry.level}] [${entry.tag}] ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
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

            StatusCard(serviceRunning = serviceRunning, serviceState = serviceState)

            val buttonLabel = if (serviceRunning) "Остановить" else "Запустить"
            val buttonIcon = if (serviceRunning) Icons.Default.Stop else Icons.Default.Mic
            val buttonColor = if (serviceRunning) MaterialTheme.colorScheme.error
                              else MaterialTheme.colorScheme.primary
            Button(
                onClick = onToggleService,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
            ) {
                Icon(buttonIcon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Сервер: ${settings.serverUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("Язык: ${if (settings.modelLang == "ru") "Русский" else "English"}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

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
                "Нажмите «Запустить» чтобы начать"
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

    @Composable
    private fun SettingsPanel(
        settings: HermesSettings,
        onSave: (HermesSettings) -> Unit,
        onBack: () -> Unit
    ) {
        var serverUrl by remember { mutableStateOf(settings.serverUrl) }
        var apiKey by remember { mutableStateOf(settings.apiKey) }
        var modelLang by remember { mutableStateOf(settings.modelLang) }

        var testResult by remember { mutableStateOf<String?>(null) }
        var testInProgress by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Настройки", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                }
            }

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
                onClick = {
                    testInProgress = true
                    testResult = null
                    scope.launch {
                        val client = HermesClient(serverUrl, apiKey)
                        val result = client.testConnection()
                        testResult = result
                        testInProgress = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !testInProgress
            ) {
                if (testInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Тест соединения")
            }

            if (testResult != null) {
                val isOk = testResult!!.startsWith("✅")
                Text(
                    testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    onSave(HermesSettings(serverUrl, apiKey, "", modelLang))
                },
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
