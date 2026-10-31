package com.minavigatering.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.minavigatering.probe.navigation.NavigationSettings
import com.minavigatering.probe.transport.WearLaunchProbe
import com.minavigatering.probe.transport.XiaomiWearableTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Refresh interval of the last auto launch line: one second, like BackgroundPolicyDiagnostics. */
private const val AUTO_LAUNCH_REFRESH_INTERVAL_MS = 1_000L

/**
 * Diagnostic entry point for the one-shot wear launch experiment (work order W-739).
 *
 * The run starts only from the button below: nothing here runs on composition, and no
 * service, broadcast receiver or notification listener calls into [WearLaunchProbe].
 * Result lines live in memory only; a new run clears the previous ones.
 * The auto launch switch above the button (work order W-744) only reads and writes
 * [NavigationSettings.autoLaunchBand]; it does not start the experiment.
 */
@Composable
fun WearLaunchDiagnostics(transport: XiaomiWearableTransport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AutoLaunchBandSection()
        Text("拉起手环导航页（实验）", style = MaterialTheme.typography.titleSmall)
        Text(
            "先让手环退出导航页回到表盘再点；每个候选之间会停 4 秒，" +
                "请看手环有没有亮屏、有没有打开地图环页面，并把看到的告诉检视者",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = !running,
            onClick = {
                if (running) return@Button
                lines.clear()
                running = true
                scope.launch {
                    try {
                        val probe = WearLaunchProbe(context.applicationContext, transport)
                        probe.run(onLine = { line -> lines.add(line) })
                    } finally {
                        running = false
                    }
                }
            },
        ) { Text(if (running) "运行中…" else "拉起手环导航页（实验）") }
        lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Auto launch explanation plus its switch, shared with the home switch's preference key. */
@Composable
private fun AutoLaunchBandSection() {
    val context = LocalContext.current
    val settings = remember { NavigationSettings(context) }
    var autoLaunchBand by remember { mutableStateOf(settings.autoLaunchBand) }

    Text("开始导航时自动拉起手环导航页", style = MaterialTheme.typography.bodySmall)
    Switch(
        checked = autoLaunchBand,
        onCheckedChange = { value ->
            autoLaunchBand = value
            settings.autoLaunchBand = value
        },
    )
    LastAutoLaunchLine(settings)
}

/** Last auto launch outcome, refreshed once per second; no record at all reads as 尚无. */
@Composable
private fun LastAutoLaunchLine(settings: NavigationSettings) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var text by remember { mutableStateOf(settings.lastAutoLaunchText) }
    var atMs by remember { mutableStateOf(settings.lastAutoLaunchAtMs) }

    LaunchedEffect(settings) {
        while (true) {
            text = settings.lastAutoLaunchText
            atMs = settings.lastAutoLaunchAtMs
            delay(AUTO_LAUNCH_REFRESH_INTERVAL_MS)
        }
    }

    val line = if (text.isEmpty() || atMs <= 0L) "最近一次自动拉起：尚无"
    else "最近一次自动拉起：$text（${clock.format(Date(atMs))}）"
    Text(line, style = MaterialTheme.typography.bodySmall)
}
