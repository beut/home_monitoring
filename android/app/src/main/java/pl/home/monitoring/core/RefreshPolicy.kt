package pl.home.monitoring.core

import pl.home.monitoring.domain.CallBudget
import pl.home.monitoring.domain.PvSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class SkipReason { Night, TooSoon, BudgetExhausted, Throttled }

data class RefreshDecision(
    val fetchMinutely: Boolean,
    val fetchSummary: Boolean,
    /** Why minutely data is not fetched now; null when it is. */
    val skipReason: SkipReason?,
    val nextScheduledAt: Instant,
    val interval: Duration,
)

/**
 * Budget- and daylight-driven refresh rules (data-model.md, research R3).
 * Pure: no I/O, no clock — everything comes in as parameters.
 */
object RefreshPolicy {
    val MIN_INTERVAL: Duration = Duration.ofMinutes(15)
    val MANUAL_MIN_AGE: Duration = Duration.ofMinutes(10)
    val MANUAL_MIN_SINCE_ATTEMPT: Duration = Duration.ofMinutes(1)
    val SUMMARY_EVERY: Duration = Duration.ofHours(3)
    val DAYLIGHT_GRACE: Duration = Duration.ofMinutes(15)

    fun decide(
        now: Instant,
        zone: ZoneId,
        lat: Double,
        lon: Double,
        budget: CallBudget,
        snapshot: PvSnapshot?,
        manual: Boolean,
    ): RefreshDecision {
        val today = now.atZone(zone).toLocalDate()
        val b = budget.forMonth(YearMonth.from(today))
        val sun = SunCalculator.sunTimes(today, lat, lon, zone)
        val sunrise = sun.sunrise.toInstant()
        val sunsetEnd = sun.sunset.toInstant().plus(DAYLIGHT_GRACE)
        val daylight = !now.isBefore(sunrise) && !now.isAfter(sunsetEnd)
        val interval = interval(now, zone, lat, lon, b)

        val nextSunrise = if (now.isBefore(sunrise)) sunrise
        else SunCalculator.sunTimes(today.plusDays(1), lat, lon, zone).sunrise.toInstant()

        if (b.used >= b.monthlyLimit) {
            val firstOfNextMonth = YearMonth.from(today).plusMonths(1).atDay(1)
            val next = SunCalculator.sunTimes(firstOfNextMonth, lat, lon, zone).sunrise.toInstant()
            return RefreshDecision(false, false, SkipReason.BudgetExhausted, next, interval)
        }
        b.throttledUntil?.let { until ->
            if (now.isBefore(until)) return RefreshDecision(false, false, SkipReason.Throttled, until, interval)
        }

        // Last attempt, successful or not — failures must not cause retries every minute.
        val lastSuccess = snapshot?.fetchedAt?.takeIf { snapshot.fetchedAtEpochMs > 0 }
        val lastAttempt = listOfNotNull(lastSuccess, b.lastAttemptAt).maxOrNull()

        val summary = shouldFetchSummary(now, daylight, sun.sunset.toInstant(), today, zone, lat, lon, snapshot)

        if (!daylight) {
            return RefreshDecision(false, summary, SkipReason.Night, nextSunrise, interval)
        }

        val due = if (manual) {
            (lastSuccess == null || Duration.between(lastSuccess, now) >= MANUAL_MIN_AGE) &&
                (b.lastAttemptAt == null || Duration.between(b.lastAttemptAt, now) >= MANUAL_MIN_SINCE_ATTEMPT)
        } else {
            lastAttempt == null || Duration.between(lastAttempt, now) >= interval
        }

        val summaryAllowed = summary && (!due || b.used + 1 < b.monthlyLimit)
        return if (due) {
            RefreshDecision(true, summaryAllowed, null, now.plus(interval), interval)
        } else {
            val next = (lastAttempt ?: now).plus(interval).let { if (it.isAfter(now)) it else now.plus(interval) }
            RefreshDecision(false, summaryAllowed, SkipReason.TooSoon, next, interval)
        }
    }

    private fun shouldFetchSummary(
        now: Instant,
        daylight: Boolean,
        sunsetToday: Instant,
        today: LocalDate,
        zone: ZoneId,
        lat: Double,
        lon: Double,
        snapshot: PvSnapshot?,
    ): Boolean {
        val last = snapshot?.summaryFetchedAt ?: return true
        if (daylight) return Duration.between(last, now) >= SUMMARY_EVERY
        // Night: once after the most recent sunset.
        val lastSunset = if (now.isAfter(sunsetToday)) sunsetToday
        else SunCalculator.sunTimes(today.minusDays(1), lat, lon, zone).sunset.toInstant()
        return last.isBefore(lastSunset)
    }

    /**
     * `max(15 min, remainingDaylightMinutesThisMonth / max(1, remainingBudget − reservedSummaryCalls))`.
     */
    fun interval(now: Instant, zone: ZoneId, lat: Double, lon: Double, budget: CallBudget): Duration {
        val today = now.atZone(zone).toLocalDate()
        val b = budget.forMonth(YearMonth.from(today))
        val month = YearMonth.from(today)
        var daylightMinutes = 0L
        var reserved = 0
        var day = today
        while (!day.isAfter(month.atEndOfMonth())) {
            val sun = SunCalculator.sunTimes(day, lat, lon, zone)
            if (day == today) {
                val start = maxOf(now, sun.sunrise.toInstant())
                val end = sun.sunset.toInstant()
                val left = if (end.isAfter(start)) Duration.between(start, end).toMinutes() else 0L
                daylightMinutes += left
                reserved += if (now.isAfter(end)) 0 else slotsForDaylightMinutes(left)
            } else {
                daylightMinutes += Duration.between(sun.sunrise, sun.sunset).toMinutes()
                reserved += summarySlotsForDay(day, zone, lat, lon)
            }
            day = day.plusDays(1)
        }
        val minutelyBudget = (b.remaining - reserved).coerceAtLeast(1)
        val minutes = daylightMinutes / minutelyBudget
        return maxOf(MIN_INTERVAL, Duration.ofMinutes(minutes))
    }

    /**
     * Summary calls a fully-visible day needs: one every 3 h across daylight (plus the 15-min grace)
     * and one after sunset — `ceil(daylightHours / 3) + 1` in the common case, about 4 in December and 7 in June.
     */
    fun summarySlotsForDay(day: LocalDate, zone: ZoneId, lat: Double, lon: Double): Int {
        val sun = SunCalculator.sunTimes(day, lat, lon, zone)
        return slotsForDaylightMinutes(Duration.between(sun.sunrise, sun.sunset).toMinutes())
    }

    private fun slotsForDaylightMinutes(minutes: Long): Int =
        ((minutes + DAYLIGHT_GRACE.toMinutes()) / SUMMARY_EVERY.toMinutes()).toInt() + 2
}
