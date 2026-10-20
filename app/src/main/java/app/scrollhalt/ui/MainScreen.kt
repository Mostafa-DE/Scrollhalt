package app.scrollhalt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.scrollhalt.R
import app.scrollhalt.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    status: ProtectionStatus,
    settings: AppSettings,
    onBlockingChange: (Boolean) -> Unit,
    onAllowSingleReelChange: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = onOpenDebug) { Text(stringResource(R.string.debug_open)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(status, onOpenAccessibility)
            SettingsCard(settings, onBlockingChange, onAllowSingleReelChange)
            SetupCard(onOpenAppInfo)
            InfoCard(R.string.how_title) {
                Text(stringResource(R.string.how_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StatusCard(status: ProtectionStatus, onOpenAccessibility: () -> Unit) {
    val (title, detail) = when (status) {
        ProtectionStatus.OFF -> R.string.status_off to R.string.status_off_detail
        ProtectionStatus.ENABLED_NOT_RUNNING -> R.string.status_not_running to R.string.status_not_running_detail
        ProtectionStatus.PAUSED -> R.string.status_paused to R.string.status_paused_detail
        ProtectionStatus.PROTECTING -> R.string.status_protecting to R.string.status_protecting_detail
    }
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        ProtectionStatus.PROTECTING -> colors.primaryContainer to colors.onPrimaryContainer
        ProtectionStatus.OFF -> colors.errorContainer to colors.onErrorContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    InfoCard(R.string.status_title, container, content) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
        if (status != ProtectionStatus.PROTECTING) {
            Button(onClick = onOpenAccessibility) { Text(stringResource(R.string.open_accessibility_settings)) }
        }
    }
}

@Composable
private fun SettingsCard(
    settings: AppSettings,
    onBlockingChange: (Boolean) -> Unit,
    onAllowSingleReelChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(stringResource(R.string.setting_block), null, settings.blockingEnabled, true, onBlockingChange)
            SwitchRow(
                title = stringResource(R.string.setting_single_reel),
                detail = stringResource(R.string.setting_single_reel_detail),
                checked = settings.allowSingleReel,
                enabled = settings.blockingEnabled,
                onChange = onAllowSingleReelChange,
            )
        }
    }
}

@Composable
private fun SetupCard(onOpenAppInfo: () -> Unit) {
    InfoCard(R.string.setup_title) {
        Text(stringResource(R.string.setup_restricted), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.setup_samsung), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.open_app_info)) }
    }
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun InfoCard(
    titleRes: Int,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    body: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
            body()
        }
    }
}
