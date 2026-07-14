package com.nous.hermesvoice.vosk

import android.content.Context
import android.util.Log
import com.nous.hermesvoice.util.AppLogger
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Управление Vosk-моделью: загрузка из assets, инициализация.
 *
 * Модель должна лежать в assets/models/vosk-model-small-ru-0.22 (или en-us-0.15).
 * При первом запуске копируется в filesDir.
 */
object VoskModelLoader {

    private const val TAG = "VoskModelLoader"

    /**
     * Копирует модель из assets в filesDir, если ещё не скопирована.
     * Возвращает путь к директории модели.
     */
    fun ensureModel(context: Context, lang: String): String {
        val modelName = if (lang == "ru") "vosk-model-small-ru-0.22" else "vosk-model-small-en-us-0.15"
        val targetDir = File(context.filesDir, modelName)

        if (targetDir.exists() && File(targetDir, "am/mfcc.conf").exists()) {
            AppLogger.d(TAG, "Model already extracted: ${targetDir.absolutePath}")
            return targetDir.absolutePath
        }

        AppLogger.i(TAG, "Extracting model $modelName from assets…")
        copyAssetFolder(context, "models/$modelName", targetDir)
        AppLogger.i(TAG, "Model extracted to ${targetDir.absolutePath}")
        return targetDir.absolutePath
    }

    private fun copyAssetFolder(context: Context, assetPath: String, targetDir: File) {
        targetDir.mkdirs()
        val assetFiles = context.assets.list(assetPath) ?: return

        for (item in assetFiles) {
            val itemPath = "$assetPath/$item"
            val targetFile = File(targetDir, item)

            // Если это директория (содержит вложения) — рекурсия
            val subItems = context.assets.list(itemPath)
            if (subItems != null && subItems.isNotEmpty()) {
                copyAssetFolder(context, itemPath, targetFile)
            } else {
                // Это файл — копируем
                try {
                    context.assets.open(itemPath).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: IOException) {
                    AppLogger.e(TAG, "Failed to copy $itemPath: ${e.message}")
                }
            }
        }
    }
}

/**
 * Обёртка над Vosk Recognizer для двух режимов:
 *
 * 1. Wake word mode — grammar = [wake word], распознаёт только ключевую фразу.
 * 2. Command mode — grammar = null (full vocabulary), распознаёт произвольную речь.
 *
 * Vosk на Android работает через SpeechService, который читает AudioRecord
 * и вызывает слушателя. Мы используем этот же поток для обоих режимов,
 * пересоздавая Recognizer при переключении.
 */
class VoskManager(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResultCb: (String) -> Unit,
    private val onTimeout: () -> Unit = {}
) {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var sampleRate = 16000

    fun init(lang: String): Boolean {
        return try {
            val modelPath = VoskModelLoader.ensureModel(context, lang)
            AppLogger.i(TAG, "Loading Vosk model from: $modelPath")
            model = Model(modelPath)
            AppLogger.i(TAG, "Vosk model loaded successfully")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to init Vosk model: ${e.message}")
            false
        }
    }

    /**
     * Стартует прослушивание с грамматикой (wake word mode).
     * Распознаёт только слова из списка — быстро и энергоэффективно.
     */
    fun startWakeWord(wakeWord: String): Boolean {
        // Экранируем кавычки для безопасного JSON
        val escaped = wakeWord.replace("\\", "\\\\").replace("\"", "\\\"")
        return start(grammar = "[\"$escaped\"]")
    }

    /**
     * Стартует свободное распознавание (command mode).
     * Полный словарь — распознаёт любую речь.
     */
    fun startFreeForm(): Boolean {
        return start(grammar = null)
    }

    private fun start(grammar: String?): Boolean {
        return try {
            stop()

            val m = model ?: run {
                AppLogger.e(TAG, "Model not initialized — call init() first")
                return false
            }

            AppLogger.d(TAG, "Creating Recognizer (grammar=${grammar != null}, sampleRate=$sampleRate)")
            recognizer = if (grammar != null) {
                Recognizer(m, sampleRate.toFloat(), grammar)
            } else {
                Recognizer(m, sampleRate.toFloat())
            }

            AppLogger.d(TAG, "Creating SpeechService")
            speechService = SpeechService(recognizer, sampleRate.toFloat())

            AppLogger.d(TAG, "Starting listening…")
            speechService?.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    AppLogger.d(TAG, "onPartialResult: $hypothesis")
                    val text = parseResult(hypothesis, "partial")
                    if (text.isNotEmpty()) {
                        AppLogger.d(TAG, "Partial text: \"$text\"")
                        onPartialResult(text)
                    }
                }

                override fun onResult(hypothesis: String) {
                    AppLogger.d(TAG, "onResult: $hypothesis")
                    val text = parseResult(hypothesis, "text")
                    if (text.isNotEmpty()) {
                        AppLogger.i(TAG, "Final text: \"$text\"")
                        onFinalResultCb(text)
                    } else {
                        AppLogger.w(TAG, "onResult with empty text")
                    }
                }

                override fun onFinalResult(hypothesis: String) {
                    AppLogger.d(TAG, "onFinalResult: $hypothesis")
                    val text = parseResult(hypothesis, "text")
                    if (text.isNotEmpty()) {
                        AppLogger.i(TAG, "Final text: \"$text\"")
                        onFinalResultCb(text)
                    } else {
                        AppLogger.w(TAG, "onFinalResult with empty text")
                    }
                }

                override fun onError(exception: Exception) {
                    AppLogger.e(TAG, "Vosk error: ${exception.message}")
                }

                override fun onTimeout() {
                    AppLogger.w(TAG, "Vosk timeout — notifying listener")
                    onTimeout()
                }
            })

            AppLogger.i(TAG, "Listening started successfully")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start Vosk: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService = null
            recognizer?.close()
            recognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Vosk", e)
        }
    }

    fun close() {
        stop()
        model?.close()
        model = null
    }

    /**
     * Парсит JSON-ответ Vosk: {"partial": "..."} или {"text": "..."}
     */
    private fun parseResult(json: String, key: String): String {
        return try {
            val obj = JSONObject(json)
            obj.optString(key, "").trim().lowercase()
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "VoskManager"
    }
}