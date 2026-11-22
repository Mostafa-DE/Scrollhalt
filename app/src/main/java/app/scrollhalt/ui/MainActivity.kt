package app.scrollhalt.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.scrollhalt.BuildConfig
import app.scrollhalt.data.AppSettings
import app.scrollhalt.data.DeviceClock
import app.scrollhalt.data.SettingsRepository
import app.scrollhalt.data.StatsRepository
import app.scrollhalt.debug.DebugLog
import app.scrollhalt.protect.PauseState
import app.scrollhalt.protect.protectionMode
import app.scrollhalt.service.Comeback
import app.scrollhalt.service.ServiceState
import app.scrollhalt.stats.Days
import app.scrollhalt.stats.StatsState
import app.scrollhalt.stats.dashboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(this)
        val statsRepository = StatsRepository(this)
        setContent {
            ScrollhaltTheme {
                App(repository, statsRepository)
            }
        }
    }
}

@Composable
private fun App(repository: SettingsRepository, statsRepository: StatsRepository) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val connected by ServiceState.connected.collectAsStateWithLifecycle()
    val stats by statsRepository.stats.collectAsStateWithLifecycle(initialValue = StatsState())
    var enabled by remember { mutableStateOf(isServiceEnabled(context)) }
    var turnsBackOnByItself by remember { mutableStateOf(Comeback.canTurnBackOn(context)) }
    var notificationsAllowed by remember { mutableStateOf(areNotificationsOn(context)) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    val clock = remember { DeviceClock(context) }
    var now by remember { mutableStateOf(clock.now()) }
    val pause = settings.pause
    val lifecycleOwner = LocalLifecycleOwner.current
    val today = Days.today(now)
    val board = remember(stats, today) { dashboard(stats, today) }
    val offForApp = settings.offForApp
    val offForLabel = remember(offForApp?.packageName) {
        offForApp?.packageName?.let { packageName ->
            try {
                context.packageManager.run { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
        }
    }
    // Asked only from the Allow notifications button. Once Android stops showing the dialog
    // (denied twice), App info is opened instead, where notifications can be allowed.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = areNotificationsOn(context)
        if (!granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false) {
            context.startActivity(appInfoIntent(context))
        }
    }

    LifecycleResumeEffect(Unit) {
        enabled = isServiceEnabled(context)
        turnsBackOnByItself = Comeback.canTurnBackOn(context)
        notificationsAllowed = areNotificationsOn(context)
        now = clock.now()
        onPauseOrDispose { }
    }

    // Countdown while a pause runs and the screen is visible; it also clears a pause that
    // ended while the app was away. No ticking without a pause.
    LaunchedEffect(pause, lifecycleOwner) {
        if (pause == null) return@LaunchedEffect
        if (!pause.isOnBootOf(clock.now())) {
            repository.rebasePause(clock.now())
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = clock.now()
                val remaining = pause.remaining(now)
                if (remaining <= 0) {
                    repository.clearPause(expected = pause)
                    break
                }
                delay(remaining % 1_000 + 1)
            }
        }
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
            status = protectionStatus(enabled, connected, protectionMode(settings.blockingEnabled, pause, now), offForApp = offForApp != null),
            settings = settings,
            pauseRemainingMillis = pause?.remaining(now) ?: 0,
            offForLabel = offForLabel,
            backOnAtMillis = offForApp?.takeIf { !it.isDue(now) }?.backAtWall(now),
            turnsBackOnByItself = turnsBackOnByItself,
            notificationsAllowed = notificationsAllowed,
            board = board,
            versionName = BuildConfig.VERSION_NAME,
            onBlockingChange = { scope.launch { repository.setBlockingEnabled(it) } },
            onAllowSingleReelChange = { scope.launch { repository.setAllowSingleReel(it) } },
            onTurnOffForChange = { packageName, listed -> scope.launch { repository.setTurnOffFor(packageName, listed) } },
            onAllowNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(appInfoIntent(context))
                }
            },
            onStartPause = { minutes ->
                scope.launch { repository.startPause(PauseState.start(clock.now(), minutes * MILLIS_PER_MINUTE)) }
            },
            onResumeNow = { scope.launch { repository.clearPause() } },
            onTurnBackOnNow = {
                if (Comeback.turnBackOn(context)) enabled = isServiceEnabled(context) else context.startActivity(accessibilitySettingsIntent())
            },
            onOpenAccessibility = { context.startActivity(accessibilitySettingsIntent()) },
            onOpenAppInfo = { context.startActivity(appInfoIntent(context)) },
            onOpenDiagnostics = { showDebug = true },
            onResetStats = { scope.launch { statsRepository.reset(clock.now(), tracking = connected) } },
        )
    }
}

private const val MILLIS_PER_MINUTE: Long = 60_000

/** Below Android 13 there is no permission: notifications are on unless switched off in App info. */
private fun areNotificationsOn(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
