package pl.home.monitoring.data.apsystems

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.home.monitoring.data.security.StringCipher
import pl.home.monitoring.data.store.CallBudgetStore
import pl.home.monitoring.data.store.CredentialsStore
import pl.home.monitoring.data.store.LocationStore
import pl.home.monitoring.data.store.SnapshotStore
import pl.home.monitoring.data.store.SystemInfoStore
import pl.home.monitoring.domain.ApsCredentials
import pl.home.monitoring.domain.ApsSystemInfo
import pl.home.monitoring.domain.Freshness
import pl.home.monitoring.domain.PvSnapshot
import pl.home.monitoring.domain.SectionState
import pl.home.monitoring.domain.StaleReason
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PvRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Warsaw")
    private lateinit var scope: CoroutineScope
    private lateinit var clock: MutableClock
    private lateinit var api: FakeApi
    private lateinit var creds: CredentialsStore
    private lateinit var info: SystemInfoStore
    private lateinit var snaps: SnapshotStore
    private lateinit var budget: CallBudgetStore
    private lateinit var repo: PvRepository
    private val credentials = ApsCredentials.of("id", "secret", "SID")

    class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    object PlainCipher : StringCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    /** Scripted API: each call pops the next queued result (or a default), and counts against the budget. */
    inner class FakeApi : ApsApi {
        val details = ArrayDeque<ApiResult<DetailsData>>()
        val summaries = ArrayDeque<ApiResult<SummaryData>>()
        val minutelies = ArrayDeque<ApiResult<MinutelyData>>()
        val minutelyEcus = mutableListOf<String>()
        var calls = 0

        private suspend fun count() {
            calls++
            budget.recordCall(clock.now)
        }

        override suspend fun details(creds: ApsCredentials): ApiResult<DetailsData> {
            count()
            return details.removeFirstOrNull() ?: ApiResult.Success(DetailsData(listOf("ECU1"), "Europe/Warsaw", "9.96"))
        }

        override suspend fun summary(creds: ApsCredentials): ApiResult<SummaryData> {
            count()
            return summaries.removeFirstOrNull() ?: ApiResult.Success(SummaryData(null, "600.5", "6000.1", "28000.9"))
        }

        override suspend fun minutely(creds: ApsCredentials, ecuId: String, date: LocalDate): ApiResult<MinutelyData> {
            count()
            minutelyEcus += ecuId
            return minutelies.removeFirstOrNull()
                ?: ApiResult.Success(MinutelyData("12.3", listOf("11:50", "11:55"), listOf(1500, 1600)))
        }
    }

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        fun ds(name: String) = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("$name.preferences_pb") }
        clock = MutableClock(at("2026-09-23T12:00:00"), zone)
        creds = CredentialsStore(ds("credentials"), PlainCipher)
        info = SystemInfoStore(ds("settings"))
        snaps = SnapshotStore(ds("snapshot"))
        budget = CallBudgetStore(ds("budget")) { zone }
        api = FakeApi()
        repo = PvRepository(api, creds, info, snaps, budget, LocationStore(ds("location")), clock)
        creds.save(credentials)
        info.save(ApsSystemInfo("ECU1", zone, 9.96))
    }

    @After
    fun tearDown() = scope.cancel()

    private fun snapshot(fetchedAt: Instant, summaryAt: Instant? = fetchedAt, date: String = "2026-09-23") = PvSnapshot(
        currentPowerW = 900, powerSampleTime = "11:00", sampleDate = date, todayKwh = 4.0,
        monthKwh = 500.0, yearKwh = 5000.0, lifetimeKwh = 27000.0,
        fetchedAtEpochMs = fetchedAt.toEpochMilli(), summaryFetchedAtEpochMs = summaryAt?.toEpochMilli(),
    )

    // ---------- US1 ----------

    @Test
    fun `a - daylight without snapshot fetches minutely and summary`() = runBlocking {
        assertEquals(RefreshOutcome.Fetched, repo.refresh(manual = false))
        assertEquals(2, api.calls)
        val s = repo.state.value as SectionState.Data
        assertEquals(Freshness.Fresh, s.freshness)
        assertEquals(1600, s.snapshot.currentPowerW)
        assertEquals("11:55", s.snapshot.powerSampleTime)
        assertEquals(12.3, s.snapshot.todayKwh!!, 1e-9)
        assertEquals(600.5, s.snapshot.monthKwh!!, 1e-9)
        assertEquals(28000.9, s.snapshot.lifetimeKwh!!, 1e-9)
        assertEquals(s.snapshot, snaps.current())
    }

    @Test
    fun `b - second automatic refresh within interval makes no call`() = runBlocking {
        repo.refresh(manual = false)
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        val o = repo.refresh(manual = false)
        assertTrue(o is RefreshOutcome.Skipped)
        assertEquals(2, api.calls)
    }

    @Test
    fun `c - no data during the day is NoDataYet with zero today`() = runBlocking {
        api.minutelies += ApiResult.NoData
        repo.refresh(manual = false)
        val s = repo.state.value as SectionState.NoDataYet
        assertEquals(0.0, s.snapshot!!.todayKwh!!, 1e-9)
        assertEquals(0, s.snapshot!!.currentPowerW)
    }

    @Test
    fun `d - night with snapshot is stale night and makes no minutely call`() = runBlocking {
        clock.now = at("2026-09-23T22:00:00")
        snaps.save(snapshot(at("2026-09-23T18:30:00"), summaryAt = at("2026-09-23T19:00:00")))
        val o = repo.refresh(manual = false)
        assertTrue(o is RefreshOutcome.Skipped)
        assertEquals(0, api.calls)
        val s = repo.state.value as SectionState.Data
        assertTrue((s.freshness as Freshness.Stale).reason is StaleReason.Night)
    }

    @Test
    fun `e - summary not due keeps previous totals`() = runBlocking {
        snaps.save(snapshot(at("2026-09-23T11:00:00"), summaryAt = at("2026-09-23T11:00:00")))
        repo.refresh(manual = false)
        assertEquals(1, api.calls)
        val s = (repo.state.value as SectionState.Data).snapshot
        assertEquals(500.0, s.monthKwh!!, 1e-9)
        assertEquals(1600, s.currentPowerW)
    }

    @Test
    fun `f - snapshot from yesterday shows no today values until a new sample`() = runBlocking {
        clock.now = at("2026-09-24T05:00:00") // before sunrise, summary already fetched after yesterday's sunset
        snaps.save(snapshot(at("2026-09-23T18:30:00"), summaryAt = at("2026-09-23T19:00:00"), date = "2026-09-23"))
        repo.refresh(manual = false)
        val s = (repo.state.value as SectionState.Data).snapshot
        assertNull(s.todayKwh)
        assertNull(s.currentPowerW)
        assertEquals(500.0, s.monthKwh!!, 1e-9)
    }

    // ---------- US3 ----------

    @Test
    fun `g - offline with snapshot is stale offline and keeps values`() = runBlocking {
        val old = snapshot(at("2026-09-23T11:00:00"), summaryAt = at("2026-09-23T11:00:00"))
        snaps.save(old)
        api.minutelies += ApiResult.Offline
        assertEquals(RefreshOutcome.Failed, repo.refresh(manual = false))
        val s = repo.state.value as SectionState.Data
        assertEquals(Freshness.Stale(StaleReason.Offline), s.freshness)
        assertEquals(old, s.snapshot)
        assertEquals(old, snaps.current())
    }

    @Test
    fun `h - every throttle code backs off without blocking the month`() = runBlocking {
        for ((i, code) in listOf(2005, 7001, 7002, 7003).withIndex()) {
            snaps.save(snapshot(clock.now.minus(Duration.ofHours(1)), summaryAt = clock.now))
            api.minutelies += ApiResult.Throttled(code)
            repo.refresh(manual = false)
            val s = repo.state.value as SectionState.Data
            assertEquals(Freshness.Stale(StaleReason.Throttled), s.freshness)
            val b = budget.current()
            assertEquals(i + 1, b.throttleStreak)
            assertTrue(b.throttledUntil!!.isAfter(clock.now))
            assertTrue(Duration.between(clock.now, b.throttledUntil) <= Duration.ofHours(6))
            // Before throttledUntil: no call at all.
            val calls = api.calls
            clock.now = b.throttledUntil!!.minusSeconds(60)
            repo.refresh(manual = false)
            assertEquals(calls, api.calls)
            clock.now = b.throttledUntil!!.plusSeconds(1)
        }
    }

    @Test
    fun `i - auth error keeps snapshot and stops calling until credentials change`() = runBlocking {
        snaps.save(snapshot(at("2026-09-23T11:00:00"), summaryAt = at("2026-09-23T11:00:00")))
        api.minutelies += ApiResult.AuthError(4000)
        api.details += ApiResult.AuthError(4000)
        repo.refresh(manual = false)
        assertEquals(SectionState.AuthError, repo.state.value)
        assertTrue(snaps.current() != null)
        val calls = api.calls
        clock.now = clock.now.plus(Duration.ofHours(1))
        repo.refresh(manual = true)
        assertEquals(calls, api.calls)
        // New credentials unblock.
        creds.save(ApsCredentials.of("id", "fixed", "SID"))
        repo.refresh(manual = true)
        assertTrue(api.calls > calls)
    }

    @Test
    fun `j - error without snapshot is Error state then Data after success`() = runBlocking {
        api.minutelies += ApiResult.Offline
        repo.refresh(manual = false)
        assertEquals(SectionState.Error(StaleReason.Offline), repo.state.value)

        clock.now = clock.now.plus(Duration.ofHours(1))
        api.minutelies += ApiResult.ServiceError(500)
        repo.refresh(manual = false)
        assertEquals(SectionState.Error(StaleReason.ServiceError(500)), repo.state.value)

        clock.now = clock.now.plus(Duration.ofHours(1))
        repo.refresh(manual = false)
        assertEquals(Freshness.Fresh, (repo.state.value as SectionState.Data).freshness)
    }

    @Test
    fun `k - stale ecu id is recovered with at most two extra calls`() = runBlocking {
        snaps.save(snapshot(at("2026-09-23T11:00:00"), summaryAt = at("2026-09-23T11:00:00")))
        api.minutelies += ApiResult.AuthError(4001)
        api.details += ApiResult.Success(DetailsData(listOf("ECU2"), "Europe/Warsaw", "9.96"))
        repo.refresh(manual = false)
        assertEquals(listOf("ECU1", "ECU2"), api.minutelyEcus)
        assertEquals(3, api.calls)
        assertEquals("ECU2", info.current()!!.ecuId)
        assertEquals(Freshness.Fresh, (repo.state.value as SectionState.Data).freshness)
    }

    @Test
    fun `l - failed attempt is not retried by the next automatic refresh`() = runBlocking {
        snaps.save(snapshot(at("2026-09-23T10:00:00"), summaryAt = at("2026-09-23T11:00:00")))
        api.minutelies += ApiResult.Offline
        repo.refresh(manual = false)
        val calls = api.calls
        clock.now = clock.now.plus(Duration.ofMinutes(2))
        repo.refresh(manual = false)
        assertEquals(calls, api.calls)
    }

    @Test
    fun `cached snapshot is shown before any network call`() = runBlocking {
        val old = snapshot(at("2026-09-23T11:00:00"))
        snaps.save(old)
        repo.loadCached()
        assertEquals(SectionState.Data(old, Freshness.Fresh), repo.state.value)
        assertEquals(0, api.calls)
    }

    @Test
    fun `not connected without credentials`() = runBlocking {
        creds.clear()
        assertEquals(RefreshOutcome.NotConnected, repo.refresh(manual = false))
        assertEquals(SectionState.NotConnected, repo.state.value)
        assertEquals(0, api.calls)
    }
}
