package com.minavigatering.probe.navigation

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUiStateTest {

    private fun activeLeft(
        navStatus: String = NavProtocol.STATUS_ACTIVE,
        action: String? = "left",
        exitNumber: Int? = null,
        distanceM: Int? = 32,
        roadName: String? = "\u4e2d\u5173\u6751\u5927\u8857",
    ): StateMessage = StateMessage(
        id = "ui-1",
        sentAtMs = 1L,
        navStatus = navStatus,
        action = action,
        exitNumber = exitNumber,
        distanceM = distanceM,
        roadName = roadName,
        mode = NavProtocol.MODE_WALK,
    )

    @Test
    fun activeLeftShowsDirectionDistanceRoadAndWalkMode() {
        val model = mapNavigationUi(
            listenerAccessGranted = true,
            bridgeEnabled = true,
            sessionRunning = true,
            bandNodePresent = true,
            selectedMode = NavProtocol.MODE_WALK,
            latest = activeLeft(),
        )
        assertEquals(NavigationPhase.ACTIVE, model.phase)
        assertEquals("\u5de6\u8f6c", model.headline)
        assertEquals("\u5de6\u8f6c", model.actionLabel)
        // The protocol action id stays in the model for the future icon lookup only.
        assertEquals("left", model.actionId)
        assertEquals("32 \u7c73", model.distanceText)
        assertEquals("\u4e2d\u5173\u6751\u5927\u8857", model.roadName)
        assertNull(model.exitText)
        assertEquals("\u6b65\u884c", model.modeLabel)
        assertTrue(model.showAsValidInstruction)
        assertFalse(model.guidance.contains("\u9996\u6b21\u4f7f\u7528"))
        assertFalse(model.guidance.contains("\u8bf7\u5728\u624b\u73af\u6253\u5f00\u5bfc\u822a\u9875"))
        assertFalse(model.headline.contains("left"))
        assertFalse(model.guidance.contains("left"))
        // 桥/会话/节点四种组合的两行状态
        val bridgeOff = mapNavigationUi(
            listenerAccessGranted = true,
            bridgeEnabled = false,
            sessionRunning = true,
            bandNodePresent = true,
            selectedMode = NavProtocol.MODE_WALK,
            latest = activeLeft(),
        )
        assertEquals("开启后自动同步 Google 地图导航", bridgeOff.bridgeLine)
        assertNull(bridgeOff.bandLine)

        val sessionNotRunning = mapNavigationUi(
            listenerAccessGranted = true,
            bridgeEnabled = true,
            sessionRunning = false,
            bandNodePresent = true,
            selectedMode = NavProtocol.MODE_WALK,
            latest = activeLeft(),
        )
        assertEquals("\u5bfc\u822a\u6865\u542f\u52a8\u4e2d", sessionNotRunning.bridgeLine)
        assertNull(sessionNotRunning.bandLine)

        val noBandNode = mapNavigationUi(
            listenerAccessGranted = true,
            bridgeEnabled = true,
            sessionRunning = true,
            bandNodePresent = false,
            selectedMode = NavProtocol.MODE_WALK,
            latest = activeLeft(),
        )
        assertEquals("\u5bfc\u822a\u6865\u5df2\u5f00\u542f", noBandNode.bridgeLine)
        assertEquals("\u624b\u73af\uff1a\u672a\u53d1\u73b0\uff0c\u6b63\u5728\u8fde\u63a5", noBandNode.bandLine)

        val bandNodePresent = mapNavigationUi(
            listenerAccessGranted = true,
            bridgeEnabled = true,
            sessionRunning = true,
            bandNodePresent = true,
            selectedMode = NavProtocol.MODE_WALK,
            latest = activeLeft(),
        )
        assertEquals("\u5bfc\u822a\u6865\u5df2\u5f00\u542f", bandNodePresent.bridgeLine)
        assertEquals("\u624b\u73af\uff1a\u5df2\u53d1\u73b0\uff0c\u5c1a\u672a\u786e\u8ba4\u6536\u5230", bandNodePresent.bandLine)
    }

    @Test
    fun missingOrClosedStatesNeverInventAManeuver() {
        // active 且 action=null：UNAVAILABLE，不默认直行
        run {
            val m = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(action = null))
            assertEquals(NavigationPhase.UNAVAILABLE, m.phase)
            assertNull(m.actionLabel)
            assertFalse(m.headline == "\u76f4\u884c")
        }
        // active 且 action=mystery：UNAVAILABLE，actionId=null
        run {
            val m = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(action = "mystery"))
            assertEquals(NavigationPhase.UNAVAILABLE, m.phase)
            assertNull(m.actionId)
        }
        // active、distanceM=null、roadName=null：距离未知、路名为 null（主界面隐藏路名行）
        run {
            val m = mapNavigationUi(
                true, true, true, true, NavProtocol.MODE_WALK,
                activeLeft(distanceM = null, roadName = null),
            )
            assertEquals(NavigationPhase.ACTIVE, m.phase)
            assertEquals("\u8ddd\u79bb\u672a\u77e5", m.distanceText)
            assertNull(m.roadName)
        }
        // active、roundabout-exit、exitNumber=null：出口未知
        run {
            val m = mapNavigationUi(
                true, true, true, true, NavProtocol.MODE_WALK,
                activeLeft(action = "roundabout-exit", exitNumber = null),
            )
            assertEquals("\u51fa\u53e3\u672a\u77e5", m.exitText)
            assertFalse(m.exitText == "\u7b2c 2 \u51fa\u53e3")
        }
        // active、roundabout-enter、exitNumber=2：exitText=null
        run {
            val m = mapNavigationUi(
                true, true, true, true, NavProtocol.MODE_WALK,
                activeLeft(action = "roundabout-enter", exitNumber = 2),
            )
            assertNull(m.exitText)
        }
        // active、arrive、distanceM=10：distanceText=null，到达目的地
        run {
            val m = mapNavigationUi(
                true, true, true, true, NavProtocol.MODE_WALK,
                activeLeft(action = "arrive", distanceM = 10),
            )
            assertNull(m.distanceText)
            assertEquals("\u5230\u8fbe\u76ee\u7684\u5730", m.actionLabel)
        }
        // ended，即使 action=left 且路名有值：ENDED，不当作有效指令
        run {
            val m = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(navStatus = NavProtocol.STATUS_ENDED))
            assertEquals(NavigationPhase.ENDED, m.phase)
            assertNull(m.actionLabel)
            assertFalse(m.showAsValidInstruction)
        }
        // unrecognized：UNAVAILABLE
        run {
            val m = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(navStatus = NavProtocol.STATUS_UNRECOGNIZED))
            assertEquals(NavigationPhase.UNAVAILABLE, m.phase)
        }
        // idle、会话在跑、有节点、权限桥都开：WAITING
        run {
            val m = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(navStatus = NavProtocol.STATUS_IDLE))
            assertEquals(NavigationPhase.WAITING, m.phase)
            assertNull(m.actionLabel)
            assertEquals("请在手环打开导航页，再在 Google 地图开始导航。", m.guidance)
        }
        // 权限关：PERMISSION_OFF
        run {
            val m = mapNavigationUi(false, true, true, true, NavProtocol.MODE_WALK, activeLeft())
            assertEquals(NavigationPhase.PERMISSION_OFF, m.phase)
            assertNull(m.actionLabel)
        }
        // 桥关：BRIDGE_OFF
        run {
            val m = mapNavigationUi(true, false, true, true, NavProtocol.MODE_WALK, activeLeft())
            assertEquals(NavigationPhase.BRIDGE_OFF, m.phase)
            assertNull(m.actionLabel)
        }
        // 会话未跑：CONNECTING，即使 latest 仍是 left/32 米也不展示旧指令
        run {
            val m = mapNavigationUi(true, true, false, true, NavProtocol.MODE_WALK, activeLeft())
            assertEquals(NavigationPhase.CONNECTING, m.phase)
            assertFalse(m.showAsValidInstruction)
            assertNull(m.actionLabel)
            assertNull(m.actionId)
            assertNull(m.distanceText)
            assertNull(m.roadName)
        }
        // 会话在跑但当前无手环节点：CONNECTING，同样不展示旧指令
        run {
            val m = mapNavigationUi(true, true, true, false, NavProtocol.MODE_WALK, activeLeft())
            assertEquals(NavigationPhase.CONNECTING, m.phase)
            assertFalse(m.showAsValidInstruction)
            assertNull(m.actionLabel)
            assertNull(m.actionId)
            assertNull(m.distanceText)
            assertNull(m.roadName)
        }
        // selectedMode=cycle：骑行
        run {
            val m = mapNavigationUi(
                true, true, true, true, NavProtocol.MODE_CYCLE,
                activeLeft(navStatus = NavProtocol.STATUS_IDLE),
            )
            assertEquals(NavigationPhase.WAITING, m.phase)
            assertEquals("\u9a91\u884c", m.modeLabel)
        }
        // 监听器未连接达 5 秒：LISTENER_DISCONNECTED，隐藏旧指令（权限、桥、会话、节点全开）
        val listenerDown = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(), 5_000L)
        assertEquals(NavigationPhase.LISTENER_DISCONNECTED, listenerDown.phase)
        assertEquals("\u901a\u77e5\u8bfb\u53d6\u672a\u8fde\u63a5", listenerDown.headline)
        assertFalse(listenerDown.showAsValidInstruction)
        assertNull(listenerDown.actionLabel)
        // 同样入参但未达 5 秒宽限：仍 ACTIVE
        run {
            val m = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(), 4_999L)
            assertEquals(NavigationPhase.ACTIVE, m.phase)
        }
        // 权限关优先于监听器断开
        run {
            val m = mapNavigationUi(false, true, true, true, NavProtocol.MODE_WALK, activeLeft(), 60_000L)
            assertEquals(NavigationPhase.PERMISSION_OFF, m.phase)
        }
        // 桥关优先于监听器断开
        run {
            val m = mapNavigationUi(true, false, true, true, NavProtocol.MODE_WALK, activeLeft(), 60_000L)
            assertEquals(NavigationPhase.BRIDGE_OFF, m.phase)
        }
        // 上屏字段（headline/guidance/roadName/distanceText/exitText）不得出现协议标识或审查者措辞
        val results = listOf(
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(action = null)),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(action = "mystery")),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(distanceM = null, roadName = null)),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(action = "roundabout-exit", exitNumber = null)),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(action = "roundabout-enter", exitNumber = 2)),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(action = "arrive", distanceM = 10)),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(navStatus = NavProtocol.STATUS_ENDED)),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(navStatus = NavProtocol.STATUS_UNRECOGNIZED)),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, activeLeft(navStatus = NavProtocol.STATUS_IDLE)),
            mapNavigationUi(false, true, true, true, NavProtocol.MODE_WALK, activeLeft()),
            mapNavigationUi(true, false, true, true, NavProtocol.MODE_WALK, activeLeft()),
            mapNavigationUi(true, true, false, true, NavProtocol.MODE_WALK, activeLeft()),
            mapNavigationUi(true, true, true, false, NavProtocol.MODE_WALK, activeLeft()),
            mapNavigationUi(true, true, true, true, NavProtocol.MODE_CYCLE, activeLeft(navStatus = NavProtocol.STATUS_IDLE)),
            listenerDown,
        )
        val banned = listOf("left", "slight-right", "roundabout-exit", "\u4e0d\u9ed8\u8ba4\u76f4\u884c", "\u901a\u9053\u4efb\u52a1", "\u5bfc\u822a\u4e2d", "\u5df2\u786e\u8ba4", "\u5df2\u8fde\u63a5", "\u5df2\u6536\u5230")
        for (m in results) {
            for (word in banned) {
                assertFalse(m.headline.contains(word))
                assertFalse(m.guidance.contains(word))
                m.roadName?.let { assertFalse(it.contains(word)) }
                m.distanceText?.let { assertFalse(it.contains(word)) }
                m.exitText?.let { assertFalse(it.contains(word)) }
                assertFalse(m.bridgeLine.contains(word))
                m.bandLine?.let { assertFalse(it.contains(word)) }
            }
        }
    }
}
