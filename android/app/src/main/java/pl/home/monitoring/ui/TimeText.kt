package pl.home.monitoring.ui

import pl.home.monitoring.core.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d.MM HH:mm")

/** "HH:mm" for today, "d.MM HH:mm" otherwise, in the phone's time zone. */
fun formatWhen(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val local = instant.atZone(zone)
    return if (local.toLocalDate() == today) Formatters.formatTime(local.toLocalTime()) else local.format(DAY_TIME)
}
