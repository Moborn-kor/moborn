package kr.moborn.hud.phone

import kr.moborn.hud.HudState
import kr.moborn.hud.LatLon
import kr.moborn.hud.ParsedNav
import kr.moborn.hud.Progress
import kr.moborn.hud.RouteTracker
import kr.moborn.hud.Turn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 앱 안의 여러 부분(화면, 서비스, 알림 수신)이 함께 보는 현재 상태. */
object HudBus {
    /** 정보 출처: SRC_ROUTE(카카오 경로로 자체 안내), SRC_NOTIF(내비 앱 알림 읽기), SRC_DEMO */
    @Volatile var source: String = SRC_ROUTE
    /** 자체 안내의 이동 수단: car / walk / transit */
    @Volatile var travel: String = HudState.MODE_WALK
    @Volatile var connected: Boolean = false
    @Volatile var useHead: Boolean = false

    // 자동차: 내비 알림에서 읽은 내용
    @Volatile var nav: ParsedNav? = null
    @Volatile var navApp: String = ""

    // 위치
    @Volatile var speedKmh: Int = -1
    @Volatile var position: LatLon? = null

    // 자체 경로 안내
    @Volatile var tracker: RouteTracker? = null
    @Volatile var progress: Progress? = null
    @Volatile var destName: String = ""
    @Volatile var note: String = ""

    @Volatile var demoStartedAt: Long = 0

    private val logLines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun log(s: String) {
        synchronized(logLines) {
            logLines.addLast("[${clock.format(Date())}] $s")
            while (logLines.size > 80) logLines.removeFirst()
        }
        changed()
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun changed() = listeners.forEach { it() }

    /** 지금 안경에 보낼 내용 */
    fun build(): HudState = when (source) {
        SRC_NOTIF -> fromNotification()
        SRC_ROUTE -> fromRoute()
        else -> demo()
    }

    private fun fromNotification(): HudState {
        val n = nav
        return if (n == null || !n.useful) {
            HudState(mode = HudState.MODE_CAR, speedKmh = speedKmh, note = "내비 안내 대기 중")
        } else {
            HudState(
                mode = HudState.MODE_CAR, turn = n.turn, distM = n.distM, instr = n.instr,
                speedKmh = speedKmh, remainM = n.remainM, remainMin = n.remainMin,
                eta = n.eta, aux = n.aux,
            )
        }
    }

    private fun fromRoute(): HudState {
        val p = progress
        val t = tracker
        val pos = position
        val speed = if (travel == HudState.MODE_CAR) speedKmh else -1
        if (p == null || t == null || pos == null) {
            return HudState(mode = travel, dest = destName, speedKmh = speed,
                note = note.ifEmpty { "목적지를 정해 주세요" })
        }
        val range = when (travel) {
            HudState.MODE_CAR -> 500
            HudState.MODE_TRANSIT -> 300
            else -> 150
        }
        val eta = SimpleDateFormat("HH:mm", Locale.KOREA)
            .format(Date(System.currentTimeMillis() + p.remainMin * 60_000L))
        return HudState(
            mode = travel,
            turn = if (p.arrived) Turn.ARRIVE else p.turn,
            distM = p.distToNextM, instr = p.text.take(40), speedKmh = speed,
            remainM = p.remainM, remainMin = p.remainMin, eta = eta,
            dest = destName, bearing = p.bearing, useHead = useHead, note = note,
            path = t.ahead(pos, range), pathM = range,
        )
    }

    /** 안경 표시를 눈으로 확인하기 위한 가짜 주행. 4초마다 장면이 바뀐다. */
    private fun demo(): HudState {
        val scenes = listOf(
            HudState(HudState.MODE_CAR, Turn.STRAIGHT, 1200, "직진", speedKmh = 78, remainM = 42_300,
                remainMin = 38, eta = "15:20", dest = "서울역", aux = "안성휴게소 12km"),
            HudState(HudState.MODE_CAR, Turn.RIGHT, 300, "우회전", speedKmh = 52, remainM = 41_100,
                remainMin = 37, eta = "15:20", dest = "서울역", aux = "주유소 2.1km",
                path = listOf(0, 0, 0, 300, 200, 310, 260, 420), pathM = 500),
            HudState(HudState.MODE_CAR, Turn.SLIGHT_LEFT, 800, "왼쪽 고속도로 진입", speedKmh = 64,
                remainM = 40_200, remainMin = 36, eta = "15:19", dest = "서울역"),
            HudState(HudState.MODE_CAR, Turn.UTURN, 150, "유턴", speedKmh = 31, remainM = 2_400,
                remainMin = 6, eta = "15:21", dest = "서울역"),
            HudState(HudState.MODE_WALK, Turn.LEFT, 45, "좌회전 후 120m 이동", remainM = 620,
                remainMin = 9, eta = "15:31", dest = "시청역 2번 출구", bearing = 270,
                path = listOf(0, 0, 0, 45, -120, 48, -125, 110), pathM = 150),
            HudState(HudState.MODE_TRANSIT, Turn.STRAIGHT, 180, "버스 · 472번 승차, 3개 정류장 이동",
                remainM = 4_200, remainMin = 21, eta = "15:43", dest = "강남역",
                path = listOf(0, 0, 10, 120, 90, 240, 95, 300), pathM = 300),
            HudState(HudState.MODE_WALK, Turn.ARRIVE, 10, "도착", remainM = 10, remainMin = 1,
                dest = "시청역 2번 출구"),
        )
        val i = ((System.currentTimeMillis() - demoStartedAt) / 4000 % scenes.size).toInt()
        return scenes[i]
    }

    const val SRC_ROUTE = "route"
    const val SRC_NOTIF = "notif"
    const val SRC_DEMO = "demo"
}
