package app.scrollhalt.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.scrollhalt.R
import app.scrollhalt.detect.InstagramRules
import app.scrollhalt.protect.OffForApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val IconSize = 40.dp

private class LaunchableApp(val packageName: String, val label: String, val icon: ImageBitmap)

/**
 * "Turn off for these apps": the apps with a launcher icon (visible through the MAIN/LAUNCHER
 * query in the manifest, no QUERY_ALL_PACKAGES), with a search field. [onChange] adds or removes
 * one. Scrollhalt and Instagram are not offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(selected: Set<String>, onChange: (packageName: String, listed: Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { IconSize.roundToPx() }
    val apps by produceState<List<LaunchableApp>?>(null) {
        value = withContext(Dispatchers.Default) { launchableApps(context, iconPx) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = apps?.filter { it.label.contains(query.trim(), ignoreCase = true) || it.packageName.contains(query.trim(), ignoreCase = true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.setting_turn_off), style = MaterialTheme.typography.titleLarge)
            val minutes = (OffForApp.COMEBACK_MILLIS / 60_000).toInt()
            Text(
                pluralStringResource(R.plurals.turn_off_sheet_detail, minutes, minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.turn_off_search)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            shown == null -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                CircularProgressIndicator()
            }
            shown.isEmpty() -> Text(
                stringResource(R.string.turn_off_none_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)) {
                items(shown, key = { it.packageName }) { app ->
                    AppRow(app, checked = app.packageName in selected, onChange = { onChange(app.packageName, it) })
                }
            }
        }
    }
}

/** A whole-row checkbox, like [SwitchRow]: one touch target, announced as a checkbox. */
@Composable
private fun AppRow(app: LaunchableApp, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(IconSize))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** One entry per app with a launcher activity, sorted by name; icons scaled to [iconPx]. */
private fun launchableApps(context: Context, iconPx: Int): List<LaunchableApp> {
    val packageManager = context.packageManager
    val excluded = setOf(context.packageName, InstagramRules.PACKAGE)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName !in excluded }
        .map { LaunchableApp(it.packageName, it.loadLabel(packageManager).toString(), it.loadIcon(packageManager).toBitmap(iconPx, iconPx).asImageBitmap()) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}
