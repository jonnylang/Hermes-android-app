package com.nous.hermesvoice.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Хранение настроек через DataStore (Preferences).
 * Сохраняет URL, ключ, wake word, язык модели и TTS-движок.
 */
private val Context.dataStore by preferencesDataStore(name = "hermes_settings")

object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val API_KEY = stringPreferencesKey("api_key")
    val WAKE_WORD = stringPreferencesKey("wake_word")
    val MODEL_LANG = stringPreferencesKey("model_lang") // "ru" or "en"
}

data class HermesSettings(
    val serverUrl: String = "http://10.8.1.1:8642",
    val apiKey: String = "",
    val wakeWord: String = "эй гермес",
    val modelLang: String = "ru"
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            serverUrl = prefs[SettingsKeys.SERVER_URL] ?: "http://10.8.1.1:8642",
            apiKey = prefs[SettingsKeys.API_KEY] ?: "",
            wakeWord = prefs[SettingsKeys.WAKE_WORD] ?: "эй гермес",
            modelLang = prefs[SettingsKeys.MODEL_LANG] ?: "ru"
        )
    }

    suspend fun update(block: (HermesSettings) -> HermesSettings) {
        context.dataStore.edit { prefs ->
            val current = HermesSettings(
                serverUrl = prefs[SettingsKeys.SERVER_URL] ?: "http://10.8.1.1:8642",
                apiKey = prefs[SettingsKeys.API_KEY] ?: "",
                wakeWord = prefs[SettingsKeys.WAKE_WORD] ?: "эй гермес",
                modelLang = prefs[SettingsKeys.MODEL_LANG] ?: "ru"
            )
            val updated = block(current)
            prefs[SettingsKeys.SERVER_URL] = updated.serverUrl
            prefs[SettingsKeys.API_KEY] = updated.apiKey
            prefs[SettingsKeys.WAKE_WORD] = updated.wakeWord
            prefs[SettingsKeys.MODEL_LANG] = updated.modelLang
        }
    }
}