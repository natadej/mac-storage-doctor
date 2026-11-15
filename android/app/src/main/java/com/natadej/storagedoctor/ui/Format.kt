package com.natadej.storagedoctor.ui

import androidx.compose.ui.graphics.Color
import com.natadej.storagedoctor.core.CATEGORIES
import com.natadej.storagedoctor.core.Safety
import java.text.DateFormat
import java.util.Date

fun fmtBytes(b: Long): String {
    val v = b.toDouble()
    return when {
        b >= 1_000_000_000_000 -> "%.2f TB".format(v / 1e12)
        b >= 1_000_000_000 -> "%.1f GB".format(v / 1e9)
        b >= 1_000_000 -> "%.0f MB".format(v / 1e6)
        b >= 1_000 -> "%.0f KB".format(v / 1e3)
        else -> "$b B"
    }
}

fun fmtTime(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

fun catLabel(id: String) = CATEGORIES.firstOrNull { it.id == id }?.label ?: id
fun catColor(id: String) = Color(CATEGORIES.firstOrNull { it.id == id }?.color ?: 0xFF94A3B8)

fun safetyColor(s: Safety) = when (s) {
    Safety.SAFE -> Color(0xFF22C55E)
    Safety.REVIEW -> Color(0xFFF59E0B)
    Safety.CAREFUL -> Color(0xFFEF4444)
}

fun safetyLabel(s: Safety) = when (s) {
    Safety.SAFE -> "safe"
    Safety.REVIEW -> "review"
    Safety.CAREFUL -> "careful"
}
