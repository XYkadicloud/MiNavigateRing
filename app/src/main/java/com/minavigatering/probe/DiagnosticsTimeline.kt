package com.minavigatering.probe

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.minavigatering.probe.navigation.NavigationSettings
import com.minavigatering.probe.navigation.isIgnoringBatteryOptimizations
import com.minavigatering.probe.navigation.miFitnessAppDetailsIntent
import com.minavigatering.probe.navigation.requestIgnoreBatteryOptimizations
import com.minavigatering.probe.navigation.startActivitySafely
import com.minavigatering.probe.transport.EntryTimestamp
import com.minavigatering.probe.transport.RuntimeTimeline
import com.minavigatering.probe.transport.TimelineEntry
import com.minavigatering.probe.transport.TimelineEvent
import com.minavigatering.probe.transport.TimelineStatistics
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断页的运行时间线与后台策略区（工作项 W-710，W-711 分区与“最近一次”）。
 *
 * 本文件只放 Composable 与它们专用的私有格式 helper（工作项 W-711）：不新增 ViewModel，也不复制或
 * 重写 BackgroundPolicy.kt 里的安全启动 helper（W-709 已完成，本单只接线）。
 *
 * 显示的每个字段都已脱敏：类别枚举、四布尔、毫秒、墙钟时间，以及协议/SDK/ACK id 的
 * 后 6 位。这里永远不出现通知正文、路名、坐标、账号、节点 id 或密钥。字段含义与
 * 仍未被验证的实机项见 docs/lockscreen-minimal.md。
 *
 * 两个区域都随现有诊断页的滚动 Column 一起滚动，不建立嵌套的独立滚动容器。
 */

/** 最近 60 秒的计数摘要：通知数、发送帧数、ACK 数与 ACK 往返毫秒。 */
@Composable
private fun TimelineSummaryLine(statistics: TimelineStatistics) {
    val window = statistics.window
    val latestMs = statistics.ackRtt.latestMs
    val averageMs = statistics.ackRtt.averageMs
    val latestText = latestMs?.let { "${it}ms" } ?: "无样本"
    val averageText = averageMs?.let { "${it}ms" } ?: "无样本"
    Text(
        "最近 60 秒：通知 ${window.notifications} · 发送帧 ${window.framesSent}" +
            " · ACK ${window.acks} · RTT 最近 $latestText / 平均 $averageText",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 最近若干条时间线，新到旧；每行只有墙钟时间、elapsed 毫秒、类别与脱敏 detail。 */
@Composable
private fun TimelineEntryList(entries: List<TimelineEntry>) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column {
        if (entries.isEmpty()) {
            Text("尚无时间线记录。", style = MaterialTheme.typography.bodySmall)
        }
        entries.forEach { entry ->
            val wallText = clock.format(Date(entry.timestamp.wallMs))
            val elapsedText = "${entry.timestamp.elapsedMs}ms"
            Text(
                "$wallText ｜ +$elapsedText ｜ ${entry.event} ｜ ${entry.detail}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** “最近一次”的一行定义：一个标签对应一类或多类事件（Google 地图四类合并成一行）。 */
private data class RecentCategory(val label: String, val events: List<TimelineEvent>)

/** 14 行“最近一次”，顺序固定；Google 地图四类取最近一条并把四类累计次数求和。 */
private val RECENT_CATEGORIES = listOf(
    RecentCategory("发送轮次", listOf(TimelineEvent.SEND_ROUND)),
    RecentCategory("发送帧", listOf(TimelineEvent.FRAME_SENT)),
    RecentCategory("SDK Task成功", listOf(TimelineEvent.SDK_TASK_SUCCEEDED)),
    RecentCategory("SDK Task失败", listOf(TimelineEvent.SDK_TASK_FAILED)),
    RecentCategory("ACK", listOf(TimelineEvent.ACK_RECEIVED)),
    RecentCategory(
        "Google 地图通知",
        listOf(
            TimelineEvent.MAPS_NOTIFICATION_ACTIVE,
            TimelineEvent.MAPS_NOTIFICATION_UNRECOGNIZED,
            TimelineEvent.MAPS_NOTIFICATION_ENDED,
            TimelineEvent.MAPS_NOTIFICATION_IGNORED,
        ),
    ),
    RecentCategory("监听连上", listOf(TimelineEvent.LISTENER_CONNECTED)),
    RecentCategory("监听断开", listOf(TimelineEvent.LISTENER_DISCONNECTED)),
    RecentCategory("屏幕灭", listOf(TimelineEvent.SCREEN_OFF)),
    RecentCategory("屏幕亮", listOf(TimelineEvent.SCREEN_ON)),
    RecentCategory("解锁", listOf(TimelineEvent.SCREEN_UNLOCK)),
    RecentCategory("Idle变化", listOf(TimelineEvent.DEVICE_IDLE_CHANGED)),
    RecentCategory("省电变化", listOf(TimelineEvent.POWER_SAVE_CHANGED)),
    RecentCategory("恢复结果", listOf(TimelineEvent.RECOVERY_RESULT)),
)

/** 一行“最近一次”的文本；没有记录时显示“尚无”，不显示 0 或推测时间。 */
private fun recentOnceText(
    timeline: RuntimeTimeline,
    category: RecentCategory,
    nowElapsedMs: Long,
    clock: SimpleDateFormat,
): String {
    val latest = category.events.mapNotNull { timeline.lastSeen(it) }.maxByOrNull { it.elapsedMs }
        ?: return "${category.label}：尚无"
    val total = category.events.sumOf { timeline.totalCount(it) }
    return "${category.label}：${clock.format(Date(latest.wallMs))}（${secondsAgo(latest, nowElapsedMs)} 秒前）" +
        "累计 $total 次"
}

/** 距最近一次的秒数：单调时钟相减，不用墙钟差值，未来记录按 0 处理。 */
private fun secondsAgo(latest: EntryTimestamp, nowElapsedMs: Long): Long =
    ((nowElapsedMs - latest.elapsedMs).coerceAtLeast(0L)) / 1_000L

/** 14 行“最近一次”，随外层诊断一起滚动。 */
@Composable
private fun RecentOnceLines(timeline: RuntimeTimeline, nowElapsedMs: Long) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column {
        RECENT_CATEGORIES.forEach { category ->
            Text(
                recentOnceText(timeline, category, nowElapsedMs, clock),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 每秒刷新计数摘要、14 行“最近一次”、40 条系统事件与 20 条最近传输。
 *
 * 摘要的“发送帧”只来自 FRAME_SENT（一次入队报文），不等于 SEND_ROUND（一次心跳循环
 * 轮次），也不等于 SDK Task 成功；ACK 计数只来自手环回执。窗口内没有 ACK 时 RTT 显示
 * “无样本”，不显示 0。“N 秒前”用 SystemClock.elapsedRealtime 减记录时的 elapsed，
 * 不用墙钟算差。两块列表都随外层滚动，不建立嵌套滚动容器。
 */
@Composable
fun RuntimeTimelineDiagnostics() {
    val timeline = RuntimeTimeline.shared
    val refreshIntervalMs = 1_000L
    val stateRows = 40
    val trafficRows = 20
    var nowElapsedMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var statistics by remember { mutableStateOf(timeline.statistics(nowElapsedMs)) }
    var stateEntries by remember { mutableStateOf(timeline.stateSnapshot(stateRows)) }
    var trafficEntries by remember { mutableStateOf(timeline.trafficSnapshot(trafficRows)) }

    LaunchedEffect(timeline) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            nowElapsedMs = now
            statistics = timeline.statistics(now)
            stateEntries = timeline.stateSnapshot(stateRows)
            trafficEntries = timeline.trafficSnapshot(trafficRows)
            delay(refreshIntervalMs)
        }
    }

    Text("运行时间线（记录在内存，进程退出即丢）", style = MaterialTheme.typography.titleSmall)
    TimelineSummaryLine(statistics)
    Text("最近一次（每秒刷新，“N 秒前”取自单调时钟）", style = MaterialTheme.typography.bodySmall)
    RecentOnceLines(timeline, nowElapsedMs)
    Text("系统事件 最近 $stateRows 条（新到旧）", style = MaterialTheme.typography.bodySmall)
    TimelineEntryList(stateEntries)
    Text("最近传输 最近 $trafficRows 条（新到旧）", style = MaterialTheme.typography.bodySmall)
    TimelineEntryList(trafficEntries)
}

/**
 * 每秒刷新电池优化豁免状态与最近一次后台恢复结果。
 *
 * 两个按钮只调用 BackgroundPolicy.kt 的既有组合 helper：请求豁免失败会自动回退到电池
 * 优化设置页；“打开小米运动健康应用信息”打开官方 App 的详情页，供用户自行检查其后台
 * 策略。按下按钮不等于授权已经生效，是否生效只看上面这行“电池优化豁免：是/否”。
 */
@Composable
fun BackgroundPolicyDiagnostics() {
    val context = LocalContext.current
    val settings = remember { NavigationSettings(context) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val refreshIntervalMs = 1_000L
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var restoreResult by remember { mutableStateOf(settings.lastRestoreText) }
    var restoreAtMs by remember { mutableStateOf(settings.lastRestoreAtMs) }

    LaunchedEffect(context) {
        while (true) {
            exempt = isIgnoringBatteryOptimizations(context)
            restoreResult = settings.lastRestoreText
            restoreAtMs = settings.lastRestoreAtMs
            delay(refreshIntervalMs)
        }
    }

    val restoreResultText = restoreResult.ifEmpty { "尚无记录" }
    val restoreAtText = if (restoreAtMs <= 0L) "尚无记录" else clock.format(Date(restoreAtMs))
    Text("后台策略诊断（每秒刷新，从设置返回后无需重进页面）", style = MaterialTheme.typography.titleSmall)
    Text("电池优化豁免：${if (exempt) "是" else "否"}", style = MaterialTheme.typography.bodySmall)
    Text("最近一次后台恢复：$restoreResultText（$restoreAtText）", style = MaterialTheme.typography.bodySmall)
    Text(
        "说明：ok / failed:异常类名 / skipped 只是启动调用的结果，不表示锁屏链路已生效。",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = { requestIgnoreBatteryOptimizations(context) }) { Text("允许后台运行") }
    Button(onClick = {
        val intent = miFitnessAppDetailsIntent(context)
        // Neither companion variant installed: fall back to the app list, never a dead button.
        if (intent == null) {
            startActivitySafely(context, Intent(Settings.ACTION_APPLICATION_SETTINGS))
        } else {
            startActivitySafely(context, intent)
        }
    }) {
        Text("打开小米运动健康应用信息")
    }
}
