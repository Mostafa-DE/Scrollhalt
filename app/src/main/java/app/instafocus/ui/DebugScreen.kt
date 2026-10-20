package app.instafocus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.instafocus.R
import app.instafocus.debug.DebugEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    entries: List<DebugEntry>,
    snapshot: List<String>,
    debugCapture: Boolean,
    onDebugCaptureChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    val clipLabel = stringResource(R.string.debug_clip_label)
    val copiedMessage = stringResource(R.string.debug_copied)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.debug_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SwitchRow(
                    title = stringResource(R.string.debug_capture),
                    detail = stringResource(R.string.debug_capture_detail),
                    checked = debugCapture,
                    enabled = true,
                    onChange = onDebugCaptureChange,
                )
            }
            item {
                Text(stringResource(R.string.debug_snapshot_count, snapshot.size), style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = snapshot.isNotEmpty(),
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, snapshot.joinToString("\n")))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        },
                    ) { Text(stringResource(R.string.debug_copy)) }
                    OutlinedButton(onClick = onClear) { Text(stringResource(R.string.debug_clear)) }
                }
            }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.debug_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(entries.asReversed()) { entry ->
                Column {
                    Text(
                        "${timeFormat.format(Date(entry.timeMillis))}  ${entry.eventType}  ${entry.screen}  ${entry.decision}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(entry.reason, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
