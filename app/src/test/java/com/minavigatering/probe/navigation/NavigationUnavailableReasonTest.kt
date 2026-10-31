package com.minavigatering.probe.navigation

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationUnavailableReasonTest {

    private fun state(
        id: String = "ui-1",
        navStatus: String = NavProtocol.STATUS_ACTIVE,
        action: String? = "left",
        distanceM: Int? = 32,
        roadName: String? = "中关村大街",
    ): StateMessage = StateMessage(
        id = id,
        sentAtMs = 1L,
        navStatus = navStatus,
        action = action,
        exitNumber = null,
        distanceM = distanceM,
        roadName = roadName,
        mode = NavProtocol.MODE_WALK,
    )

    private val restoreId = NavigationSettings.RESTORE_ID_PREFIX + "1700000000000"

    private val originalWaiting = "请在手环打开导航页，再在 Google 地图开始导航。"
    private val autoLaunchWaiting =
        "在 Google 地图开始步行或骑行导航，手环导航页会自动打开。"
    private val unknownAction =
        "Google 地图给出的转向动作无法识别，手环不会显示猜测的方向。"
    private val actionMissing =
        "Google 地图正在导航，但这条提示读不出转向方向。手环不会显示猜测的方向，请看 Google 地图或等下一条提示。"
    private val noNotice =
        "没有读到 Google 地图的导航提示：导航可能已结束，或 Google 地图已退出。要继续请回到 Google 地图重新开始导航。"

    // (a) A restored cold-start snapshot waits, and the waiting sentence follows the home switch.
    @Test
    fun restoredSnapshotWaitsAndSaysWhetherTheBandPageOpensByItself() {
        assertEquals("restore-", NavigationSettings.RESTORE_ID_PREFIX)
        val restored = state(
            id = restoreId,
            navStatus = NavProtocol.STATUS_UNRECOGNIZED,
            action = null,
            distanceM = null,
            roadName = null,
        )
        val autoOn = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, restored, autoLaunchBand = true)
        assertEquals(NavigationPhase.WAITING, autoOn.phase)
        assertEquals("等待 Google 地图导航", autoOn.headline)
        assertEquals(autoLaunchWaiting, autoOn.guidance)
        assertFalse(autoOn.showAsValidInstruction)
        val autoOff = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, restored, autoLaunchBand = false)
        assertEquals(NavigationPhase.WAITING, autoOff.phase)
        assertEquals(originalWaiting, autoOff.guidance)
        // Priority is unchanged: a restored id never lifts ENDED or CONNECTING.
        val ended = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK,
            state(id = restoreId, navStatus = NavProtocol.STATUS_ENDED),
        )
        assertEquals(NavigationPhase.ENDED, ended.phase)
        val connecting = mapNavigationUi(
            true, true, false, true, NavProtocol.MODE_WALK,
            state(id = restoreId, navStatus = NavProtocol.STATUS_UNRECOGNIZED),
        )
        assertEquals(NavigationPhase.CONNECTING, connecting.phase)
    }

    // (b) The unavailable phase names which of the three reasons applies, and only that one.
    @Test
    fun unavailableSaysWhichReasonApplies() {
        val unrecognized = state(
            navStatus = NavProtocol.STATUS_UNRECOGNIZED,
            action = null,
            distanceM = null,
            roadName = null,
        )
        val mapsOn = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK, unrecognized,
            mapsNavigationPresent = true,
        )
        assertEquals(NavigationPhase.UNAVAILABLE, mapsOn.phase)
        assertEquals("信息暂不可用", mapsOn.headline)
        assertEquals(actionMissing, mapsOn.guidance)
        val mapsOff = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK, unrecognized,
            mapsNavigationPresent = false,
        )
        assertEquals(NavigationPhase.UNAVAILABLE, mapsOff.phase)
        assertEquals("信息暂不可用", mapsOff.headline)
        assertEquals(noNotice, mapsOff.guidance)
        val mysteryAction = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK,
            state(navStatus = NavProtocol.STATUS_ACTIVE, action = "mystery"),
            mapsNavigationPresent = true,
        )
        assertEquals(NavigationPhase.UNAVAILABLE, mysteryAction.phase)
        assertEquals("信息暂不可用", mysteryAction.headline)
        assertEquals(unknownAction, mysteryAction.guidance)
        assertEquals(3, setOf(mapsOn.guidance, mapsOff.guidance, mysteryAction.guidance).size)
    }

    // (c) None of the new sentences carries a word the existing screen test forbids.
    @Test
    fun newSentencesCarryNoForbiddenWording() {
        val newSentences = listOf(autoLaunchWaiting, unknownAction, actionMissing, noNotice)
        val banned = listOf("left", "slight-right", "roundabout-exit", "不默认直行", "通道任务", "导航中", "已确认", "已连接", "已收到")
        for (sentence in newSentences) {
            for (word in banned) {
                assertFalse("$word in $sentence", sentence.contains(word))
            }
        }
    }
}
