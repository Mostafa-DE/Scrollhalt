package app.scrollhalt.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.scrollhalt.R
import app.scrollhalt.debug.DebugEntry
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
    val clipLabel = stringResource(R.string.diag_clip_label)
    val copiedMessage = stringResource(R.string.diag_copied)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.diag_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        SwitchRow(stringResource(R.string.diag_capture), stringResource(R.string.diag_ids_only), debugCapture, true, onDebugCaptureChange)
                        Text(
                            stringResource(R.string.diag_snapshot_count, snapshot.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                            FilledTonalButton(
                                enabled = snapshot.isNotEmpty(),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, snapshot.joinToString("\n")))
                                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                                },
                            ) { Text(stringResource(R.string.diag_copy)) }
                            FilledTonalButton(onClick = onClear, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.diag_clear))
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
                        if (entries.isEmpty()) {
                            Text(stringResource(R.string.diag_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        entries.asReversed().forEach { entry ->
                            Column {
                                Text(
                                    "${timeFormat.format(Date(entry.timeMillis))}  ${entry.eventType}  ${entry.decision}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "${entry.screen}  ${entry.reason}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
