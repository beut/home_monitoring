package pl.home.monitoring.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import pl.home.monitoring.R
import pl.home.monitoring.core.SkipReason
import pl.home.monitoring.data.apsystems.PvRepository
import pl.home.monitoring.data.apsystems.RefreshOutcome
import pl.home.monitoring.domain.SectionState
import pl.home.monitoring.ui.UiText
import pl.home.monitoring.ui.formatWhen
import pl.home.monitoring.ui.uiText
import java.time.Clock
import java.time.Duration

class DashboardViewModel(
    private val pv: PvRepository,
    private val clock: Clock,
) : ViewModel() {

    val pvState: StateFlow<SectionState> = pv.state

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    private var autoJob: Job? = null
    private var manualJob: Job? = null

    init {
        viewModelScope.launch { pv.loadCached() }
    }

    /** Pull-to-refresh and "Spróbuj ponownie": all sources in parallel, isolated from each other (FR-002, FR-012). */
    fun onPullToRefresh(): Job {
        manualJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
            _isRefreshing.value = true
            try {
                supervisorScope {
                    launch { runCatching { pv.refresh(manual = true) }.onSuccess(::announce) }
                    // Future sources (heat pump, rooms) launch here, each in its own child.
                }
            } finally {
                _isRefreshing.value = false
            }
        }.also { manualJob = it }
    }

    fun onRetry() {
        onPullToRefresh()
    }

    /** Runs only while the dashboard is visible (FR-014). */
    fun startAutoRefresh() {
        if (autoJob?.isActive == true) return
        autoJob = viewModelScope.launch {
            while (isActive) {
                supervisorScope {
                    launch { runCatching { pv.refresh(manual = false) } }
                }
                val next = pv.nextScheduledAt.value
                val wait = next?.let { Duration.between(clock.instant(), it) } ?: Duration.ofMinutes(15)
                delay(maxOf(wait, MIN_LOOP).toMillis())
            }
        }
    }

    fun stopAutoRefresh() {
        autoJob?.cancel()
        autoJob = null
    }

    private fun announce(outcome: RefreshOutcome) {
        if (outcome !is RefreshOutcome.Skipped) return
        val msg = when (outcome.reason) {
            SkipReason.TooSoon -> outcome.lastFetchedAt?.let {
                val minutes = Duration.between(it, clock.instant()).toMinutes().coerceAtLeast(0)
                uiText(R.string.msg_fresh, minutes.toInt())
            } ?: uiText(R.string.msg_retry_soon)
            SkipReason.BudgetExhausted -> uiText(R.string.msg_budget, formatWhen(outcome.nextAt))
            SkipReason.Night -> uiText(R.string.msg_night, formatWhen(outcome.nextAt))
            SkipReason.Throttled -> uiText(R.string.msg_throttled, formatWhen(outcome.nextAt))
        }
        _messages.tryEmit(msg)
    }

    private companion object {
        val MIN_LOOP: Duration = Duration.ofSeconds(60)
    }
}
