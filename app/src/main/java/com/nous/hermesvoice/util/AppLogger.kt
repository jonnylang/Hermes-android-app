package com.nous.hermesvoice.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app логгер с кольцевым буфером.
 * Хранит последние [maxEntries] записей в памяти.
 * Можно читать из UI для отладки.
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val maxEntries = 500

    private val buffer = ArrayDeque<LogEntry>(maxEntries)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String
    )

    /**
     * Добавить запись в лог.
     */
    @Synchronized
    fun log(level: String, tag: String, message: String) {
        if (buffer.size >= maxEntries) {
            buffer.removeFirst()
        }
        buffer.addLast(
            LogEntry(
                timestamp = dateFormat.format(Date()),
                level = level,
                tag = tag,
                message = message
            )
        )
        // Дублируем в Android Log
        when (level) {
            "V" -> Log.v(tag, message)
            "D" -> Log.d(tag, message)
            "I" -> Log.i(tag, message)
            "W" -> Log.w(tag, message)
            "E" -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
    }

    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)

    /**
     * Получить копию текущего буфера.
     */
    @Synchronized
    fun getEntries(): List<LogEntry> = buffer.toList()

    /**
     * Очистить лог.
     */
    @Synchronized
    fun clear() = buffer.clear()
}
