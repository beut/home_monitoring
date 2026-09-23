package pl.home.monitoring

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import pl.home.monitoring.data.apsystems.ApsApiClient
import pl.home.monitoring.data.apsystems.PvRepository
import pl.home.monitoring.data.security.CredentialCipher
import pl.home.monitoring.data.store.CallBudgetStore
import pl.home.monitoring.data.store.CredentialsStore
import pl.home.monitoring.data.store.LocationStore
import pl.home.monitoring.data.store.SnapshotStore
import pl.home.monitoring.data.store.SystemInfoStore
import pl.home.monitoring.domain.ApsCredentials
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Manual wiring (no DI framework, research R5). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dataStore(name: String) =
        PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile(name) }

    val clock: Clock = Clock.systemDefaultZone()

    val credentialsStore = CredentialsStore(
        store = dataStore("credentials"),
        cipher = CredentialCipher(),
        devPrefill = if (BuildConfig.DEBUG) {
            ApsCredentials.orNull(BuildConfig.DEV_APS_APP_ID, BuildConfig.DEV_APS_APP_SECRET, BuildConfig.DEV_APS_SID)
        } else {
            null
        },
    )
    private val settings = dataStore("settings")
    val systemInfoStore = SystemInfoStore(settings)
    val locationStore = LocationStore(dataStore("location"))
    val snapshotStore = SnapshotStore(dataStore("snapshot"))
    val callBudgetStore = CallBudgetStore(dataStore("budget"))

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactHeader("X-CA-Signature")
                        redactHeader("X-CA-AppId")
                    },
                )
            }
        }
        .build()

    val apsApi = ApsApiClient(http = http, recorder = callBudgetStore, clock = clock)

    val pvRepository = PvRepository(
        api = apsApi,
        credentials = credentialsStore,
        systemInfo = systemInfoStore,
        snapshots = snapshotStore,
        budget = callBudgetStore,
        location = locationStore,
        clock = clock,
    )
}
