package pl.home.monitoring.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pl.home.monitoring.R
import pl.home.monitoring.core.Formatters
import pl.home.monitoring.domain.Freshness
import pl.home.monitoring.domain.PvSnapshot
import pl.home.monitoring.domain.SectionState
import pl.home.monitoring.domain.StaleReason
import pl.home.monitoring.ui.formatWhen
import pl.home.monitoring.ui.theme.HomeMonitoringTheme
import java.time.Instant

@Composable
fun PvSection(
    state: SectionState,
    capacityKwp: Double?,
    onRetry: () -> Unit,
    onOpenAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Header(capacityKwp)
            Spacer(Modifier.height(12.dp))
            when (state) {
                SectionState.Loading -> Box { CircularProgressIndicator(Modifier.size(32.dp)) }
                is SectionState.Data -> DataContent(state.snapshot, state.freshness, onRetry)
                is SectionState.NoDataYet -> {
                    Values(
                        powerText = Formatters.formatPower(0),
                        sampleTime = null,
                        snapshot = state.snapshot,
                        todayOverride = 0.0,
                    )
                    Note(stringResource(R.string.no_data_today))
                    state.snapshot?.let { Footer(it) }
                }
                is SectionState.Error -> {
                    Values(powerText = Formatters.DASH, sampleTime = null, snapshot = null, todayOverride = null)
                    Banner(errorText(state.reason))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                SectionState.AuthError -> {
                    Text(stringResource(R.string.auth_error), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenAccounts) { Text(stringResource(R.string.fix_in_settings)) }
                }
                SectionState.NotConnected -> {
                    Text(stringResource(R.string.connect_aps))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenAccounts) { Text(stringResource(R.string.connect_account)) }
                }
            }
        }
    }
}

@Composable
private fun Box(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) { content() }

@Composable
private fun Header(capacityKwp: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.pv_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (capacityKwp != null) {
            Text(
                stringResource(R.string.pv_capacity, Formatters.formatKwh(capacityKwp).removeSuffix(" kWh")),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DataContent(snapshot: PvSnapshot, freshness: Freshness, onRetry: () -> Unit) {
    val reason = (freshness as? Freshness.Stale)?.reason
    if (reason is StaleReason.Night) {
        Values(
            powerText = stringResource(R.string.night_power, Formatters.formatPower(0)),
            sampleTime = null,
            snapshot = snapshot,
            todayOverride = null,
        )
        Footer(snapshot)
        Note(stringResource(R.string.next_update_at, formatWhen(reason.nextAt)))
        return
    }
    Values(
        powerText = Formatters.formatPower(snapshot.currentPowerW),
        sampleTime = snapshot.sampleTime?.let(Formatters::formatTime),
        snapshot = snapshot,
        todayOverride = null,
    )
    Footer(snapshot)
    when (reason) {
        StaleReason.Offline -> Banner(stringResource(R.string.stale_offline, formatWhen(snapshot.fetchedAt)))
        StaleReason.Throttled -> Banner(stringResource(R.string.stale_throttled, formatWhen(snapshot.fetchedAt)))
        is StaleReason.ServiceError -> {
            Banner(stringResource(R.string.stale_service, formatWhen(snapshot.fetchedAt)))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        else -> Unit
    }
}

@Composable
private fun Values(powerText: String, sampleTime: String?, snapshot: PvSnapshot?, todayOverride: Double?) {
    Text(stringResource(R.string.pv_power_now), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(powerText, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        if (sampleTime != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.pv_at_time, sampleTime),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    // "null means 0" for today (data-model.md); other totals show a dash when unknown.
    val today = todayOverride ?: snapshot?.todayKwh ?: if (snapshot != null) 0.0 else null
    LabeledValue(stringResource(R.string.pv_today), Formatters.formatKwh(today), big = true)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        LabeledValue(stringResource(R.string.pv_month), Formatters.formatKwh(snapshot?.monthKwh), Modifier.weight(1f))
        LabeledValue(stringResource(R.string.pv_year), Formatters.formatKwh(snapshot?.yearKwh), Modifier.weight(1f))
        LabeledValue(stringResource(R.string.pv_lifetime), Formatters.formatKwh(snapshot?.lifetimeKwh), Modifier.weight(1f))
    }
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Footer(snapshot: PvSnapshot) {
    val at = (snapshot.fetchedAtEpochMs.takeIf { it > 0 } ?: snapshot.summaryFetchedAtEpochMs)?.let(Instant::ofEpochMilli)
    if (at != null) Note(stringResource(R.string.updated_at, formatWhen(at)))
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Banner(text: String) {
    Spacer(Modifier.height(8.dp))
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        )
    }
}

@Composable
private fun errorText(reason: StaleReason): String = when (reason) {
    StaleReason.Offline -> stringResource(R.string.error_offline)
    StaleReason.Throttled -> stringResource(R.string.error_throttled)
    is StaleReason.ServiceError -> reason.code?.let { stringResource(R.string.error_service, it) }
        ?: stringResource(R.string.error_service_nocode)
    is StaleReason.Night -> stringResource(R.string.error_service_nocode)
}

// ---------- Previews ----------

private val previewSnapshot = PvSnapshot(
    currentPowerW = 3480, powerSampleTime = "12:35", sampleDate = "2026-09-23", todayKwh = 12.44,
    monthKwh = 617.66, yearKwh = 6166.11, lifetimeKwh = 28320.78,
    fetchedAtEpochMs = System.currentTimeMillis(), summaryFetchedAtEpochMs = System.currentTimeMillis(),
)

@Composable
private fun PreviewBox(state: SectionState) = HomeMonitoringTheme {
    Surface { PvSection(state, 9.96, {}, {}, Modifier.padding(16.dp)) }
}

@Preview @Composable private fun PreviewFresh() = PreviewBox(SectionState.Data(previewSnapshot, Freshness.Fresh))
@Preview @Composable private fun PreviewLoading() = PreviewBox(SectionState.Loading)
@Preview @Composable private fun PreviewOffline() =
    PreviewBox(SectionState.Data(previewSnapshot, Freshness.Stale(StaleReason.Offline)))
@Preview @Composable private fun PreviewThrottled() =
    PreviewBox(SectionState.Data(previewSnapshot, Freshness.Stale(StaleReason.Throttled)))
@Preview @Composable private fun PreviewServiceError() =
    PreviewBox(SectionState.Data(previewSnapshot, Freshness.Stale(StaleReason.ServiceError(5000))))
@Preview @Composable private fun PreviewNight() =
    PreviewBox(SectionState.Data(previewSnapshot, Freshness.Stale(StaleReason.Night(Instant.now().plusSeconds(3600)))))
@Preview @Composable private fun PreviewNoData() = PreviewBox(SectionState.NoDataYet(previewSnapshot))
@Preview @Composable private fun PreviewError() = PreviewBox(SectionState.Error(StaleReason.Offline))
@Preview @Composable private fun PreviewAuth() = PreviewBox(SectionState.AuthError)
@Preview @Composable private fun PreviewNotConnected() = PreviewBox(SectionState.NotConnected)
