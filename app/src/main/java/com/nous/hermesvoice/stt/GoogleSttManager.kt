package com.nous.hermesvoice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nous.hermesvoice.util.AppLogger
import java.util.Locale

/**
 * STT через встроенный Google Speech Recognizer.
 * Гораздо точнее Vosk small, требует интернет.
 */
class GoogleSttManager(
    private val context: Context
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onTimeout: (() -> Unit)? = null

    fun init(lang: String): Boolean {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            AppLogger.i(TAG, "Google SpeechRecognizer available")
            return true
        }
        AppLogger.e(TAG, "Google SpeechRecognizer not available")
        return false
    }

    fun startListening(lang: String) {
        stop()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        isListening = true

        val locale = if (lang == "ru") Locale("ru-RU") else Locale.US

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                AppLogger.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                AppLogger.d(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                AppLogger.d(TAG, "End of speech")
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No permission"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error: $error"
                }
                AppLogger.w(TAG, "Speech error: $errorMsg")
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onTimeout?.invoke()
                } else {
                    onError?.invoke(errorMsg)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (text != null && text.isNotBlank()) {
                    AppLogger.i(TAG, "Recognized: \"$text\"")
                    onResult?.invoke(text.lowercase())
                } else {
                    AppLogger.w(TAG, "Empty result")
                    onTimeout?.invoke()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (text != null && text.isNotBlank()) {
                    AppLogger.d(TAG, "Partial: \"$text\"")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
        AppLogger.i(TAG, "Google STT started (lang=$lang)")
    }

    fun stop() {
        isListening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    fun close() {
        stop()
    }

    companion object {
        private const val TAG = "GoogleSttManager"
    }
}
