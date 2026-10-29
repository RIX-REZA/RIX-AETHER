package com.rix.vpn.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log shared by the service, the JNI callbacks and the log screen.
 *
 * Deliberately not persisted: the core already writes its own config/log files,
 * and a growing on-disk log on a phone is a liability mid-tunnel.
 */
object LogBus {

    private const val MAX_LINES = 500

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${time.format(Date())} [$tag] $message"
        val current = _lines.value
        val next = when {
            current.size < MAX_LINES -> current + line
            else -> current.subList(current.size - MAX_LINES + 1, current.size) + line
        }
        _lines.value = next
    }

    fun i(tag: String, message: String) = add(tag, message)

    fun e(tag: String, message: String) = add(tag, "خطا: $message")

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    fun dump(): String = _lines.value.joinToString("\n")
}