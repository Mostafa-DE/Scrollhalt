package app.scrollhalt.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollhalt.BuildConfig
import app.scrollhalt.data.AppSettings
import app.scrollhalt.data.SettingsRepository
import app.scrollhalt.debug.DebugLog
import app.scrollhalt.service.ServiceState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(this)
        setContent {
            ScrollhaltTheme {
                App(repository)
            }
        }
    }
}

@Composable
private fun App(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val connected by ServiceState.connected.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(isServiceEnabled(context)) }
    var showDebug by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        enabled = isServiceEnabled(context)
        onPauseOrDispose { }
    }

    if (showDebug) {
        BackHandler { showDebug = false }
        val entries by DebugLog.entries.collectAsStateWithLifecycle()
        val snapshot by DebugLog.snapshot.collectAsStateWithLifecycle()
        DebugScreen(
            entries = entries,
            snapshot = snapshot,
            debugCapture = settings.debugCapture,
            onDebugCaptureChange = { scope.launch { repository.setDebugCapture(it) } },
            onClear = DebugLog::clear,
            onBack = { showDebug = false },
        )
    } else {
        MainScreen(
            status = protectionStatus(enabled, connected, settings.blockingEnabled),
            settings = settings,
            versionName = BuildConfig.VERSION_NAME,
            onBlockingChange = { scope.launch { repository.setBlockingEnabled(it) } },
            onAllowSingleReelChange = { scope.launch { repository.setAllowSingleReel(it) } },
            onOpenAccessibility = { context.startActivity(accessibilitySettingsIntent()) },
            onOpenAppInfo = { context.startActivity(appInfoIntent(context)) },
            onOpenDiagnostics = { showDebug = true },
        )
    }
}
