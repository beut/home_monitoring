package pl.home.monitoring.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.home.monitoring.R
import pl.home.monitoring.core.Formatters
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.connected.collect { onConnected() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApsCard(viewModel)
            SoonCard(stringResource(R.string.panasonic_title))
            SoonCard(stringResource(R.string.tado_title))
            BudgetCard(viewModel)
            LocationCard(viewModel)
        }
    }
}

@Composable
private fun ApsCard(viewModel: AccountsViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.aps_card_title), style = MaterialTheme.typography.titleMedium)
            when (val c = connection) {
                is ConnectionUi.Connected -> {
                    val status = c.capacityKwp?.let {
                        stringResource(R.string.connected_status, oneDecimal(it))
                    } ?: stringResource(R.string.connected_status_nocap)
                    Text(status, color = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.sid_masked, c.maskedSid), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirmDisconnect = true }) { Text(stringResource(R.string.disconnect)) }
                }
                else -> {
                    val busy = c is ConnectionUi.Connecting
                    OutlinedTextField(
                        value = form.appId,
                        onValueChange = viewModel::onAppIdChange,
                        label = { Text(stringResource(R.string.field_app_id)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.appSecret,
                        onValueChange = viewModel::onSecretChange,
                        label = { Text(stringResource(R.string.field_app_secret)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (form.secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                        trailingIcon = {
                            IconButton(onClick = viewModel::toggleSecretVisible) {
                                Icon(
                                    if (form.secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(
                                        if (form.secretVisible) R.string.hide_secret else R.string.show_secret,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.sid,
                        onValueChange = viewModel::onSidChange,
                        label = { Text(stringResource(R.string.field_sid)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (c is ConnectionUi.Error) {
                        Text(c.message.asString(), color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = { viewModel.connect() }, enabled = form.isComplete && !busy) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.connect))
                    }
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.disconnect_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    viewModel.disconnect()
                }) { Text(stringResource(R.string.disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SoonCard(title: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.soon), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BudgetCard(viewModel: AccountsViewModel) {
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    var limitText by rememberSaveable(budget.monthlyLimit) { mutableStateOf(budget.monthlyLimit.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.budget_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.budget_used, budget.used, budget.monthlyLimit))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { v -> limitText = v.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.budget_limit_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { limitText.toIntOrNull()?.let { viewModel.setLimit(it) } },
                    enabled = limitText.toIntOrNull() != null && limitText.toIntOrNull() != budget.monthlyLimit,
                ) { Text(stringResource(R.string.save)) }
            }
            Text(
                stringResource(R.string.budget_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocationCard(viewModel: AccountsViewModel) {
    val location by viewModel.location.collectAsStateWithLifecycle()
    val message by viewModel.locationMessage.collectAsStateWithLifecycle()
    var lat by rememberSaveable(location.latitude) { mutableStateOf(location.latitude.toString()) }
    var lon by rememberSaveable(location.longitude) { mutableStateOf(location.longitude.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text(stringResource(R.string.latitude)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text(stringResource(R.string.longitude)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.setLocation(lat.toCoordinate(), lon.toCoordinate()) }) {
                    Text(stringResource(R.string.save))
                }
                Spacer(Modifier.width(12.dp))
                message?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(0.dp))
            Text(
                stringResource(R.string.location_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Accepts both "52.1" and the Polish "52,1". */
private fun String.toCoordinate(): Double? = trim().replace(',', '.').toDoubleOrNull()

private fun oneDecimal(v: Double): String =
    NumberFormat.getNumberInstance(Formatters.PL).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(v)
