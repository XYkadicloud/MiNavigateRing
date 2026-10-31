# 转向图标哈希标定（Google 地图）

Google 地图把**转向方向**主要放在通知的“转向图标”（maneuver icon，一张 bitmap）里，文字里往往只有路名和距离。本应用的做法是：把这张图标缩到 32×32、取**红通道**、切成 4×4 共 16 个区域算平均值，拼成 `"a-b-c-…-p"` 这样的字符串，再查一张“哈希 → 动作”的表（`MapsParser.iconHashMap`）。

这张表来自开源项目 `GmapNavNotiCatcher` 在一台设备上的采样。**不同手机 / DPI / 系统主题 / 地图版本**都可能让同一动作的哈希值略有差异，导致查表落空。落空时不会乱猜：解析器会退回**文字通道**（能读距离、路名，方向可能缺失），手环显示“暂无导航”。所以这张表需要在你自己的机型上**重新标定**一次。

## 什么时候需要标定

- 手环在导航时长期只显示“暂无导航”，但手机主页能看到 Google 地图在导航。
- 换了一台手机 / 改了系统深色主题 / 升级了 Google 地图之后，方向突然读不出来了。

## 标定步骤

1. **打日志**：在 `MapsNotificationService.onNotificationPosted` 里，取到 `icon` 之后临时加一行，把哈希和文字都打到 Logcat：

   ```kotlin
   val icon = sbn.notification.largeIcon
       ?: (extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)
   if (icon != null) {
       val px = IntArray(icon.width * icon.height)
       icon.getPixels(px, 0, icon.width, 0, 0, icon.width, icon.height)
       android.util.Log.i("MapsIcon", "hash=${MapsParser.hashIcon(px, icon.width, icon.height)} text=${fields.joinToString(" | ")}")
   }
   ```

2. **采集样本**：用 Google 地图步行/骑行导航，依次走到**左转、右转、直行、环岛、掉头、到达**等动作。每出现一个新动作，在 Logcat 里抄下 `hash=…` 和 `text=…`。同一个动作多采几条（不同距离下图标一般不变，但顺带确认）。

3. **填表**：在 `MapsParser.kt` 的 `iconHashMap` 里加一条 `"a-b-…-p" to "动作"`。动作取值见下表（与手环 14 种箭头一一对应）：

   | 动作 | 含义 |
   |---|---|
   | `left` / `right` | 左转 / 右转 |
   | `slight-left` / `slight-right` | 左前方 / 右前方（缓弯） |
   | `sharp-left` / `sharp-right` | 左后方 / 右后方（急弯） |
   | `keep-left` / `keep-right` | 靠左 / 靠右（分岔） |
   | `straight` | 直行 / 继续 |
   | `uturn-left` / `uturn-right` | 掉头 |
   | `roundabout-enter` / `roundabout-exit` | 进入环岛 / 驶出环岛 |
   | `arrive` | 到达 |

4. **验证**：改完重装，再走一遍同样的动作，确认手环显示对应箭头。`./gradlew :app:testDebugUnitTest` 里的 `MapsParserTest` 会校验哈希函数本身（用合成位图），但**真机哈希必须靠这一步肉眼确认**。

## 说明与限制

- **只读红通道**：和 `GmapNavNotiCatcher` 保持一致，这样社区里已有的种子表才有参考价值。若你改算法（比如换成灰度），旧表全部作废。
- **只做本地哈希，不上传**：图标像素只在内存里算一次，既不写盘也不联网。
- **DPI 容错有限**：先把图标等比缩到 32×32 再切 16 块，对常见 DPI 有一定容忍；但如果 Google 把图标画风整个换掉（比如换成圆形徽章），哈希会整体偏移，必须重标。
- **文字通道兜底**：即使图标表没命中，只要通知里有“左转 / Turn left”这类文字，仍能识别方向；中文/英文都支持。所以最坏情况是“方向缺失”而不是“方向错误”。
