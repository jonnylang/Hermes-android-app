package com.nous.hermesvoice.vosk

import android.content.Context
import android.util.Log
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
            Log.d(TAG, "Model already extracted: ${targetDir.absolutePath}")
            return targetDir.absolutePath
        }

        Log.i(TAG, "Extracting model $modelName from assets…")
        copyAssetFolder(context, "models/$modelName", targetDir)
        Log.i(TAG, "Model extracted to ${targetDir.absolutePath}")
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
                    Log.e(TAG, "Failed to copy $itemPath", e)
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
    private val onFinalResult: (String) -> Unit,
    private val onTimeout: () -> Unit = {}
) {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var sampleRate = 16000

    fun init(lang: String): Boolean {
        return try {
            val modelPath = VoskModelLoader.ensureModel(context, lang)
            model = Model(modelPath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Vosk model", e)
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
                Log.e(TAG, "Model not initialized — call init() first")
                return false
            }

            recognizer = if (grammar != null) {
                Recognizer(m, sampleRate.toFloat(), grammar)
            } else {
                Recognizer(m, sampleRate.toFloat())
            }

            speechService = SpeechService(recognizer, sampleRate.toFloat())

            speechService?.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    val text = parseResult(hypothesis, "partial")
                    if (text.isNotEmpty()) onPartialResult(text)
                }

                override fun onResult(hypothesis: String) {
                    val text = parseResult(hypothesis, "text")
                    if (text.isNotEmpty()) onFinalResult(text)
                }

                override fun onFinalResult(hypothesis: String) {
                    val text = parseResult(hypothesis, "text")
                    if (text.isNotEmpty()) onFinalResult(text)
                }

                override fun onError(exception: Exception) {
                    Log.e(TAG, "Vosk error", exception)
                }

                override fun onTimeout() {
                    Log.d(TAG, "Vosk timeout — notifying listener")
                    onTimeout()
                }
            })

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk", e)
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