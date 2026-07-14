package com.nous.hermesvoice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.nous.hermesvoice.util.AppLogger
import java.util.Locale
import java.util.UUID

/**
 * Обёртка над Android TextToSpeech.
 *
 * Озвучивает текст по предложениям — чтобы начать говорить как только
 * первая фраза готова (в streaming-режиме), не дожидаясь полного ответа.
 *
 * Буферизирует токены, если TTS ещё не инициализировался.
 */
class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var activeUtterances = 0
    private var currentSentence = StringBuilder()
    // Буфер на случай, если TTS ещё не готов
    private val pendingTokens = mutableListOf<String>()

    var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = this.tts
                if (tts != null) {
                    val locale = if (isRussian) Locale("ru") else Locale.US
                    val result = tts.setLanguage(locale)
                    ready = result != TextToSpeech.LANG_MISSING_DATA &&
                            result != TextToSpeech.LANG_NOT_SUPPORTED
                    AppLogger.i(TAG, "TTS ready=$ready, lang=$locale")

                    // Воспроизводим накопленные токены
                    if (ready && pendingTokens.isNotEmpty()) {
                        AppLogger.i(TAG, "Playing ${pendingTokens.size} buffered tokens")
                        synchronized(this) {
                            for (token in pendingTokens) {
                                processToken(token)
                            }
                            pendingTokens.clear()
                        }
                    }

                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            synchronized(this@TtsManager) {
                                activeUtterances--
                                if (activeUtterances <= 0) {
                                    activeUtterances = 0
                                    currentSentence.clear()
                                    this@TtsManager.onDone?.invoke()
                                }
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            synchronized(this@TtsManager) {
                                activeUtterances--
                                if (activeUtterances <= 0) {
                                    activeUtterances = 0
                                    currentSentence.clear()
                                    this@TtsManager.onDone?.invoke()
                                }
                            }
                        }
                    })
                }
            } else {
                AppLogger.e(TAG, "TTS init failed: $status")
            }
        }
    }

    /**
     * Озвучивает полный текст сразу.
     */
    fun speak(text: String) {
        if (!ready || text.isBlank()) {
            if (!ready) AppLogger.w(TAG, "TTS not ready yet, speak() ignored")
            onDone?.invoke()
            return
        }
        synchronized(this) {
            activeUtterances = 0
            currentSentence.clear()
            activeUtterances++
        }
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Streaming-озвучка: принимает токены по мере поступления.
     * Если TTS ещё не готов — буферизирует.
     */
    fun feedToken(token: String) {
        if (!ready) {
            // Буферизируем до готовности
            synchronized(this) {
                pendingTokens.add(token)
            }
            return
        }
        synchronized(this) {
            processToken(token)
        }
    }

    private fun processToken(token: String) {
        currentSentence.append(token)
        val text = currentSentence.toString()

        // Проверяем — заканчивается ли на разделитель предложения
        val sentenceEnd = text.lastIndexOfAny(charArrayOf('.', '!', '?', '…', '\n'))
        if (sentenceEnd >= 0) {
            val sentence = text.substring(0, sentenceEnd + 1).trim()
            val rest = text.substring(sentenceEnd + 1)
            currentSentence = StringBuilder(rest)

            if (sentence.isNotEmpty()) {
                activeUtterances++
                val utteranceId = UUID.randomUUID().toString()
                tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        }
    }

    /**
     * Завершает streaming — озвучивает остаток текста.
     */
    fun flush() {
        synchronized(this) {
            // Если ещё не готов — просто очищаем буфер
            if (!ready) {
                AppLogger.w(TAG, "TTS not ready, flushing pending buffer")
                pendingTokens.clear()
                currentSentence.clear()
                onDone?.invoke()
                return
            }
            val rest = currentSentence.toString().trim()
            currentSentence.clear()
            if (rest.isNotEmpty()) {
                activeUtterances++
                val utteranceId = UUID.randomUUID().toString()
                tts?.speak(rest, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            activeUtterances = 0
            currentSentence.clear()
            pendingTokens.clear()
        }
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "TtsManager"
        private const val isRussian = true
    }
}
