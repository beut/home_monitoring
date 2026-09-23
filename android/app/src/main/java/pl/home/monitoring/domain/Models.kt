package pl.home.monitoring.domain

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

enum class SourceId { PV, HEAT_PUMP, ROOMS }

class ApsCredentials private constructor(
    val appId: String,
    val appSecret: String,
    val sid: String,
) {
    init {
        require(appId.isNotBlank() && appSecret.isNotBlank() && sid.isNotBlank()) { "All APsystems fields are required" }
    }

    override fun equals(other: Any?) =
        other is ApsCredentials && other.appId == appId && other.appSecret == appSecret && other.sid == sid

    override fun hashCode() = listOf(appId, appSecret, sid).hashCode()

    // Never print the secret.
    override fun toString() = "ApsCredentials(appId=${appId.take(4)}…, sid=…${sid.takeLast(4)})"

    companion object {
        fun of(appId: String, appSecret: String, sid: String) =
            ApsCredentials(appId.trim(), appSecret.trim(), sid.trim())

        fun orNull(appId: String?, appSecret: String?, sid: String?): ApsCredentials? =
            if (appId.isNullOrBlank() || appSecret.isNullOrBlank() || sid.isNullOrBlank()) null
            else of(appId, appSecret, sid)
    }
}

data class ApsSystemInfo(
    val ecuId: String,
    val timezone: ZoneId,
    val capacityKwp: Double?,
)

/** Last successful PV data, cached on the device (data-model.md). Times are stored as ISO strings. */
@Serializable
data class PvSnapshot(
    val currentPowerW: Int?,
    val powerSampleTime: String?,
    val sampleDate: String,
    val todayKwh: Double?,
    val monthKwh: Double?,
    val yearKwh: Double?,
    val lifetimeKwh: Double?,
    val fetchedAtEpochMs: Long,
    val summaryFetchedAtEpochMs: Long?,
) {
    val fetchedAt: Instant get() = Instant.ofEpochMilli(fetchedAtEpochMs)
    val summaryFetchedAt: Instant? get() = summaryFetchedAtEpochMs?.let(Instant::ofEpochMilli)
    val sampleTime: LocalTime? get() = powerSampleTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
}

sealed interface StaleReason {
    data object Offline : StaleReason
    data object Throttled : StaleReason
    data class ServiceError(val code: Int?) : StaleReason
    data class Night(val nextAt: Instant) : StaleReason
}

sealed interface Freshness {
    data object Fresh : Freshness
    data class Stale(val reason: StaleReason) : Freshness
}

sealed interface SectionState {
    data object Loading : SectionState
    data class Data(val snapshot: PvSnapshot, val freshness: Freshness) : SectionState
    data class NoDataYet(val snapshot: PvSnapshot?) : SectionState
    /** A fetch failed and there is no cached snapshot yet. */
    data class Error(val reason: StaleReason) : SectionState
    data object AuthError : SectionState
    data object NotConnected : SectionState
}

data class CallBudget(
    val monthKey: YearMonth,
    val used: Int = 0,
    val monthlyLimit: Int = DEFAULT_LIMIT,
    val throttledUntil: Instant? = null,
    val throttleStreak: Int = 0,
    val lastAttemptAt: Instant? = null,
) {
    val remaining: Int get() = (monthlyLimit - used).coerceAtLeast(0)

    fun forMonth(month: YearMonth): CallBudget =
        if (month == monthKey) this else copy(monthKey = month, used = 0)

    companion object {
        const val DEFAULT_LIMIT = 800
        const val MIN_LIMIT = 100
        const val MAX_LIMIT = 1000

        fun coerceLimit(n: Int) = n.coerceIn(MIN_LIMIT, MAX_LIMIT)

        /** 15 → 30 → 60 min …, capped at 6 h. [streak] counts consecutive throttled results, starting at 1. */
        fun throttlePause(streak: Int): Duration {
            val max = Duration.ofHours(6)
            val shift = (streak - 1).coerceIn(0, 10)
            val pause = Duration.ofMinutes(15L shl shift)
            return if (pause > max) max else pause
        }
    }
}
