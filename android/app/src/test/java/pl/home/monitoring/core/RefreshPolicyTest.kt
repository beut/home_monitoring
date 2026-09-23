package pl.home.monitoring.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.home.monitoring.domain.CallBudget
import pl.home.monitoring.domain.PvSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class RefreshPolicyTest {

    private val zone = ZoneId.of("Europe/Warsaw")
    private val lat = 52.0
    private val lon = 19.0

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun budget(now: Instant, used: Int = 0, limit: Int = 800) =
        CallBudget(monthKey = YearMonth.from(now.atZone(zone)), used = used, monthlyLimit = limit)

    private fun snapshot(fetchedAt: Instant?, summaryAt: Instant? = fetchedAt) = PvSnapshot(
        currentPowerW = 1000,
        powerSampleTime = "12:00",
        sampleDate = "2026-09-23",
        todayKwh = 5.0,
        monthKwh = 100.0,
        yearKwh = 1000.0,
        lifetimeKwh = 10000.0,
        fetchedAtEpochMs = fetchedAt?.toEpochMilli() ?: 0L,
        summaryFetchedAtEpochMs = summaryAt?.toEpochMilli(),
    )

    private fun decide(
        now: Instant,
        budget: CallBudget = budget(now),
        snapshot: PvSnapshot? = null,
        manual: Boolean = false,
    ) = RefreshPolicy.decide(now, zone, lat, lon, budget, snapshot, manual)

    @Test
    fun `a - night skips minutely and schedules next sunrise`() {
        val now = at("2026-09-23T01:12:00")
        val d = decide(now, snapshot = snapshot(at("2026-09-22T19:30:00")))
        assertFalse(d.fetchMinutely)
        assertFalse(d.fetchSummary)
        assertEquals(SkipReason.Night, d.skipReason)
        val sunrise = SunCalculator.sunTimes(LocalDate.parse("2026-09-23"), lat, lon, zone).sunrise.toInstant()
        assertEquals(sunrise, d.nextScheduledAt)
    }

    @Test
    fun `b - daylight without snapshot fetches both`() {
        val d = decide(at("2026-09-23T12:00:00"))
        assertTrue(d.fetchMinutely)
        assertTrue(d.fetchSummary)
        assertNull(d.skipReason)
    }

    @Test
    fun `c - automatic refresh within interval does not fetch`() {
        val now = at("2026-09-23T12:00:00")
        val d = decide(now, snapshot = snapshot(now.minus(Duration.ofMinutes(5))))
        assertFalse(d.fetchMinutely)
        assertEquals(SkipReason.TooSoon, d.skipReason)
        assertTrue(d.nextScheduledAt.isAfter(now))
    }

    @Test
    fun `d - interval never below 15 minutes`() {
        val now = at("2026-09-30T12:00:00")
        val interval = RefreshPolicy.interval(now, zone, lat, lon, budget(now, used = 0, limit = 1000))
        assertTrue(interval >= Duration.ofMinutes(15))
        val d = decide(now, budget(now, limit = 1000), snapshot(now.minus(Duration.ofMinutes(14))))
        assertFalse(d.fetchMinutely)
    }

    @Test
    fun `e - manual refresh younger than 10 minutes is too soon`() {
        val now = at("2026-09-23T12:00:00")
        val d = decide(now, snapshot = snapshot(now.minus(Duration.ofMinutes(9))), manual = true)
        assertFalse(d.fetchMinutely)
        assertEquals(SkipReason.TooSoon, d.skipReason)
        val ok = decide(now, snapshot = snapshot(now.minus(Duration.ofMinutes(11))), manual = true)
        assertTrue(ok.fetchMinutely)
    }

    @Test
    fun `f - exhausted budget blocks everything`() {
        val now = at("2026-09-23T12:00:00")
        val d = decide(now, budget(now, used = 800), snapshot = null, manual = true)
        assertFalse(d.fetchMinutely)
        assertFalse(d.fetchSummary)
        assertEquals(SkipReason.BudgetExhausted, d.skipReason)
    }

    @Test
    fun `g - throttled blocks until throttledUntil`() {
        val now = at("2026-09-23T12:00:00")
        val until = now.plus(Duration.ofMinutes(20))
        val d = decide(now, budget(now).copy(throttledUntil = until), snapshot = null, manual = true)
        assertFalse(d.fetchMinutely)
        assertFalse(d.fetchSummary)
        assertEquals(SkipReason.Throttled, d.skipReason)
        assertEquals(until, d.nextScheduledAt)
    }

    @Test
    fun `h - summary cadence`() {
        val noon = at("2026-09-23T12:00:00")
        // null -> fetch
        assertTrue(decide(noon, snapshot = snapshot(noon.minusSeconds(3600), summaryAt = null)).fetchSummary)
        // < 3 h during daylight -> no
        assertFalse(decide(noon, snapshot = snapshot(noon.minusSeconds(3600), noon.minusSeconds(2 * 3600))).fetchSummary)
        // >= 3 h -> yes
        assertTrue(decide(noon, snapshot = snapshot(noon.minusSeconds(3600), noon.minusSeconds(3 * 3600))).fetchSummary)
        // first check after sunset -> yes (summary last fetched before sunset)
        val evening = at("2026-09-23T20:00:00")
        val beforeSunset = at("2026-09-23T17:30:00")
        val night1 = decide(evening, snapshot = snapshot(beforeSunset, beforeSunset))
        assertTrue(night1.fetchSummary)
        assertFalse(night1.fetchMinutely)
        // not a second time after sunset
        val afterSunset = at("2026-09-23T19:05:00")
        assertFalse(decide(evening, snapshot = snapshot(beforeSunset, afterSunset)).fetchSummary)
        // opened next morning before sunrise, yesterday's post-sunset summary missing -> fetch once
        assertTrue(decide(at("2026-09-24T05:00:00"), snapshot = snapshot(beforeSunset, beforeSunset)).fetchSummary)
    }

    @Test
    fun `j - budget resets on month change`() {
        val now = at("2026-10-01T12:00:00")
        val old = CallBudget(monthKey = YearMonth.of(2026, 9), used = 800)
        val d = decide(now, old, snapshot = null)
        assertTrue(d.fetchMinutely)
    }

    @Test
    fun `k - reserved summary calls per day`() {
        assertEquals(7, RefreshPolicy.summarySlotsForDay(LocalDate.parse("2026-06-21"), zone, lat, lon))
        assertEquals(4, RefreshPolicy.summarySlotsForDay(LocalDate.parse("2026-12-21"), zone, lat, lon))
    }

    @Test
    fun `l - throttle backoff doubles and caps at 6h`() {
        assertEquals(Duration.ofMinutes(15), CallBudget.throttlePause(1))
        assertEquals(Duration.ofMinutes(30), CallBudget.throttlePause(2))
        assertEquals(Duration.ofMinutes(60), CallBudget.throttlePause(3))
        assertEquals(Duration.ofHours(6), CallBudget.throttlePause(10))
    }

    @Test
    fun `m - manual retry needs a minute since last attempt`() {
        val now = at("2026-09-23T12:00:00")
        val b = budget(now).copy(lastAttemptAt = now.minusSeconds(30))
        val d = decide(now, b, snapshot = null, manual = true)
        assertFalse(d.fetchMinutely)
        assertEquals(SkipReason.TooSoon, d.skipReason)
    }

    @Test
    fun `n - automatic refresh after a failed attempt waits the interval`() {
        val now = at("2026-09-23T12:00:00")
        val b = budget(now).copy(lastAttemptAt = now.minus(Duration.ofMinutes(2)))
        val d = decide(now, b, snapshot(now.minus(Duration.ofHours(2))))
        assertFalse(d.fetchMinutely)
    }

    // --- (i) quota guarantee: whole-month simulation, app visible 24 h, checked every minute ---

    private data class SimResult(val total: Int, val lastDayMinutely: Int)

    private fun simulateMonth(month: YearMonth): SimResult {
        var budget = CallBudget(monthKey = month)
        var snap: PvSnapshot? = null
        var t = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val lastDay = month.atEndOfMonth()
        var lastDayMinutely = 0
        while (t.isBefore(end)) {
            val d = RefreshPolicy.decide(t, zone, lat, lon, budget, snap, manual = false)
            var s = snap ?: snapshot(null, null).copy(fetchedAtEpochMs = 0L)
            if (d.fetchMinutely) {
                budget = budget.copy(used = budget.used + 1, lastAttemptAt = t)
                s = s.copy(fetchedAtEpochMs = t.toEpochMilli())
                if (t.atZone(zone).toLocalDate() == lastDay) lastDayMinutely++
            }
            if (d.fetchSummary) {
                budget = budget.copy(used = budget.used + 1, lastAttemptAt = t)
                s = s.copy(summaryFetchedAtEpochMs = t.toEpochMilli())
            }
            if (d.fetchMinutely || d.fetchSummary) snap = s
            t = t.plusSeconds(60)
        }
        return SimResult(budget.used, lastDayMinutely)
    }

    @Test
    fun `i - september stays within budget and still refreshes on the last day`() = assertMonth(YearMonth.of(2026, 9))

    @Test
    fun `i - june stays within budget and still refreshes on the last day`() = assertMonth(YearMonth.of(2026, 6))

    @Test
    fun `i - december stays within budget and still refreshes on the last day`() = assertMonth(YearMonth.of(2026, 12))

    private fun assertMonth(month: YearMonth) {
        val r = simulateMonth(month)
        assertTrue("$month used ${r.total}", r.total <= 800)
        assertTrue("$month used only ${r.total}, budget badly under-used", r.total >= 600)
        assertTrue("$month last day minutely ${r.lastDayMinutely}", r.lastDayMinutely >= 10)
    }
}
