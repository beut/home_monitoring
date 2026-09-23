package pl.home.monitoring.data.apsystems

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.home.monitoring.core.RefreshDecision
import pl.home.monitoring.core.RefreshPolicy
import pl.home.monitoring.core.SkipReason
import pl.home.monitoring.data.store.CallBudgetStore
import pl.home.monitoring.data.store.CredentialsStore
import pl.home.monitoring.data.store.LocationStore
import pl.home.monitoring.data.store.SnapshotStore
import pl.home.monitoring.data.store.SystemInfoStore
import pl.home.monitoring.data.store.zoneOrDefault
import pl.home.monitoring.domain.ApsCredentials
import pl.home.monitoring.domain.ApsSystemInfo
import pl.home.monitoring.domain.Freshness
import pl.home.monitoring.domain.PvSnapshot
import pl.home.monitoring.domain.SectionState
import pl.home.monitoring.domain.StaleReason
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

sealed interface RefreshOutcome {
    data object Fetched : RefreshOutcome
    data class Skipped(val reason: SkipReason, val nextAt: Instant, val lastFetchedAt: Instant?) : RefreshOutcome
    data object Failed : RefreshOutcome
    data object NotConnected : RefreshOutcome
}

/** PV source: combines RefreshPolicy, the call budget, the API and the cached snapshot into one [state]. */
class PvRepository(
    private val api: ApsApi,
    private val credentials: CredentialsStore,
    private val systemInfo: SystemInfoStore,
    private val snapshots: SnapshotStore,
    private val budget: CallBudgetStore,
    private val location: LocationStore,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow<SectionState>(SectionState.Loading)
    val state: StateFlow<SectionState> = _state.asStateFlow()

    private val _nextScheduledAt = MutableStateFlow<Instant?>(null)
    val nextScheduledAt: StateFlow<Instant?> = _nextScheduledAt.asStateFlow()

    private val mutex = Mutex()

    /** Credentials that produced an auth error; no calls are made with them again (US3). */
    @Volatile
    private var authBlocked: ApsCredentials? = null

    /** Shows cached data immediately, before any network call (SC-002). */
    suspend fun loadCached() {
        if (_state.value != SectionState.Loading) return
        val creds = credentials.current()
        if (creds == null) {
            _state.value = SectionState.NotConnected
            return
        }
        val zone = systemInfo.current()?.timezone ?: zoneOrDefault(null)
        snapshots.current()?.let {
            _state.value = SectionState.Data(it.forDate(LocalDate.now(clock.withZone(zone))), Freshness.Fresh)
        }
    }

    /** Called after connecting, disconnecting or changing credentials. */
    fun reset() {
        authBlocked = null
        _state.value = SectionState.Loading
        _nextScheduledAt.value = null
    }

    suspend fun refresh(manual: Boolean): RefreshOutcome = mutex.withLock {
        val creds = credentials.current()
        if (creds == null) {
            _state.value = SectionState.NotConnected
            return RefreshOutcome.NotConnected
        }
        if (creds == authBlocked) {
            _state.value = SectionState.AuthError
            return RefreshOutcome.Failed
        }
        authBlocked = null

        val snap = snapshots.current()
        val info = systemInfo.current() ?: when (val r = fetchSystemInfo(creds)) {
            is ApiResult.Success -> r.value
            else -> return fail(creds, r, snap, clock.instant())
        }

        val now = clock.instant()
        val today = LocalDate.ofInstant(now, info.timezone)
        val loc = location.current()
        val decision = RefreshPolicy.decide(
            now, info.timezone, loc.latitude, loc.longitude, budget.current(), snap, manual,
        )
        _nextScheduledAt.value = decision.nextScheduledAt

        if (!decision.fetchMinutely && !decision.fetchSummary) {
            _state.value = skippedState(snap?.forDate(today), decision)
            return RefreshOutcome.Skipped(decision.skipReason ?: SkipReason.TooSoon, decision.nextScheduledAt, snap?.fetchedAt)
        }

        var next = snap?.forDate(today) ?: emptySnapshot(today)
        var noData = false

        if (decision.fetchMinutely) {
            when (val r = minutelyWithRecovery(creds, info, today)) {
                is ApiResult.Success -> {
                    val sample = r.value.lastSample()
                    next = next.copy(
                        currentPowerW = sample?.second ?: 0,
                        powerSampleTime = sample?.first?.toString(),
                        sampleDate = today.toString(),
                        todayKwh = r.value.today.kwhOrNull() ?: next.todayKwh,
                        fetchedAtEpochMs = now.toEpochMilli(),
                    )
                }
                ApiResult.NoData -> {
                    noData = true
                    next = next.copy(
                        currentPowerW = 0,
                        powerSampleTime = null,
                        sampleDate = today.toString(),
                        todayKwh = 0.0,
                        fetchedAtEpochMs = now.toEpochMilli(),
                    )
                }
                else -> return fail(creds, r, snap?.forDate(today), now)
            }
        }

        if (decision.fetchSummary) {
            when (val r = api.summary(creds)) {
                is ApiResult.Success -> {
                    val t = r.value.toTotals()
                    next = next.copy(
                        todayKwh = if (decision.fetchMinutely) next.todayKwh else t.todayKwh ?: next.todayKwh,
                        monthKwh = t.monthKwh ?: next.monthKwh,
                        yearKwh = t.yearKwh ?: next.yearKwh,
                        lifetimeKwh = t.lifetimeKwh ?: next.lifetimeKwh,
                        summaryFetchedAtEpochMs = now.toEpochMilli(),
                    )
                }
                ApiResult.NoData -> next = next.copy(summaryFetchedAtEpochMs = now.toEpochMilli())
                else -> {
                    // Keep whatever minutely data we already got.
                    if (decision.fetchMinutely) snapshots.save(next)
                    return fail(creds, r, if (decision.fetchMinutely) next else snap?.forDate(today), now)
                }
            }
        }

        budget.onSuccess()
        snapshots.save(next)
        _state.value = when {
            decision.skipReason == SkipReason.Night ->
                SectionState.Data(next, Freshness.Stale(StaleReason.Night(decision.nextScheduledAt)))
            noData -> SectionState.NoDataYet(next)
            else -> SectionState.Data(next, Freshness.Fresh)
        }
        RefreshOutcome.Fetched
    }

    private suspend fun fetchSystemInfo(creds: ApsCredentials): ApiResult<ApsSystemInfo> =
        when (val r = api.details(creds)) {
            is ApiResult.Success -> {
                val ecu = r.value.ecu.firstOrNull()
                if (ecu == null) {
                    ApiResult.ServiceError(null)
                } else {
                    val info = ApsSystemInfo(ecu, zoneOrDefault(r.value.timezone), r.value.capacity.kwhOrNull())
                    systemInfo.save(info)
                    ApiResult.Success(info)
                }
            }
            ApiResult.NoData -> ApiResult.ServiceError(null)
            is ApiResult.AuthError -> r
            is ApiResult.Throttled -> r
            is ApiResult.ServiceError -> r
            ApiResult.Offline -> ApiResult.Offline
        }

    /** On an auth error, re-read the ECU id once: if it changed, retry; otherwise the credentials are wrong. */
    private suspend fun minutelyWithRecovery(
        creds: ApsCredentials,
        info: ApsSystemInfo,
        today: LocalDate,
    ): ApiResult<MinutelyData> {
        val first = api.minutely(creds, info.ecuId, today)
        if (first !is ApiResult.AuthError) return first
        return when (val d = fetchSystemInfo(creds)) {
            is ApiResult.Success ->
                if (d.value.ecuId != info.ecuId) api.minutely(creds, d.value.ecuId, today) else first
            is ApiResult.AuthError -> d
            else -> first
        }
    }

    private suspend fun fail(
        creds: ApsCredentials,
        result: ApiResult<*>,
        snap: PvSnapshot?,
        now: Instant,
    ): RefreshOutcome {
        val reason: StaleReason = when (result) {
            is ApiResult.AuthError -> {
                authBlocked = creds
                _state.value = SectionState.AuthError
                return RefreshOutcome.Failed
            }
            is ApiResult.Throttled -> {
                budget.onThrottled(now)
                StaleReason.Throttled
            }
            ApiResult.Offline -> StaleReason.Offline
            is ApiResult.ServiceError -> StaleReason.ServiceError(result.code)
            else -> StaleReason.ServiceError(null)
        }
        _state.value = if (snap != null && snap.fetchedAtEpochMs > 0) {
            SectionState.Data(snap, Freshness.Stale(reason))
        } else {
            SectionState.Error(reason)
        }
        return RefreshOutcome.Failed
    }

    private fun skippedState(snap: PvSnapshot?, d: RefreshDecision): SectionState {
        val stale: StaleReason? = when (d.skipReason) {
            SkipReason.Night -> StaleReason.Night(d.nextScheduledAt)
            SkipReason.BudgetExhausted, SkipReason.Throttled -> StaleReason.Throttled
            SkipReason.TooSoon, null -> null
        }
        if (snap == null) {
            return when (stale) {
                null, is StaleReason.Night -> _state.value.takeUnless { it == SectionState.Loading }
                    ?: SectionState.Error(stale ?: StaleReason.ServiceError(null))
                else -> SectionState.Error(stale)
            }
        }
        // Keep a more specific current state (e.g. an Offline banner) when merely "too soon".
        val current = _state.value
        if (stale == null && current is SectionState.Data && current.freshness is Freshness.Stale) {
            return current.copy(snapshot = snap)
        }
        if (stale == null && current is SectionState.NoDataYet) return current
        return SectionState.Data(snap, stale?.let { Freshness.Stale(it) } ?: Freshness.Fresh)
    }

    private fun emptySnapshot(today: LocalDate) = PvSnapshot(
        currentPowerW = null,
        powerSampleTime = null,
        sampleDate = today.toString(),
        todayKwh = null,
        monthKwh = null,
        yearKwh = null,
        lifetimeKwh = null,
        fetchedAtEpochMs = 0L,
        summaryFetchedAtEpochMs = null,
    )
}

/** A snapshot from an earlier day has no "today" values yet. */
fun PvSnapshot.forDate(today: LocalDate): PvSnapshot =
    if (sampleDate == today.toString()) this
    else copy(currentPowerW = null, powerSampleTime = null, todayKwh = null, sampleDate = today.toString())
