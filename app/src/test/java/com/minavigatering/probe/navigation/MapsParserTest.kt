package com.minavigatering.probe.navigation

import org.junit.Assert.*
import org.junit.Test

/** Manufactured notification text and synthetic bitmaps. Real Google Maps fixtures must be sampled separately. */
class MapsParserTest {

    // --- icon hash channel -------------------------------------------------

    /** Builds a dim x dim ARGB buffer whose 16 (4x4) region luminas equal [lumas]. */
    private fun regionLumaBitmap(lumas: List<Int>, dim: Int = 64): IntArray {
        val px = IntArray(dim * dim)
        val parts = 4
        for (y in 0 until dim) {
            val ry = (y * parts / dim).coerceAtMost(parts - 1)
            for (x in 0 until dim) {
                val rx = (x * parts / dim).coerceAtMost(parts - 1)
                val luma = lumas[ry * parts + rx]
                px[y * dim + x] = (255 shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        return px
    }

    private fun parse(text: String, icon: IntArray? = null, w: Int = 0, h: Int = 0) =
        MapsParser.parse(MapsParser.PACKAGE, listOf(text), icon, w, h)

    @Test fun hashIsDeterministicAndDistinct() {
        val a = MapsParser.hashIcon(regionLumaBitmap(List(16) { it * 10 }, 64), 64, 64)
        val b = MapsParser.hashIcon(regionLumaBitmap(List(16) { it * 10 }, 64), 64, 64)
        val c = MapsParser.hashIcon(regionLumaBitmap(List(16) { 255 - it * 10 }, 64), 64, 64)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(16, a!!.split("-").size)
    }

    @Test fun hashFromRegionLumaMatchesSeedString() {
        // The seed table stores 16 region-average luminas; rebuilding that exact buffer must
        // reproduce the hash AND resolve to the mapped action through the icon channel.
        val lumas = listOf(0, 39, 39, 0, 0, 175, 175, 0, 0, 63, 63, 0, 0, 31, 31, 0)
        val px = regionLumaBitmap(lumas, 64)
        assertEquals("0-39-39-0-0-175-175-0-0-63-63-0-0-31-31-0", MapsParser.hashIcon(px, 64, 64))
        val instr = parse("", px, 64, 64)
        assertEquals("straight", instr?.action)
        assertEquals("active", instr?.status)
    }

    @Test fun iconChannelResolvesLeftFromSeed() {
        val lumas = listOf(3, 59, 0, 0, 123, 151, 99, 71, 11, 71, 0, 95, 0, 0, 0, 47)
        val instr = parse("", regionLumaBitmap(lumas, 64), 64, 64)
        assertEquals("left", instr?.action)
    }

    // --- text channel (no icon) -------------------------------------------

    @Test fun chineseDistanceAndRoadAndDirection() {
        val i = parse("500米后左转，进入中山路")
        assertEquals("left", i?.action)
        assertEquals(500, i?.distanceM)
        assertEquals("中山路", i?.roadName)
        assertEquals(1500, parse("1.5公里后右前方转弯").distanceM)
        assertEquals("slight-right", parse("1.5公里后右前方转弯").action)
    }

    @Test fun englishDistanceAndRoadAndDirection() {
        val i = parse("Turn left in 500 m onto Main St")
        assertEquals("left", i?.action)
        assertEquals(500, i?.distanceM)
        assertEquals("Main St", i?.roadName)
        val j = parse("In 0.5 km, continue onto Elm Road")
        assertEquals("straight", j?.action)
        assertEquals(500, j?.distanceM)
        assertEquals("Elm Road", j?.roadName)
    }

    @Test fun unitVariants() {
        assertEquals(1609, parse("1 mi to the destination").distanceM) // approximate miles
        assertEquals(305, parse("1000 ft ahead").distanceM) // approximate feet
        assertEquals(200, parse("200 m").distanceM)
    }

    @Test fun unrelatedIsNotNavigation() {
        assertNull(parse("Traffic on your route"))
        assertNull(MapsParser.parse("other.app", listOf("500米后右转")))
    }

    @Test fun endAndRoundaboutExit() {
        assertEquals("ended", parse("You have arrived at your destination").status)
        assertEquals("ended", parse("导航已结束").status)
        assertEquals("roundabout-exit", parse("50米后驶出环岛，第3个出口").action)
        assertEquals(3, parse("50米后驶出环岛，第3个出口").exitNumber)
        assertEquals("uturn-right", parse("30米后向右掉头").action)
    }

    @Test fun doesNotGuessDirection() {
        // Distance but no recognizable direction -> unrecognized, never a guessed action.
        val i = parse("500 m")
        assertEquals("unrecognized", i?.status)
        assertNull(i?.action)
        assertEquals(500, i?.distanceM)
    }

    @Test fun exitNumberFromEnglish() {
        val i = parse("Slight right, then take exit 3")
        assertEquals("slight-right", i?.action)
        assertEquals(3, i?.exitNumber)
    }

    @Test fun wrongPackageIsIgnored() {
        assertNull(MapsParser.parse("com.example.other", listOf("Turn left in 500 m")))
    }

    // --- turn tracker -----------------------------------------------------

    @Test fun stableTurnAndConsecutiveSameDirection() {
        val tracker = MapsTurnTracker()
        val a = tracker.actionId(parse("300米后右转，进入中山路")!!)
        assertEquals(a, tracker.actionId(parse("30米后右转，进入中山路")!!))
        assertNotEquals(a, tracker.actionId(parse("300米后右转，进入中山路")!!))
    }

    @Test fun consecutiveSameDirectionTurnSharesTheActionId() {
        val tracker = MapsTurnTracker()
        val first = tracker.actionId(parse("300米后右转，进入中山路")!!)
        val second = tracker.actionId(parse("280米后右转，进入中山路")!!)
        assertEquals(
            "同方向连续转弯没有通知实例 id，距离未从≤100回跳时无法与距离更新区分，不得声称已识别新转弯",
            first, second,
        )
    }

    // --- closeout ----------------------------------------------------------

    @Test fun removalGraceIsNotArrivalOrEnd() {
        assertEquals(5000L, MapsCloseout.REMOVAL_GRACE_MS)
        val grace = MapsCloseout.afterRemovalGrace(true)!!
        assertEquals("unrecognized", grace.status)
        assertNull(grace.action)
        assertNotEquals("ended", grace.status)
        assertNotEquals("arrive", grace.action)
        assertNull(MapsCloseout.afterRemovalGrace(false))
    }

    @Test fun disconnectAndEndCloseWithoutAction() {
        val disconnected = MapsCloseout.listenerDisconnected()
        assertEquals("unrecognized", disconnected.status)
        assertNull(disconnected.action)
        val ended = MapsCloseout.navigationEnded()
        assertEquals("ended", ended.status)
        assertNull(ended.action)
    }
}
