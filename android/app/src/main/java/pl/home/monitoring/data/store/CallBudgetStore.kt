package pl.home.monitoring.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.home.monitoring.data.apsystems.CallRecorder
import pl.home.monitoring.domain.CallBudget
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Local count of APsystems calls per calendar month (data-model.md CallBudget). */
class CallBudgetStore(
    private val store: DataStore<Preferences>,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : CallRecorder {

    val budget: Flow<CallBudget> = store.data.map { it.toBudget() }

    suspend fun current(): CallBudget = budget.first()

    override suspend fun recordCall(now: Instant) {
        val month = YearMonth.from(now.atZone(zone()))
        store.edit { p ->
            val b = p.toBudget().forMonth(month)
            p[MONTH] = month.toString()
            p[USED] = b.used + 1
            p[LAST_ATTEMPT] = now.toEpochMilli()
        }
    }

    suspend fun setLimit(n: Int) {
        store.edit { it[LIMIT] = CallBudget.coerceLimit(n) }
    }

    /** Throttled (2005/7001–7003): back off 15 → 30 → 60 min …, capped at 6 h. */
    suspend fun onThrottled(now: Instant) {
        store.edit { p ->
            val streak = (p[STREAK] ?: 0) + 1
            p[STREAK] = streak
            p[THROTTLED_UNTIL] = now.plus(CallBudget.throttlePause(streak)).toEpochMilli()
        }
    }

    suspend fun onSuccess() {
        store.edit { p ->
            p.remove(STREAK)
            p.remove(THROTTLED_UNTIL)
        }
    }

    private fun Preferences.toBudget(): CallBudget {
        val month = this[MONTH]?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: YearMonth.now(zone())
        return CallBudget(
            monthKey = month,
            used = this[USED] ?: 0,
            monthlyLimit = CallBudget.coerceLimit(this[LIMIT] ?: CallBudget.DEFAULT_LIMIT),
            throttledUntil = this[THROTTLED_UNTIL]?.let(Instant::ofEpochMilli),
            throttleStreak = this[STREAK] ?: 0,
            lastAttemptAt = this[LAST_ATTEMPT]?.let(Instant::ofEpochMilli),
        ).forMonth(YearMonth.now(zone()))
    }

    private companion object {
        val MONTH = stringPreferencesKey("month")
        val USED = intPreferencesKey("used")
        val LIMIT = intPreferencesKey("limit")
        val THROTTLED_UNTIL = longPreferencesKey("throttled_until")
        val STREAK = intPreferencesKey("throttle_streak")
        val LAST_ATTEMPT = longPreferencesKey("last_attempt")
    }
}
