package com.nous.hermesvoice.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hermes_settings")

object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val API_KEY = stringPreferencesKey("api_key")
    val MODEL_LANG = stringPreferencesKey("model_lang")
    val STT_PROVIDER = stringPreferencesKey("stt_provider")  // "google" | "vosk"
    val TTS_PROVIDER = stringPreferencesKey("tts_provider")  // "android" | "edge"
}

data class HermesSettings(
    val serverUrl: String = "http://10.8.1.1:8642",
    val apiKey: String = "",
    val modelLang: String = "ru",
    val sttProvider: String = "google",   // google (рекомендуется) или vosk
    val ttsProvider: String = "edge"      // edge (рекомендуется) или android
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            serverUrl = prefs[SettingsKeys.SERVER_URL] ?: "http://10.8.1.1:8642",
            apiKey = prefs[SettingsKeys.API_KEY] ?: "",
            modelLang = prefs[SettingsKeys.MODEL_LANG] ?: "ru",
            sttProvider = prefs[SettingsKeys.STT_PROVIDER] ?: "google",
            ttsProvider = prefs[SettingsKeys.TTS_PROVIDER] ?: "edge"
        )
    }

    suspend fun update(block: (HermesSettings) -> HermesSettings) {
        context.dataStore.edit { prefs ->
            val current = HermesSettings(
                serverUrl = prefs[SettingsKeys.SERVER_URL] ?: "http://10.8.1.1:8642",
                apiKey = prefs[SettingsKeys.API_KEY] ?: "",
                modelLang = prefs[SettingsKeys.MODEL_LANG] ?: "ru",
                sttProvider = prefs[SettingsKeys.STT_PROVIDER] ?: "google",
                ttsProvider = prefs[SettingsKeys.TTS_PROVIDER] ?: "edge"
            )
            val updated = block(current)
            prefs[SettingsKeys.SERVER_URL] = updated.serverUrl
            prefs[SettingsKeys.API_KEY] = updated.apiKey
            prefs[SettingsKeys.MODEL_LANG] = updated.modelLang
            prefs[SettingsKeys.STT_PROVIDER] = updated.sttProvider
            prefs[SettingsKeys.TTS_PROVIDER] = updated.ttsProvider
        }
    }
}
