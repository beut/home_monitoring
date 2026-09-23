package pl.home.monitoring.ui.accounts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.home.monitoring.R
import pl.home.monitoring.data.apsystems.ApiResult
import pl.home.monitoring.data.apsystems.ApsApi
import pl.home.monitoring.data.apsystems.DetailsData
import pl.home.monitoring.data.apsystems.MinutelyData
import pl.home.monitoring.data.apsystems.SummaryData
import pl.home.monitoring.data.security.StringCipher
import pl.home.monitoring.data.store.CallBudgetStore
import pl.home.monitoring.data.store.CredentialsStore
import pl.home.monitoring.data.store.LocationStore
import pl.home.monitoring.data.store.SnapshotStore
import pl.home.monitoring.data.store.SystemInfoStore
import pl.home.monitoring.domain.ApsCredentials
import pl.home.monitoring.domain.PvSnapshot
import java.time.LocalDate
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val main = Executors.newSingleThreadExecutor()
    private lateinit var scope: CoroutineScope
    private lateinit var api: FakeApi
    private lateinit var creds: CredentialsStore
    private lateinit var info: SystemInfoStore
    private lateinit var snaps: SnapshotStore
    private lateinit var budget: CallBudgetStore
    private lateinit var location: LocationStore
    private var changed = 0

    object PlainCipher : StringCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    class FakeApi : ApsApi {
        var detailsResult: ApiResult<DetailsData> = ApiResult.Success(DetailsData(listOf("ECU1"), "Europe/Warsaw", "9.96"))
        var detailsCalls = 0
        override suspend fun details(creds: ApsCredentials): ApiResult<DetailsData> {
            detailsCalls++
            return detailsResult
        }
        override suspend fun summary(creds: ApsCredentials): ApiResult<SummaryData> = error("not used")
        override suspend fun minutely(creds: ApsCredentials, ecuId: String, date: LocalDate): ApiResult<MinutelyData> =
            error("not used")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        fun ds(name: String) = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("$name.preferences_pb") }
        api = FakeApi()
        creds = CredentialsStore(ds("credentials"), PlainCipher)
        info = SystemInfoStore(ds("settings"))
        snaps = SnapshotStore(ds("snapshot"))
        budget = CallBudgetStore(ds("budget"))
        location = LocationStore(ds("location"))
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        main.shutdown()
    }

    private fun vm() = AccountsViewModel(api, creds, info, snaps, budget, location) { changed++ }
        .also { runBlocking { it.initialized.join() } }

    private fun AccountsViewModel.fill(id: String = "id", secret: String = "secret", sid: String = "SID12345") {
        onAppIdChange(id)
        onSecretChange(secret)
        onSidChange(sid)
    }

    @Test
    fun `a - blank fields fail validation without an API call`() = runBlocking {
        val vm = vm()
        vm.fill(secret = "  ")
        vm.connect().join()
        assertEquals(ConnectionUi.Error(pl.home.monitoring.ui.uiText(R.string.err_required)), vm.connection.value)
        assertEquals(0, api.detailsCalls)
    }

    @Test
    fun `b - auth error names APsystems and saves nothing`() = runBlocking {
        api.detailsResult = ApiResult.AuthError(4000)
        val vm = vm()
        vm.fill()
        vm.connect().join()
        val e = vm.connection.value as ConnectionUi.Error
        assertEquals(R.string.err_aps_auth, e.message.id)
        assertNull(creds.current())
        assertEquals(0, changed)
    }

    @Test
    fun `c - success saves credentials and system info`() = runBlocking {
        val vm = vm()
        vm.fill(id = " id ", sid = "SID12345")
        vm.connect().join()
        val c = vm.connection.value as ConnectionUi.Connected
        assertEquals(9.96, c.capacityKwp!!, 1e-9)
        assertEquals("••••2345", c.maskedSid)
        assertEquals(ApsCredentials.of("id", "secret", "SID12345"), creds.current())
        assertEquals("ECU1", info.current()!!.ecuId)
        assertEquals(1, changed)
    }

    @Test
    fun `d - disconnect clears credentials, system info and snapshot`() = runBlocking {
        val vm = vm()
        vm.fill()
        vm.connect().join()
        snaps.save(PvSnapshot(1, "10:00", "2026-09-23", 1.0, 1.0, 1.0, 1.0, 1L, 1L))
        vm.disconnect().join()
        assertNull(creds.current())
        assertNull(info.current())
        assertNull(snaps.current())
        assertEquals(ConnectionUi.NotConnected, vm.connection.value)
        assertTrue(changed >= 2)
    }

    @Test
    fun `e - limit is coerced to 100-1000`() = runBlocking {
        val vm = vm()
        vm.setLimit(1500).join()
        assertEquals(1000, budget.current().monthlyLimit)
        vm.setLimit(50).join()
        assertEquals(100, budget.current().monthlyLimit)
    }

    @Test
    fun `f - invalid latitude is rejected`() = runBlocking {
        val vm = vm()
        vm.setLocation(95.0, 19.0).join()
        assertEquals(R.string.location_invalid, vm.locationMessage.value!!.id)
        assertEquals(52.0, location.current().latitude, 1e-9)
        vm.setLocation(51.5, 20.1).join()
        assertEquals(51.5, location.current().latitude, 1e-9)
    }

    @Test
    fun `already connected shows connected state`() = runBlocking {
        creds.save(ApsCredentials.of("id", "secret", "SID12345"))
        val vm = vm()
        assertTrue(vm.connection.value is ConnectionUi.Connected)
    }
}
