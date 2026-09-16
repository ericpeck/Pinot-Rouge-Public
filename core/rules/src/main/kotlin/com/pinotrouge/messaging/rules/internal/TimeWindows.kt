package com.pinotrouge.messaging.rules.internal

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parses and evaluates [TimeOp.BETWEEN] windows.
 *
 * Seed data uses an en dash with spaces (`22:00 – 07:00`). Also accept
 * hyphen-minus, en dash, and em dash, with or without surrounding spaces.
 * When start > end the window wraps past midnight.
 */
internal object TimeWindows {

    private val SEPARATOR = Regex("""\s*[-–—]\s*""")
    private val TIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("H:mm"),
        DateTimeFormatter.ofPattern("HH:mm"),
        DateTimeFormatter.ofPattern("H:mm:ss"),
        DateTimeFormatter.ofPattern("HH:mm:ss"),
    )

    data class Window(val start: LocalTime, val end: LocalTime)

    fun parse(value: String): Window? {
        val parts = value.trim().split(SEPARATOR)
        if (parts.size != 2) return null
        val start = parseTime(parts[0].trim()) ?: return null
        val end = parseTime(parts[1].trim()) ?: return null
        return Window(start, end)
    }

    /**
     * Inclusive on both ends. When [Window.start] is after [Window.end], the
     * range wraps midnight: `22:00 – 07:00` includes 23:30 and 02:00, excludes noon.
     */
    fun contains(window: Window, time: LocalTime): Boolean {
        return if (window.start <= window.end) {
            !time.isBefore(window.start) && !time.isAfter(window.end)
        } else {
            !time.isBefore(window.start) || !time.isAfter(window.end)
        }
    }

    fun isInWindow(value: String, time: LocalTime): Boolean {
        val window = parse(value) ?: return false
        return contains(window, time)
    }

    private fun parseTime(raw: String): LocalTime? {
        for (formatter in TIME_FORMATS) {
            try {
                return LocalTime.parse(raw, formatter)
            } catch (_: DateTimeParseException) {
                // try next format
            }
        }
        return null
    }
}
