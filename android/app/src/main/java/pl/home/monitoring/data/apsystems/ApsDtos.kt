package pl.home.monitoring.data.apsystems

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalTime

val ApsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class ApsResponse<T>(val code: Int, val data: T? = null)

@Serializable
data class DetailsData(
    val ecu: List<String> = emptyList(),
    val timezone: String? = null,
    val capacity: String? = null,
)

@Serializable
data class SummaryData(
    val today: String? = null,
    val month: String? = null,
    val year: String? = null,
    val lifetime: String? = null,
)

@Serializable
data class MinutelyData(
    val today: String? = null,
    val time: List<String> = emptyList(),
    val power: List<Int?> = emptyList(),
)

data class Totals(val todayKwh: Double?, val monthKwh: Double?, val yearKwh: Double?, val lifetimeKwh: Double?)

fun String?.kwhOrNull(): Double? = this?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

fun SummaryData.toTotals() = Totals(today.kwhOrNull(), month.kwhOrNull(), year.kwhOrNull(), lifetime.kwhOrNull())

/** The most recent (time, power W) sample; negative power is clamped to 0. */
fun MinutelyData.lastSample(): Pair<LocalTime, Int>? {
    val n = minOf(time.size, power.size)
    for (i in n - 1 downTo 0) {
        val t = runCatching { LocalTime.parse(time[i]) }.getOrNull() ?: continue
        val p = power[i] ?: continue
        return t to p.coerceAtLeast(0)
    }
    return null
}
