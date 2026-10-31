package com.minavigatering.probe.protocol

/**
 * Wire protocol for the MiNavigateRing probe.
 *
 * Pure Kotlin on purpose: the unit tests exercise this without an Android SDK or the
 * xms-wearable AAR on the classpath. See PROJECT_SPEC.md section 4 (职责边界) — this layer
 * transports state only; it never matches Maps text and never renders.
 */
object NavProtocol {

    const val VERSION = 1

    const val KIND_STATE = "state"
    const val KIND_ACK = "ack"

    const val LINK_CONNECTED = "connected"
    const val LINK_CONNECTING = "connecting"
    const val LINK_DISCONNECTED = "disconnected"

    const val STATUS_IDLE = "idle"
    const val STATUS_ACTIVE = "active"
    const val STATUS_UNRECOGNIZED = "unrecognized"
    const val STATUS_ENDED = "ended"

    const val MODE_WALK = "walk"
    const val MODE_CYCLE = "cycle"

    /** Walk advisory threshold, metres (PROJECT_SPEC.md section 6). */
    const val WALK_THRESHOLD_M = 30

    /** Cycle advisory threshold, metres (PROJECT_SPEC.md section 6). */
    const val CYCLE_THRESHOLD_M = 100

    /** Heartbeat period, milliseconds (PROJECT_SPEC.md section 7). */
    const val HEARTBEAT_PERIOD_MS = 5_000L

    /** Link is "connecting" after this gap without an accepted report (section 7). */
    const val LINK_TIMEOUT_MS = 15_000L

    /**
     * The 14 confirmed navigation actions, see design/NAVIGATION_ICONS.md.
     * Anything outside this set is unrecognised — it must never be coerced to `straight`.
     */
    val ALLOWED_ACTIONS: List<String> = listOf(
        "left",
        "right",
        "straight",
        "slight-left",
        "slight-right",
        "sharp-left",
        "sharp-right",
        "keep-left",
        "keep-right",
        "uturn-left",
        "uturn-right",
        "roundabout-enter",
        "roundabout-exit",
        "arrive",
    )

    val ALLOWED_LINKS: List<String> = listOf(LINK_CONNECTED, LINK_CONNECTING, LINK_DISCONNECTED)
    val ALLOWED_STATUSES: List<String> = listOf(STATUS_IDLE, STATUS_ACTIVE, STATUS_UNRECOGNIZED, STATUS_ENDED)
    val ALLOWED_KINDS: List<String> = listOf(KIND_STATE, KIND_ACK)

    fun isAllowedAction(action: String?): Boolean = action == null || action in ALLOWED_ACTIONS
}

sealed interface Message {
    val v: Int
    val kind: String
    val id: String
    val sentAtMs: Long
}

/**
 * Navigation state report.
 *
 * Field order is fixed by the work order: v, kind, id, sentAtMs, link, navStatus, action,
 * exitNumber, distanceM, roadName, actionId, mode, heartbeat.
 */
data class StateMessage(
    override val v: Int = NavProtocol.VERSION,
    override val kind: String = NavProtocol.KIND_STATE,
    override val id: String,
    override val sentAtMs: Long,
    val link: String = NavProtocol.LINK_CONNECTED,
    val navStatus: String = NavProtocol.STATUS_IDLE,
    val action: String? = null,
    /** Roundabout exit number, null when unknown. Never inferred (NAVIGATION_ICONS.md). */
    val exitNumber: Int? = null,
    val distanceM: Int? = null,
    val roadName: String? = null,
    val actionId: String? = null,
    val mode: String? = null,
    val heartbeat: Boolean = false,
) : Message

data class AckMessage(
    override val v: Int = NavProtocol.VERSION,
    override val kind: String = NavProtocol.KIND_ACK,
    override val id: String,
    val refId: String,
    override val sentAtMs: Long,
) : Message

/** UTF-8 JSON codec. Round-trips every field listed above. */
object NavCodec {

    fun encode(message: Message): ByteArray = encodeToString(message).toByteArray(Charsets.UTF_8)

    fun encodeToString(message: Message): String = when (message) {
        is StateMessage -> Json.obj(
            listOf(
                "v" to message.v,
                "kind" to message.kind,
                "id" to message.id,
                "sentAtMs" to message.sentAtMs,
                "link" to message.link,
                "navStatus" to message.navStatus,
                "action" to message.action,
                "exitNumber" to message.exitNumber,
                "distanceM" to message.distanceM,
                "roadName" to message.roadName,
                "actionId" to message.actionId,
                "mode" to message.mode,
                "heartbeat" to message.heartbeat,
            )
        )

        is AckMessage -> Json.obj(
            listOf(
                "v" to message.v,
                "kind" to message.kind,
                "id" to message.id,
                "refId" to message.refId,
                "sentAtMs" to message.sentAtMs,
            )
        )
    }

    fun decode(bytes: ByteArray): Message = decodeFromString(String(bytes, Charsets.UTF_8))

    fun decodeFromString(text: String): Message {
        val root = Json.parseObject(text)
        val kind = Json.asString(root["kind"])
            ?: throw IllegalArgumentException("missing kind")
        require(kind in NavProtocol.ALLOWED_KINDS) { "unsupported kind '$kind'" }
        return when (kind) {
            NavProtocol.KIND_STATE -> decodeState(root)
            NavProtocol.KIND_ACK -> decodeAck(root)
            else -> throw IllegalArgumentException("unsupported kind '$kind'")
        }
    }

    private fun decodeState(root: Map<String, Any?>): StateMessage {
        val id = Json.asString(root["id"]) ?: throw IllegalArgumentException("state.id missing")
        val sentAtMs = Json.asLong(root["sentAtMs"])
            ?: throw IllegalArgumentException("state.sentAtMs missing")
        val link = Json.asString(root["link"]) ?: NavProtocol.LINK_CONNECTED
        require(link in NavProtocol.ALLOWED_LINKS) { "unsupported link '$link'" }
        val navStatus = Json.asString(root["navStatus"]) ?: NavProtocol.STATUS_IDLE
        require(navStatus in NavProtocol.ALLOWED_STATUSES) { "unsupported navStatus '$navStatus'" }
        return StateMessage(
            v = Json.asInt(root["v"]) ?: NavProtocol.VERSION,
            kind = NavProtocol.KIND_STATE,
            id = id,
            sentAtMs = sentAtMs,
            link = link,
            navStatus = navStatus,
            // The raw action string is preserved verbatim so an unknown direction stays
            // visibly unknown instead of being normalised to a known value.
            action = Json.asString(root["action"]),
            exitNumber = Json.asInt(root["exitNumber"]),
            distanceM = Json.asInt(root["distanceM"]),
            roadName = Json.asString(root["roadName"]),
            actionId = Json.asString(root["actionId"]),
            mode = Json.asString(root["mode"]),
            heartbeat = Json.asBoolean(root["heartbeat"]) ?: false,
        )
    }

    private fun decodeAck(root: Map<String, Any?>): AckMessage {
        val id = Json.asString(root["id"]) ?: throw IllegalArgumentException("ack.id missing")
        val refId = Json.asString(root["refId"]) ?: throw IllegalArgumentException("ack.refId missing")
        val sentAtMs = Json.asLong(root["sentAtMs"])
            ?: throw IllegalArgumentException("ack.sentAtMs missing")
        return AckMessage(
            v = Json.asInt(root["v"]) ?: NavProtocol.VERSION,
            id = id,
            refId = refId,
            sentAtMs = sentAtMs,
        )
    }
}
