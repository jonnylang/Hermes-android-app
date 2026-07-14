package com.nous.hermesvoice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Обёртка над Android TextToSpeech.
 *
 * Озвучивает текст по предложениям — чтобы начать говорить как только
 * первая фраза готова (в streaming-режиме), не дожидаясь полного ответа.
 */
class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var activeUtterances = 0
    private var currentSentence = StringBuilder()

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
                    Log.i(TAG, "TTS ready=$ready, lang=$locale")

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
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    /**
     * Озвучивает полный текст сразу.
     */
    fun speak(text: String) {
        if (!ready || text.isBlank()) {
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
     * Streaming-озвучка: принимает токены по мере поступления,
     * озвучивает по готовности предложения (разделитель — . ! ? …).
     */
    fun feedToken(token: String) {
        if (!ready) return
        synchronized(this) {
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
    }

    /**
     * Завершает streaming — озвучивает остаток текста.
     */
    fun flush() {
        synchronized(this) {
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
        private const val isRussian = true // TODO: настраивать из Settings
    }
}