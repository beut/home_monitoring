package pl.home.monitoring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.map
import pl.home.monitoring.ui.accounts.AccountsScreen
import pl.home.monitoring.ui.accounts.AccountsViewModel
import pl.home.monitoring.ui.dashboard.DashboardScreen
import pl.home.monitoring.ui.dashboard.DashboardViewModel
import pl.home.monitoring.ui.theme.HomeMonitoringTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HomeMonitoringApp).container
        setContent {
            HomeMonitoringTheme {
                Surface(Modifier.fillMaxSize()) { AppRoot(container) }
            }
        }
    }
}

private enum class Screen { Dashboard, Accounts }

/** Credentials as loaded from storage; [Unknown] until the first read completes. */
private sealed interface Connected {
    data object Unknown : Connected
    data class Known(val connected: Boolean) : Connected
}

@Composable
private fun AppRoot(container: AppContainer) {
    val connectedFlow = remember { container.credentialsStore.credentials.map { Connected.Known(it != null) } }
    val connected by connectedFlow.collectAsStateWithLifecycle(initialValue = Connected.Unknown)
    val capacityFlow = remember { container.systemInfoStore.info.map { it?.capacityKwp } }
    val capacity by capacityFlow.collectAsStateWithLifecycle(initialValue = null)

    val known = connected as? Connected.Known
    if (known == null) {
        // Blank until the first DataStore read (a few ms), so we never flash the wrong screen.
        Box(Modifier.fillMaxSize())
        return
    }

    var screen by rememberSaveable { mutableStateOf(if (known.connected) Screen.Dashboard else Screen.Accounts) }

    val dashboardVm: DashboardViewModel = viewModel(
        factory = viewModelFactory { initializer { DashboardViewModel(container.pvRepository, container.clock) } },
    )

    when (screen) {
        Screen.Dashboard -> DashboardScreen(
            viewModel = dashboardVm,
            capacityKwp = capacity,
            onOpenAccounts = { screen = Screen.Accounts },
        )
        Screen.Accounts -> {
            val accountsVm: AccountsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        AccountsViewModel(
                            api = container.apsApi,
                            credentials = container.credentialsStore,
                            systemInfo = container.systemInfoStore,
                            snapshots = container.snapshotStore,
                            budgetStore = container.callBudgetStore,
                            locationStore = container.locationStore,
                            onCredentialsChanged = container.pvRepository::reset,
                        )
                    }
                },
            )
            BackHandler(enabled = known.connected) { screen = Screen.Dashboard }
            AccountsScreen(
                viewModel = accountsVm,
                canGoBack = known.connected,
                onBack = { screen = Screen.Dashboard },
                onConnected = { screen = Screen.Dashboard },
            )
        }
    }
}
