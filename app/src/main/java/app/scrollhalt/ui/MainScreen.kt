package app.scrollhalt.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scrollhalt.R
import app.scrollhalt.data.AppSettings

private val ScreenPadding = 20.dp

@Composable
fun MainScreen(
    status: ProtectionStatus,
    settings: AppSettings,
    pauseRemainingMillis: Long,
    versionName: String,
    onBlockingChange: (Boolean) -> Unit,
    onAllowSingleReelChange: (Boolean) -> Unit,
    onStartPause: (minutes: Int) -> Unit,
    onResumeNow: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var showHowItWorks by rememberSaveable { mutableStateOf(false) }
    var showPauseSheet by rememberSaveable { mutableStateOf(false) }
    val setupNeeded = status == ProtectionStatus.SERVICE_UNAVAILABLE

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onHowItWorks = { showHowItWorks = true }, onDiagnostics = onOpenDiagnostics)
            HeroCard(
                status = status,
                pauseRemainingMillis = pauseRemainingMillis,
                onPause = { showPauseSheet = true },
                onResumeNow = onResumeNow,
                onEnableBlocking = { onBlockingChange(true) },
                onTurnOn = onOpenAccessibility,
            )
            AnimatedVisibility(visible = setupNeeded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow(R.drawable.ic_lock, R.string.setup_restricted_title, R.string.setup_restricted_detail, onOpenAppInfo)
                    ActionRow(R.drawable.ic_battery, R.string.setup_samsung_title, R.string.setup_samsung_detail, onOpenAppInfo)
                }
            }
            ControlsCard(settings, enabled = !setupNeeded, onBlockingChange, onAllowSingleReelChange)
            Text(
                text = stringResource(R.string.version_label, versionName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }

    if (showHowItWorks) HowItWorksSheet(onDismiss = { showHowItWorks = false })
    if (showPauseSheet && status == ProtectionStatus.ACTIVE) {
        PauseSheet(
            onPick = { minutes ->
                showPauseSheet = false
                onStartPause(minutes)
            },
            onDismiss = { showPauseSheet = false },
        )
    }
}

@Composable
private fun Header(onHowItWorks: () -> Unit, onDiagnostics: () -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(BrandGreen),
        ) {
            // The launcher foreground's shapes fill the middle half of its 108-unit canvas.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(56.dp))
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.how_title)) },
                    onClick = {
                        menuOpen = false
                        onHowItWorks()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.diag_title)) },
                    onClick = {
                        menuOpen = false
                        onDiagnostics()
                    },
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    status: ProtectionStatus,
    pauseRemainingMillis: Long,
    onPause: () -> Unit,
    onResumeNow: () -> Unit,
    onEnableBlocking: () -> Unit,
    onTurnOn: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        ProtectionStatus.ACTIVE -> colors.primaryContainer to colors.onPrimaryContainer
        ProtectionStatus.PAUSED -> colors.secondaryContainer to colors.onSecondaryContainer
        ProtectionStatus.DISABLED, ProtectionStatus.SERVICE_UNAVAILABLE -> colors.surfaceVariant to colors.onSurface
    }
    val containerColor by animateColorAsState(container, label = "hero container")
    val contentColor by animateColorAsState(content, label = "hero content")
    val disabled = status == ProtectionStatus.DISABLED
    val icon = when (status) {
        ProtectionStatus.ACTIVE -> R.drawable.ic_shield_check
        ProtectionStatus.PAUSED -> R.drawable.ic_pause
        ProtectionStatus.DISABLED, ProtectionStatus.SERVICE_UNAVAILABLE -> R.drawable.ic_power
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(
                enabled = disabled,
                onClickLabel = stringResource(R.string.status_disabled_action),
                role = Role.Button,
                onClick = onEnableBlocking,
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(64.dp).clip(CircleShape).background(contentColor.copy(alpha = 0.12f)),
            ) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            when (status) {
                ProtectionStatus.ACTIVE -> {
                    HeroText(R.string.status_protecting, R.string.status_protecting_detail, contentColor)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = onPause,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = contentColor.copy(alpha = 0.16f), contentColor = contentColor),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.pause_button))
                    }
                }
                ProtectionStatus.PAUSED -> {
                    Text(stringResource(R.string.status_paused), style = MaterialTheme.typography.titleMedium)
                    Text(
                        countdown(pauseRemainingMillis),
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.status_paused_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onResumeNow,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.secondary, contentColor = colors.onSecondary),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.resume_now), fontWeight = FontWeight.SemiBold)
                    }
                }
                ProtectionStatus.DISABLED -> HeroText(R.string.status_disabled, R.string.status_disabled_detail, contentColor)
                ProtectionStatus.SERVICE_UNAVAILABLE -> {
                    Text(stringResource(R.string.status_setup), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onTurnOn, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.status_setup_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroText(@StringRes title: Int, @StringRes detail: Int, contentColor: Color) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = contentColor.copy(alpha = 0.8f), textAlign = TextAlign.Center)
}

/** mm:ss, rounded up so the display reaches 00:00 only when the pause ends. */
@Composable
private fun countdown(remainingMillis: Long): String {
    val seconds = (remainingMillis.coerceAtLeast(0) + 999) / 1_000
    return stringResource(R.string.countdown, seconds / 60, seconds % 60)
}

@Composable
private fun ActionRow(@DrawableRes icon: Int, @StringRes title: Int, @StringRes detail: Int, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
            IconBadge(icon)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ControlsCard(
    settings: AppSettings,
    enabled: Boolean,
    onBlockingChange: (Boolean) -> Unit,
    onAllowSingleReelChange: (Boolean) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            SwitchRow(stringResource(R.string.setting_block), null, settings.blockingEnabled, enabled, onBlockingChange)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = ScreenPadding))
            SwitchRow(
                title = stringResource(R.string.setting_single_reel),
                detail = stringResource(R.string.setting_single_reel_detail),
                checked = settings.allowSingleReel,
                enabled = enabled && settings.blockingEnabled,
                onChange = onAllowSingleReelChange,
            )
        }
    }
}

/** A whole-row switch: one touch target, announced as a switch. */
@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun IconBadge(@DrawableRes icon: Int, tint: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HowItWorksSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(stringResource(R.string.how_title), style = MaterialTheme.typography.titleLarge)
            listOf(
                R.drawable.ic_eye_off to R.string.how_tab,
                R.drawable.ic_close_circle to R.string.how_explore,
                R.drawable.ic_play_once to R.string.how_shared,
                R.drawable.ic_lock to R.string.how_private,
            ).forEach { (icon, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon)
                    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                }
            }
            Text(
                stringResource(R.string.how_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
