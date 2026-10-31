package com.minavigatering.probe.navigation

/**
 * Google Maps navigation parser.
 *
 * Unlike Amap (which puts the maneuver in text), Google Maps encodes the turn direction
 * primarily in the notification's **maneuver icon** (a bitmap). So this parser uses two
 * channels:
 *   1. direction from the large-icon brightness hash (language-independent, robust worldwide);
 *   2. distance + road name from the notification text (Chinese + English).
 *
 * The icon hash is a port of the 16-region average-grey algorithm from the open-source
 * `GmapNavNotiCatcher` project. The bundled [iconHashMap] seed table is illustrative only —
 * Google can re-skin the icons and different DPIs/theme shift the hashes, so the table must
 * be re-calibrated on the target device (see [hashIcon] / docs). The text channel is the
 * reliable fallback.
 *
 * Never infers an arrow from an unknown notification or icon. If neither channel yields a
 * direction, the status is `unrecognized` and the band shows "暂无导航".
 */
data class MapsInstruction(
    val status: String,
    val action: String? = null,
    val distanceM: Int? = null,
    val roadName: String? = null,
    val exitNumber: Int? = null,
)

/**
 * Closeout states. Built here so "listener gone" and "route ended" can never be confused
 * with a real turn: both carry no action, and the removal grace is neither an arrival nor
 * an end until no tracked Maps notification is left.
 */
object MapsCloseout {
    const val REMOVAL_GRACE_MS = 5_000L
    fun listenerDisconnected(): MapsInstruction = MapsInstruction(status = "unrecognized")
    fun navigationEnded(): MapsInstruction = MapsInstruction(status = "ended")
    fun afterRemovalGrace(noTrackedMapsNotifications: Boolean): MapsInstruction? =
        if (noTrackedMapsNotifications) MapsInstruction(status = "unrecognized") else null
}

object MapsParser {
    const val PACKAGE = "com.google.android.apps.maps"

    /**
     * Seed maneuver-icon hash table (16 region average-grey values joined by "-").
     * These come from `GmapNavNotiCatcher` on one device/DPI and are ONLY a starting point.
     * Re-calibrate on the target phone by logging [hashIcon] for each maneuver and extending
     * this map. Icons that miss the table fall back to the (weaker) text channel.
     */
    private val iconHashMap: Map<String, String> = mapOf(
        "3-59-0-0-123-151-99-71-11-71-0-95-0-0-0-47" to "left",
        "0-0-59-3-71-99-151-123-95-0-71-11-47-0-0-0" to "right",
        "0-39-39-0-0-175-175-0-0-55-55-0-0-23-23-0" to "straight",
        "0-39-39-0-0-175-175-0-0-63-63-0-0-31-31-0" to "straight",
        "0-39-39-0-0-175-175-0-0-139-135-0-7-55-55-3" to "straight",
        "0-119-47-0-0-191-79-0-0-0-111-23-0-0-31-31" to "keep-left",
        "0-47-119-0-0-79-191-0-23-111-0-0-31-31-0-0" to "keep-right",
        "0-23-111-47-43-127-155-95-0-139-11-0-0-63-0-0" to "keep-right",
        "47-111-11-3-95-171-131-43-0-11-139-0-0-0-63-0" to "keep-left",
        "47-15-99-15-95-139-171-103-95-75-43-3-47-15-0-0" to "roundabout-exit",
        "0-143-143-0-0-167-167-0-0-139-139-0-0-39-39-0" to "roundabout-enter",
        "23-123-119-11-115-51-171-75-91-127-119-0-0-63-0-0" to "roundabout-exit",
        "0-59-79-0-0-131-159-3-39-131-147-3-0-0-39-15" to "roundabout-exit",
        "0-51-115-15-11-115-23-107-75-203-51-95-0-15-0-47" to "uturn-left",
        "99-131-11-0-119-111-39-11-67-167-203-143-0-55-91-103" to "arrive",
    )

    /**
     * Direction words as a text fallback. More specific phrases first so e.g. "slight left"
     * wins over "left". Used only when the icon hash misses. Covers Chinese + English.
     */
    private val directions = listOf(
        "向左掉头" to "uturn-left", "左侧掉头" to "uturn-left", "向右掉头" to "uturn-right",
        "右侧掉头" to "uturn-right",
        "进入环岛" to "roundabout-enter", "驶出环岛" to "roundabout-exit", "离开环岛" to "roundabout-exit",
        "左后方" to "sharp-left", "右后方" to "sharp-right",
        "左前方" to "slight-left", "右前方" to "slight-right",
        "靠左" to "keep-left", "靠右" to "keep-right",
        "左转" to "left", "右转" to "right",
        "直行" to "straight", "继续向前" to "straight", "继续直行" to "straight",
        "到达目的地" to "arrive", "到达终点" to "arrive", "已到达" to "arrive",
        // English
        "u-turn left" to "uturn-left", "make a u-turn" to "uturn-left",
        "u-turn right" to "uturn-right",
        "enter the roundabout" to "roundabout-enter", "enter roundabout" to "roundabout-enter",
        "take the exit" to "roundabout-exit", "exit the roundabout" to "roundabout-exit",
        "exit roundabout" to "roundabout-exit",
        "sharp left" to "sharp-left", "sharp right" to "sharp-right",
        "slight left" to "slight-left", "slight right" to "slight-right",
        "bear left" to "keep-left", "bear right" to "keep-right",
        "keep left" to "keep-left", "keep right" to "keep-right",
        "turn left" to "left", "turn right" to "right",
        "continue" to "straight", "continue straight" to "straight", "head straight" to "straight",
        "you have arrived" to "arrive", "arriving at" to "arrive", "arrive at" to "arrive",
    )

    /** Words that mark the end of a navigation session. */
    private val endWords = listOf(
        "导航已结束", "结束导航", "已退出导航", "导航结束",
        "navigation ended", "you have arrived", "arrived at your destination", "stop navigation",
    )

    private val distanceZh = Regex("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米)")
    // Longer alternatives first so "mi"/"miles" win over the bare "m", and "meters" over "m".
    private val distanceEn = Regex("(\\d+(?:\\.\\d+)?)\\s*(kilometers?|km|miles?|mi|meters?|m|feet|ft)")
    private val roadZh = Regex("(?:沿|进入|前往|走|上)\\s*([^，。；\\n]{1,40}?(?:大道|公路|街|路|巷|桥|高速|隧道|胡同))")
    private val roadEn = Regex("(?:onto|toward|on|to|head)\\s+([A-Z][A-Za-z0-9 .'-]{1,40}?(?:St|Street|Rd|Road|Ave|Avenue|Blvd|Lane|Ln|Hwy|Highway|Dr|Drive|Way|Pl|Place|Ct|Court|Sq|Square))")
    private val exitZh = Regex("第\\s*(\\d+)\\s*(?:个)?出口")
    private val exitEn = Regex("(?:exit|ramp)\\s*(\\d+)")

    /**
     * Parses one notification.
     *
     * @param iconPixels ARGB pixel array of [Notification.EXTRA_LARGE_ICON], or null if absent.
     * @param iconW/iconH dimensions of that bitmap (0 if null).
     */
    fun parse(
        packageName: String,
        fields: List<String>,
        iconPixels: IntArray? = null,
        iconW: Int = 0,
        iconH: Int = 0,
    ): MapsInstruction? {
        if (packageName != PACKAGE) return null
        val text = fields.filter { it.isNotBlank() }.distinct().joinToString("\n")
        // English direction/end words arrive capitalised ("Turn left", "You have arrived"), so
        // match on a lowercased copy. Chinese is unaffected by lowercasing.
        val lower = text.lowercase()
        if (endWords.any { it in lower }) return MapsInstruction("ended")

        // Direction: icon hash wins, text fallback otherwise.
        val actionFromIcon = iconPixels
            ?.let { hashIcon(it, iconW, iconH) }
            ?.let { iconHashMap[it] }
        val foundText = directions.firstOrNull { it.first in lower }
        val action = actionFromIcon ?: foundText?.second

        val metres = parseDistance(text)
        val road = roadZh.find(text)?.groupValues?.get(1)?.trim()
            ?: roadEn.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.')
        val exit = exitZh.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: exitEn.find(text)?.groupValues?.get(1)?.toIntOrNull()

        val hasTurnSignal = action != null
        // No direction and nothing else readable -> not a navigation notice for us.
        if (!hasTurnSignal && metres == null && road == null) return null
        // Direction is the only thing that makes this a usable turn; if we cannot read it we
        // must not invent one. A notification with distance/road but no recognizable direction
        // still counts as active navigation, but with a null action (band shows "暂无导航").
        if (!hasTurnSignal) {
            return MapsInstruction("unrecognized", null, metres, road, exit)
        }
        return MapsInstruction("active", action, metres, road, exit)
    }

    private fun parseDistance(text: String): Int? {
        distanceZh.find(text)?.let {
            val value = it.groupValues[1].toDoubleOrNull() ?: return@let null
            val m = value * if (it.groupValues[2] == "米") 1.0 else 1000.0
            return clampMetres(m)
        }
        distanceEn.find(text)?.let {
            val value = it.groupValues[1].toDoubleOrNull() ?: return@let null
            val unit = it.groupValues[2].lowercase()
            val m = when {
                unit.startsWith("km") || unit == "kilometer" || unit == "kilometers" -> value * 1000.0
                unit.startsWith("mi") -> value * 1609.344
                unit.startsWith("ft") || unit == "feet" -> value * 0.3048
                else -> value // m / meter / meters
            }
            return clampMetres(m)
        }
        return null
    }

    private fun clampMetres(m: Double): Int? =
        if (m.isFinite() && m >= 0 && m <= 200_000) kotlin.math.round(m).toInt() else null

    // --- Icon hash (pure, JVM-testable; the service converts Bitmap -> IntArray) ---

    /** Downsamples an ARGB pixel buffer to 32x32 average luma and returns the 16-region hash. */
    fun hashIcon(pixels: IntArray, width: Int, height: Int): String? {
        val gray = downsampleGray(pixels, width, height) ?: return null
        val side = 32
        val parts = 4
        val regionSize = side / parts
        val sum = IntArray(parts * parts) { 0 }
        val count = IntArray(parts * parts) { 0 }
        for (y in 0 until side) {
            for (x in 0 until side) {
                val rx = (x / regionSize).coerceAtMost(parts - 1)
                val ry = (y / regionSize).coerceAtMost(parts - 1)
                val idx = ry * parts + rx
                sum[idx] += gray[y * side + x]
                count[idx]++
            }
        }
        return (0 until parts * parts).joinToString("-") {
            if (count[it] > 0) sum[it] / count[it] else 0
        }.toString()
    }

    private fun downsampleGray(pixels: IntArray, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0) return null
        val side = 32
        val out = IntArray(side * side)
        val sx = w.toDouble() / side
        val sy = h.toDouble() / side
        for (yy in 0 until side) {
            for (xx in 0 until side) {
                val x0 = (xx * sx).toInt()
                val x1 = ((xx + 1) * sx).toInt().coerceAtMost(w)
                val y0 = (yy * sy).toInt()
                val y1 = ((yy + 1) * sy).toInt().coerceAtMost(h)
                var sum = 0L
                var n = 0
                for (y in y0 until maxOf(y1, y0 + 1)) {
                    for (x in x0 until maxOf(x1, x0 + 1)) {
                        // Mirrors GmapNavNotiCatcher: take the red channel as the per-pixel
                        // value so the bundled seed table (built the same way) stays compatible.
                        sum += (pixels[y * w + x] shr 16) and 0xFF
                        n++
                    }
                }
                out[yy * side + xx] = if (n > 0) (sum / n).toInt() else 0
            }
        }
        return out
    }
}

/**
 * Turn-id tracker. A new turn (or route session) creates one id; a mere distance update
 * keeps the same id so it does not re-trigger the vibration.
 *
 * [tag] widens the id shape to `maps-turn-<tag>-<n>` so two listener instances of the same
 * process cannot share a turn number; an empty tag keeps the historical `maps-turn-<n>` form.
 */
class MapsTurnTracker(private val tag: String = "") {
    private var last: MapsInstruction? = null
    private var turn = 0L
    fun actionId(next: MapsInstruction): String {
        val previous = last
        if (previous == null || previous.status != "active" || next.action != previous.action ||
            next.roadName != previous.roadName || next.exitNumber != previous.exitNumber ||
            (previous.distanceM != null && next.distanceM != null && previous.distanceM <= 100 &&
                next.distanceM > previous.distanceM + 100)
        ) {
            turn++
        }
        last = next
        return if (tag.isEmpty()) "maps-turn-$turn" else "maps-turn-$tag-$turn"
    }
}
