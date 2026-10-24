package app.scrollhalt.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scrollhalt.R
import app.scrollhalt.detect.BlockCategory
import app.scrollhalt.stats.BlockedDay
import app.scrollhalt.stats.ChartMetric
import app.scrollhalt.stats.Dashboard
import app.scrollhalt.stats.DayBar
import app.scrollhalt.stats.DaySummary
import app.scrollhalt.stats.ReelsDay
import app.scrollhalt.stats.averageMillis
import app.scrollhalt.stats.hoursMinutes
import java.time.format.TextStyle as DayStyle
import java.util.Date

private val Numbers = TextStyle(fontFeatureSettings = "tnum")
private val BarMaxHeight = 120.dp

/** Today's tiles, blocked attempts and the 7-day chart, in the order of the main screen. */
@Composable
fun DashboardSection(board: Dashboard, paused: Boolean) {
    var category by rememberSaveable { mutableStateOf<BlockCategory?>(null) }
    var showReels by rememberSaveable { mutableStateOf(false) }
    val today = board.today ?: DaySummary()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TodayHeader(board.today, paused)
        TodayTiles(today, onReels = { showReels = true })
        BlockedCard(today.blocked, onCategory = { category = it })
        WeekCard(board.week)
    }

    category?.let { CategorySheet(it, today.blocked, onDismiss = { category = null }) }
    if (showReels) ReelsSheet(today.reels, onDismiss = { showReels = false })
}

@Composable
private fun TodayHeader(today: DaySummary?, paused: Boolean) {
    val context = LocalContext.current
    val since = today?.trackedSinceWall
    val note = when {
        paused -> stringResource(R.string.today_tracking_paused)
        since != null -> stringResource(R.string.today_tracked_since, DateFormat.getTimeFormat(context).format(Date(since)))
        else -> stringResource(R.string.today_not_tracked)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
        Text(
            stringResource(R.string.today_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Text(note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun TodayTiles(today: DaySummary, onReels: () -> Unit) {
    val instagram = today.instagram
    val reels = today.reels
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TileRow {
            StatTile(
                label = stringResource(R.string.tile_opened),
                value = "${instagram.openings}",
                suffix = pluralStringResource(R.plurals.tile_times, instagram.openings),
                detail = pluralStringResource(R.plurals.tile_sessions, instagram.sessions, instagram.sessions),
                modifier = it,
            )
            StatTile(
                label = stringResource(R.string.tile_instagram_time),
                value = duration(instagram.timeMillis),
                detail = stringResource(R.string.tile_time_detail, duration(instagram.averageMillis()), duration(instagram.longestMillis)),
                modifier = it,
            )
        }
        TileRow {
            StatTile(
                label = stringResource(R.string.tile_reels),
                value = stringResource(R.string.tile_reels_value, duration(reels.totalMillis)),
                detail = if (reels.pausedMillis > 0) {
                    stringResource(R.string.tile_reels_paused, duration(reels.pausedMillis))
                } else {
                    stringResource(R.string.tile_reels_estimated)
                },
                modifier = it,
                onClick = onReels,
            )
            StatTile(
                label = stringResource(R.string.tile_watched),
                value = "${reels.watched}",
                detail = stringResource(R.string.tile_watched_detail, duration(reels.longestMillis)),
                modifier = it,
                onClick = onReels,
            )
        }
    }
}

/** Two tiles side by side, as tall as the taller one. */
@Composable
private fun TileRow(content: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        content(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String, modifier: Modifier, suffix: String? = null, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
            Text(label, fontSize = 13.sp, color = colors.onSurfaceVariant)
            Text(
                buildAnnotatedString {
                    append(value)
                    if (suffix != null) {
                        withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)) { append(" $suffix") }
                    }
                },
                style = Numbers,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(detail, fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
    }
    if (onClick == null) {
        Surface(shape = MaterialTheme.shapes.medium, color = colors.surface, modifier = modifier) { body() }
    } else {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = colors.surface, modifier = modifier) { body() }
    }
}

@Composable
private fun BlockedCard(blocked: BlockedDay, onCategory: (BlockCategory) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp, end = 8.dp)) {
                Text(
                    stringResource(R.string.blocked_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                Text(
                    "${blocked.total}",
                    style = Numbers,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            BlockCategory.entries.forEachIndexed { index, category ->
                if (index > 0) HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(end = 8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button) { onCategory(category) }
                        .padding(end = 8.dp),
                ) {
                    Text(stringResource(category.label), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${blocked.of(category).detected}", style = Numbers, fontSize = 15.sp)
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun WeekCard(week: List<DayBar>) {
    var metric by rememberSaveable { mutableStateOf(ChartMetric.INSTAGRAM_TIME) }
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.week_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            // Wraps on narrow screens and large fonts instead of clipping the last chip.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartMetric.entries.forEach { MetricChip(stringResource(it.label), selected = it == metric, onClick = { metric = it }) }
            }
            Bars(week, metric)
            if (week.any { !it.tracked }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(width = 14.dp, height = 10.dp).dashedOutline(colors.outline, 3.dp))
                    Text(stringResource(R.string.week_not_tracked), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) colors.primaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.height(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

/** One column per day: value, bar (dashed when not tracked) and day name, today highlighted. */
@Composable
private fun Bars(week: List<DayBar>, metric: ChartMetric) {
    val colors = MaterialTheme.colorScheme
    val max = week.filter { it.tracked }.maxOfOrNull { metric.of(it) }?.takeIf { it > 0 } ?: 1
    val locale = LocalConfiguration.current.locales[0]
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth(),
    ) {
        week.forEachIndexed { index, bar ->
            val today = index == week.lastIndex
            val highlight = if (today) colors.onPrimaryContainer else colors.onSurfaceVariant
            val weight = if (today) FontWeight.SemiBold else FontWeight.Normal
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
            ) {
                Text(chartLabel(metric, bar), fontSize = 11.sp, color = highlight, fontWeight = weight, maxLines = 1, style = Numbers)
                if (bar.tracked) {
                    val height = maxOf(4.dp, BarMaxHeight * (metric.of(bar).toFloat() / max))
                    Box(
                        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp))
                            .background(if (today) colors.primary else colors.primary.copy(alpha = 0.35f)),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(24.dp).dashedOutline(colors.outline, 8.dp))
                }
                Text(
                    if (today) stringResource(R.string.week_today) else bar.date.dayOfWeek.getDisplayName(DayStyle.SHORT, locale),
                    fontSize = 12.sp,
                    color = highlight,
                    fontWeight = weight,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun chartLabel(metric: ChartMetric, bar: DayBar): String {
    if (!bar.tracked) return stringResource(R.string.chart_na)
    val value = metric.of(bar)
    return when (metric) {
        ChartMetric.OPENS, ChartMetric.BLOCKED -> "$value"
        ChartMetric.INSTAGRAM_TIME, ChartMetric.REELS -> {
            val (hours, minutes) = hoursMinutes(value)
            if (hours > 0) stringResource(R.string.chart_hm, hours, minutes) else stringResource(R.string.chart_m, minutes)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(category: BlockCategory, blocked: BlockedDay, onDismiss: () -> Unit) {
    val outcome = blocked.of(category)
    DetailSheet(stringResource(category.label), stringResource(category.detail), onDismiss) {
        DetailRow(stringResource(R.string.blocked_detected), stringResource(R.string.blocked_detected_detail), "${outcome.detected}")
        DetailRow(stringResource(R.string.blocked_action), stringResource(R.string.blocked_action_detail), "${outcome.actionTaken}")
        DetailRow(stringResource(R.string.blocked_confirmed), stringResource(R.string.blocked_confirmed_detail), "${outcome.confirmed}")
        if (blocked.sentToLauncher > 0) {
            Text(
                stringResource(R.string.blocked_launcher, blocked.sentToLauncher),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReelsSheet(reels: ReelsDay, onDismiss: () -> Unit) {
    DetailSheet(stringResource(R.string.reels_sheet_title), stringResource(R.string.reels_caveat), onDismiss) {
        DetailRow(stringResource(R.string.reels_protected), null, duration(reels.protectedMillis))
        DetailRow(stringResource(R.string.reels_paused), null, duration(reels.pausedMillis))
        DetailRow(stringResource(R.string.reels_disabled), null, duration(reels.disabledMillis))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DetailRow(stringResource(R.string.reels_tab), null, duration(reels.reelsTabMillis))
        DetailRow(stringResource(R.string.reels_single), null, duration(reels.singleReelMillis))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DetailRow(stringResource(R.string.reels_sessions), null, "${reels.sessions}")
        DetailRow(stringResource(R.string.tile_watched), null, "${reels.watched}")
        DetailRow(stringResource(R.string.reels_longest), null, duration(reels.longestMillis))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(title: String, intro: String, onDismiss: () -> Unit, rows: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows()
        }
    }
}

@Composable
private fun DetailRow(title: String, detail: String?, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Numbers))
    }
}

/** "1 h 12 m", "5 m", or "< 1 m" for a few seconds. */
@Composable
private fun duration(millis: Long): String {
    if (millis in 1 until 60_000) return stringResource(R.string.duration_under_minute)
    val (hours, minutes) = hoursMinutes(millis)
    return if (hours > 0) stringResource(R.string.duration_hm, hours, minutes) else stringResource(R.string.duration_m, minutes)
}

private fun Modifier.dashedOutline(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

@get:StringRes
private val BlockCategory.label: Int
    get() = when (this) {
        BlockCategory.REELS_TAB -> R.string.blocked_reels_tab
        BlockCategory.EXPLORE_REEL -> R.string.blocked_explore
        BlockCategory.NEXT_REEL -> R.string.blocked_next
    }

@get:StringRes
private val BlockCategory.detail: Int
    get() = when (this) {
        BlockCategory.REELS_TAB -> R.string.blocked_reels_tab_detail
        BlockCategory.EXPLORE_REEL -> R.string.blocked_explore_detail
        BlockCategory.NEXT_REEL -> R.string.blocked_next_detail
    }

@get:StringRes
private val ChartMetric.label: Int
    get() = when (this) {
        ChartMetric.INSTAGRAM_TIME -> R.string.chip_time
        ChartMetric.OPENS -> R.string.chip_opens
        ChartMetric.REELS -> R.string.chip_reels
        ChartMetric.BLOCKED -> R.string.chip_blocked
    }
