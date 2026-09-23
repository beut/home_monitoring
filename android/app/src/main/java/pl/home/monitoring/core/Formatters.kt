package pl.home.monitoring.core

import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {
    val PL: Locale = Locale("pl", "PL")
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    const val DASH = "—"

    private fun oneDecimal(v: Double): String = NumberFormat.getNumberInstance(PL).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(v)

    fun formatPower(w: Int?): String = when {
        w == null -> DASH
        w < 1000 -> "$w W"
        else -> "${oneDecimal(w / 1000.0)} kW"
    }

    fun formatKwh(kwh: Double?): String = if (kwh == null) DASH else "${oneDecimal(kwh)} kWh"

    fun formatTime(t: LocalTime): String = t.format(HHMM)
}
