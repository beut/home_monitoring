package pl.home.monitoring.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.home.monitoring.R
import pl.home.monitoring.data.apsystems.ApiResult
import pl.home.monitoring.data.apsystems.ApsApi
import pl.home.monitoring.data.apsystems.kwhOrNull
import pl.home.monitoring.data.store.CallBudgetStore
import pl.home.monitoring.data.store.CredentialsStore
import pl.home.monitoring.data.store.Location
import pl.home.monitoring.data.store.LocationStore
import pl.home.monitoring.data.store.SnapshotStore
import pl.home.monitoring.data.store.SystemInfoStore
import pl.home.monitoring.data.store.zoneOrDefault
import pl.home.monitoring.domain.ApsCredentials
import pl.home.monitoring.domain.ApsSystemInfo
import pl.home.monitoring.domain.CallBudget
import pl.home.monitoring.ui.UiText
import pl.home.monitoring.ui.uiText
import java.time.YearMonth

data class ApsForm(
    val appId: String = "",
    val appSecret: String = "",
    val sid: String = "",
    val secretVisible: Boolean = false,
) {
    val isComplete: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank() && sid.isNotBlank()
}

sealed interface ConnectionUi {
    data object NotConnected : ConnectionUi
    data object Connecting : ConnectionUi
    data class Connected(val capacityKwp: Double?, val maskedSid: String) : ConnectionUi
    data class Error(val message: UiText) : ConnectionUi
}

class AccountsViewModel(
    private val api: ApsApi,
    private val credentials: CredentialsStore,
    private val systemInfo: SystemInfoStore,
    private val snapshots: SnapshotStore,
    private val budgetStore: CallBudgetStore,
    private val locationStore: LocationStore,
    /** Tells the PV repository that credentials changed (unblocks auth errors, drops stale state). */
    private val onCredentialsChanged: () -> Unit,
) : ViewModel() {

    private val _form = MutableStateFlow(ApsForm())
    val form: StateFlow<ApsForm> = _form.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionUi>(ConnectionUi.NotConnected)
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    /** One-shot: emitted after a successful connect, so the UI can navigate to Pulpit. */
    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    val budget: StateFlow<CallBudget> =
        budgetStore.budget.stateIn(viewModelScope, SharingStarted.Eagerly, CallBudget(YearMonth.now()))

    val location: StateFlow<Location> =
        locationStore.location.stateIn(viewModelScope, SharingStarted.Eagerly, Location())

    private val _locationMessage = MutableStateFlow<UiText?>(null)
    val locationMessage: StateFlow<UiText?> = _locationMessage.asStateFlow()

    val initialized: Job = viewModelScope.launch {
        val saved = credentials.credentials.first()
        if (saved != null) {
            _connection.value = ConnectionUi.Connected(systemInfo.current()?.capacityKwp, mask(saved.sid))
        } else {
            credentials.devPrefill()?.let { _form.value = ApsForm(it.appId, it.appSecret, it.sid) }
        }
    }

    fun onAppIdChange(v: String) = _form.update { copy(appId = v) }
    fun onSecretChange(v: String) = _form.update { copy(appSecret = v) }
    fun onSidChange(v: String) = _form.update { copy(sid = v) }
    fun toggleSecretVisible() = _form.update { copy(secretVisible = !secretVisible) }

    /** Validates with one `details` call; saves nothing unless it succeeds (FR-009). */
    fun connect(): Job = viewModelScope.launch {
        val f = _form.value
        val creds = ApsCredentials.orNull(f.appId, f.appSecret, f.sid)
        if (creds == null) {
            _connection.value = ConnectionUi.Error(uiText(R.string.err_required))
            return@launch
        }
        _connection.value = ConnectionUi.Connecting
        _connection.value = when (val r = api.details(creds)) {
            is ApiResult.Success -> {
                val ecu = r.value.ecu.firstOrNull()
                if (ecu == null) {
                    ConnectionUi.Error(uiText(R.string.err_aps_service, "—"))
                } else {
                    val capacity = r.value.capacity.kwhOrNull()
                    credentials.save(creds)
                    systemInfo.save(ApsSystemInfo(ecu, zoneOrDefault(r.value.timezone), capacity))
                    snapshots.clear()
                    onCredentialsChanged()
                    _connected.tryEmit(Unit)
                    ConnectionUi.Connected(capacity, mask(creds.sid))
                }
            }
            is ApiResult.AuthError -> ConnectionUi.Error(uiText(R.string.err_aps_auth))
            ApiResult.Offline -> ConnectionUi.Error(uiText(R.string.err_aps_offline))
            is ApiResult.Throttled -> ConnectionUi.Error(uiText(R.string.err_aps_throttled))
            is ApiResult.ServiceError -> ConnectionUi.Error(uiText(R.string.err_aps_service, r.code?.toString() ?: "—"))
            ApiResult.NoData -> ConnectionUi.Error(uiText(R.string.err_aps_service, "1001"))
        }
    }

    fun disconnect(): Job = viewModelScope.launch {
        credentials.clear()
        systemInfo.clear()
        snapshots.clear()
        onCredentialsChanged()
        _form.value = ApsForm()
        _connection.value = ConnectionUi.NotConnected
    }

    fun setLimit(n: Int): Job = viewModelScope.launch { budgetStore.setLimit(n) }

    fun setLocation(lat: Double?, lon: Double?): Job = viewModelScope.launch {
        val ok = lat != null && lon != null && locationStore.save(lat, lon)
        _locationMessage.value = uiText(if (ok) R.string.saved else R.string.location_invalid)
    }

    private inline fun MutableStateFlow<ApsForm>.update(f: ApsForm.() -> ApsForm) {
        value = value.f()
        if (_connection.value is ConnectionUi.Error) _connection.value = ConnectionUi.NotConnected
    }

    companion object {
        fun mask(sid: String): String = if (sid.length <= 4) sid else "•".repeat(sid.length - 4) + sid.takeLast(4)
    }
}
