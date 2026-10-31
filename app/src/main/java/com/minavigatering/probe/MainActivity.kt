package com.minavigatering.probe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.minavigatering.probe.WearLaunchDiagnostics
import com.minavigatering.probe.engine.NavigationEngine
import com.minavigatering.probe.protocol.NavCodec
import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import com.minavigatering.probe.transport.ProbeActions
import com.minavigatering.probe.transport.ProbeForegroundService
import com.minavigatering.probe.transport.ProbeRuntime
import com.minavigatering.probe.transport.SendPhase
import com.minavigatering.probe.transport.SendResult
import com.minavigatering.probe.transport.XiaomiWearableTransport

/**
 * Minimal probe page. It exists to exercise the message channel and protocol states,
 * not to be the product UI (PROJECT_SPEC.md section 8, V1)。
 *
 * Sending state and watch-receipt state are shown as two separate lines: a queued channel
 * task or a successful SDK `Task` is *not* a watch acknowledgement (work order W-618).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One transport per process, shared with the foreground service so both use the
        // same refreshed node and the same inbound listener registration.
        val transport = ProbeRuntime.transport(applicationContext)
        // Diagnostics flow into the shared session so the page can read host service,
        // node, permission and the last SDK error (work order W-625).
        transport.attachDiagnostics(ProbeRuntime.session)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavigationScreen(transport)
                }
            }
        }
    }

    override fun onDestroy() {
        // Leaving the page must not leave a stacked service-connection listener behind.
        ProbeRuntime.transport(applicationContext).unregisterDiagnostics()
        super.onDestroy()
    }
}

@Composable
fun ProbeScreen(transport: XiaomiWearableTransport) {
    val context = LocalContext.current
    val session = ProbeRuntime.session
    val engine = remember { NavigationEngine() }
    var lastEnvelope by remember { mutableStateOf<String?>(null) }
    var sendText by remember { mutableStateOf(session.lastSendText) }
    var ackText by remember { mutableStateOf(session.lastAckText) }
    var linkText by remember { mutableStateOf(NavProtocol.LINK_CONNECTING) }
    var lastStatus by remember { mutableStateOf("（尚未发送）") }
    var hostText by remember { mutableStateOf(session.hostText) }
    var nodeText by remember { mutableStateOf(session.nodeText) }
    var permissionText by remember { mutableStateOf(session.permissionText) }
    var sdkErrorText by remember { mutableStateOf(session.sdkErrorText) }

    fun refreshFromSession() {
        sendText = session.lastSendText
        ackText = session.lastAckText
    }

    fun refreshDiagnostics() {
        hostText = session.hostText
        nodeText = session.nodeText
        permissionText = session.permissionText
        sdkErrorText = session.sdkErrorText
    }

    /**
     * Runs the read-only channel diagnostics.
     *
     * It never mints a receipt: these lines only describe the phone side. The device
     * manager permission is *checked* here, never requested — an automatic grant prompt
     * on page entry is explicitly out of scope.
     */
    fun runChannelDiagnostics() {
        transport.attachDiagnostics(session)
        transport.registerDiagnostics()
        // Until the diagnostics come back the link must not claim to be connected.
        linkText = NavProtocol.LINK_CONNECTING
        transport.queryServiceApiLevel { _, _ ->
            runOnUiThreadCompat { refreshDiagnostics() }
        }
        transport.refreshNode { node ->
            if (node != null) transport.ensureListening()
            // Records "no node, checkPermission not called" when there is no node.
            transport.checkDeviceManagerPermission { _, _ ->
                runOnUiThreadCompat { refreshDiagnostics() }
            }
            runOnUiThreadCompat { refreshDiagnostics() }
        }
    }

    // Auto-run once on page entry: host service API level, connection listener and node
    // refresh, then a permission *check* only. No requestPermission here.
    LaunchedEffect(Unit) { runChannelDiagnostics() }

    // The watch receipt only ever comes from the session's inbound path. The activity is
    // handed inbound messages by ProbeRuntime, so wrap the handler to hop to the UI thread.
    transport.setInboundHandler { message ->
        val accepted = session.onInbound(message)
        if (accepted) {
            runOnUiThreadCompat {
                ackText = session.lastAckText
                sendText = session.lastSendText
            }
        }
    }

    var pendingStartAction by remember { mutableStateOf(ProbeActions.START) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            context.startForegroundService(
                Intent(context, ProbeForegroundService::class.java)
                    .setAction(pendingStartAction),
            )
        } else {
            session.onSubmitFailed("通知权限未授予，未启动前台会话")
            refreshFromSession()
        }
    }

    fun startSession(action: String = ProbeActions.START) {
        pendingStartAction = action
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startForegroundService(
                Intent(context, ProbeForegroundService::class.java)
                    .setAction(action),
            )
        }
    }

    fun stopSession() {
        context.startService(
            Intent(context, ProbeForegroundService::class.java)
                .setAction(ProbeActions.STOP),
        )
    }

    fun send(state: StateMessage) {
        // Non-heartbeat sends register their own identity so only their own ack counts,
        // and the service learns the newest state before it heartbeats it.
        session.rememberInstruction(state.id)
        session.update(state)
        context.startService(
            Intent(context, ProbeForegroundService::class.java)
                .setAction(ProbeActions.UPDATE)
                .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(state)),
        )

        val outcome = engine.accept(state, System.currentTimeMillis())
        lastEnvelope = NavCodec.encodeToString(state)
        lastStatus = outcome.state.statusText

        when (val result = transport.sendState(state) { phase ->
            when (phase) {
                is SendPhase.Queued -> {
                    session.onQueued(phase.messageId, phase.nodeId)
                    runOnUiThreadCompat { refreshFromSession() }
                }

                is SendPhase.TaskSucceeded -> {
                    session.onTaskSuccess(phase.messageId)
                    runOnUiThreadCompat { refreshFromSession() }
                }

                is SendPhase.TaskFailed -> {
                    session.onTaskFailure(phase.messageId, phase.reason)
                    runOnUiThreadCompat { refreshFromSession() }
                }
            }
        }) {
            is SendResult.Queued -> {
                linkText = NavProtocol.LINK_CONNECTED
                session.onQueued(result.messageId, result.nodeId)
            }

            is SendResult.NoNode -> {
                linkText = NavProtocol.LINK_CONNECTING
                session.onNoNode(result.reason)
            }

            is SendResult.Failed -> {
                linkText = NavProtocol.LINK_DISCONNECTED
                session.onSubmitFailed(result.reason)
            }
        }
        refreshFromSession()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("MapRing 探针", style = MaterialTheme.typography.headlineSmall)
        Text("链路：$linkText")
        Text("状态：$lastStatus")
        Text("包名：com.minavigatering.probe")

        // Synthetic fixtures: no Maps notification has been sampled yet, so these samples
        // are manufactured for protocol/state verification only.
        Text(
            "注意：以下道路名与转向均为合成夹具，不代表已验收的Google 地图真实解析。",
            style = MaterialTheme.typography.bodySmall,
        )

        // Phone-channel diagnostics. These lines describe the phone side only; none of
        // them is a watch receipt.
        Text("通道诊断（手机侧，非手环回执）", style = MaterialTheme.typography.titleSmall)
        Text("宿主服务：$hostText", style = MaterialTheme.typography.bodySmall)
        Text("节点：$nodeText", style = MaterialTheme.typography.bodySmall)
        Text("权限：$permissionText", style = MaterialTheme.typography.bodySmall)
        Text("最近 SDK 错误：$sdkErrorText", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { runChannelDiagnostics() }) { Text("刷新通道诊断") }
        Button(onClick = {
            transport.requestDeviceManagerPermission { _, _ ->
                runOnUiThreadCompat { refreshDiagnostics() }
            }
        }) { Text("请求设备管理权限") }

        RuntimeTimelineDiagnostics()
        BackgroundPolicyDiagnostics()
        WearLaunchDiagnostics(transport)
        Button(onClick = { startSession() }) { Text("开始探针会话") }
        Button(onClick = { stopSession() }) { Text("停止探针会话") }
        Button(onClick = { startSession(ProbeForegroundService.ACTION_SCREEN_OFF_TEST) }) {
            Text("熄屏定时回归（合成，45秒）")
        }
        Text("定时回归：15秒右转300米，25秒200米，35秒30米，45秒左转100米。仅合成测试。",
            style = MaterialTheme.typography.bodySmall)

        Button(onClick = {
            send(
                StateMessage(
                    id = "probe-1",
                    sentAtMs = System.currentTimeMillis(),
                    link = NavProtocol.LINK_CONNECTED,
                    navStatus = NavProtocol.STATUS_ACTIVE,
                    action = "left",
                    exitNumber = null,
                    distanceM = 300,
                    roadName = "合成道路",
                    actionId = "probe-left-1",
                    mode = NavProtocol.MODE_WALK,
                    heartbeat = false,
                )
            )
        }) { Text("左转 300 米（合成夹具）") }

        Button(onClick = {
            send(
                StateMessage(
                    id = "probe-2",
                    sentAtMs = System.currentTimeMillis(),
                    link = NavProtocol.LINK_CONNECTED,
                    navStatus = NavProtocol.STATUS_ACTIVE,
                    action = "left",
                    exitNumber = null,
                    distanceM = 200,
                    roadName = "合成道路",
                    actionId = "probe-left-1",
                    mode = NavProtocol.MODE_WALK,
                    heartbeat = false,
                )
            )
        }) { Text("左转 200 米（同一 actionId，合成夹具）") }

        Button(onClick = {
            send(
                StateMessage(
                    id = "probe-3",
                    sentAtMs = System.currentTimeMillis(),
                    link = NavProtocol.LINK_CONNECTED,
                    navStatus = NavProtocol.STATUS_ACTIVE,
                    action = "mystery",
                    exitNumber = null,
                    distanceM = 150,
                    roadName = "合成道路",
                    actionId = "probe-mystery-1",
                    mode = NavProtocol.MODE_WALK,
                    heartbeat = false,
                )
            )
        }) { Text("不可识别方向（action=mystery）") }

        Button(onClick = {
            send(
                StateMessage(
                    id = "probe-4",
                    sentAtMs = System.currentTimeMillis(),
                    link = NavProtocol.LINK_CONNECTED,
                    navStatus = NavProtocol.STATUS_ENDED,
                    action = null,
                    exitNumber = null,
                    distanceM = null,
                    roadName = null,
                    actionId = null,
                    mode = NavProtocol.MODE_WALK,
                    heartbeat = false,
                )
            )
        }) { Text("结束导航（navStatus=ended）") }

        Button(onClick = {
            send(
                StateMessage(
                    id = "probe-5",
                    sentAtMs = System.currentTimeMillis(),
                    link = NavProtocol.LINK_CONNECTED,
                    navStatus = NavProtocol.STATUS_IDLE,
                    action = null,
                    exitNumber = null,
                    distanceM = null,
                    roadName = null,
                    actionId = null,
                    mode = NavProtocol.MODE_WALK,
                    heartbeat = false,
                )
            )
        }) { Text("空闲（navStatus=idle，无虚构转向）") }

        Text("最近一次本地报文：", style = MaterialTheme.typography.titleSmall)
        Text(lastEnvelope ?: "（尚未发送）", style = MaterialTheme.typography.bodySmall)

        Text("最近一次发送状态：", style = MaterialTheme.typography.titleSmall)
        Text(sendText, style = MaterialTheme.typography.bodySmall)

        Text("最近一次回执：", style = MaterialTheme.typography.titleSmall)
        Text(ackText, style = MaterialTheme.typography.bodySmall)
    }
}

/** Runs [block] on the UI thread when the composable is hosted by an activity. */
private fun runOnUiThreadCompat(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}
