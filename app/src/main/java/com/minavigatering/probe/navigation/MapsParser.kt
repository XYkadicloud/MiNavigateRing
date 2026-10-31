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
        // 实测标定（CPH1823 / Android 10 / Maps 导航态，图标 99x99）。
        // 这两个哈希与上面种子的数值不同（DPI + 版本重绘），但结构一致；精确匹配会漏，
        // 所以下面 lookupIcon() 还会做一次带容差的最近邻。留在这里是为了让精确命中生效，
        // 并作为该机型的地面真值记录。
        "0-0-49-1-70-125-170-118-119-0-74-14-63-0-0-0" to "right",
        "0-31-38-0-0-154-163-2-0-45-54-0-0-22-26-0" to "straight",
    )

    /**
     * 最近邻匹配的容差：16 个区域平均灰度的「平均绝对差」上限（0..255 尺度）。
     *
     * 精确匹配对 DPI、系统字体缩放、Google 重绘图标都不稳，而方向本身只由图案形状决定，
     * 所以形状相同、数值整体漂移的哈希应该仍然命中。实测：本机右转图标对种子 `right`
     * 的距离是 6.81，对次优（roundabout-exit）是 22.06；直行图标对种子 `straight` 是
     * 5.31，次优 25.19。取 18 能把两者都收进来，同时挡住形状不同的图案。
     */
    private const val ICON_MATCH_MAX_MAD = 18.0

    /**
     * 冠军必须比「最佳的不同方向」还要好这么多倍，否则宁可判为读不出。
     *
     * 左转和右转是镜像，数值上彼此接近；只卡距离阈值容易把左转认成右转。这一条保证
     * 只有明显更优的那个方向才被采纳——识别不出就让手环显示「暂无导航」，绝不猜。
     */
    private const val ICON_MATCH_MARGIN = 0.6

    /** 预解析的种子向量，避免每次通知都重新切分字符串。 */
    private val iconVectors: List<Pair<IntArray, String>> by lazy {
        iconHashMap.mapNotNull { (hash, action) ->
            val parts = hash.split('-')
            if (parts.size != 16) return@mapNotNull null
            val vec = IntArray(16) { parts[it].toIntOrNull() ?: return@mapNotNull null }
            vec to action
        }
    }

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
            ?.let { lookupIcon(it) }
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
    /**
     * 把图标哈希解析成方向：先精确命中，再带容差找最近邻。
     *
     * 找不到「明显更优」的候选就返回 null。调用方据此把状态置为 `unrecognized`，
     * 手环显示「暂无导航」——绝不按最接近的那个猜一个方向。
     */
    fun lookupIcon(hash: String): String? {
        iconHashMap[hash]?.let { return it }
        val parts = hash.split('-')
        if (parts.size != 16) return null
        val probe = IntArray(16)
        for (i in 0 until 16) probe[i] = parts[i].toIntOrNull() ?: return null

        var bestMad = Double.MAX_VALUE
        var bestAction: String? = null
        for ((vec, action) in iconVectors) {
            val mad = meanAbsDiff(probe, vec)
            if (mad < bestMad) {
                bestMad = mad
                bestAction = action
            }
        }
        val winner = bestAction ?: return null
        if (bestMad > ICON_MATCH_MAX_MAD) return null

        // 亚军只在不同方向里找：`straight` 有多条种子，它们互为同一方向，不该互相压分。
        var runnerUpMad = Double.MAX_VALUE
        for ((vec, action) in iconVectors) {
            if (action == winner) continue
            val mad = meanAbsDiff(probe, vec)
            if (mad < runnerUpMad) runnerUpMad = mad
        }
        if (runnerUpMad != Double.MAX_VALUE && bestMad > runnerUpMad * ICON_MATCH_MARGIN) return null
        return winner
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

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
            (if (count[it] > 0) sum[it] / count[it] else 0).toString()
        }
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
