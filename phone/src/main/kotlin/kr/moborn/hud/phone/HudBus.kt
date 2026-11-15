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
    @Volatile var mode: String = HudState.MODE_CAR
    @Volatile var connected: Boolean = false
    @Volatile var useHead: Boolean = false

    // 자동차: 내비 알림에서 읽은 내용
    @Volatile var nav: ParsedNav? = null
    @Volatile var navApp: String = ""

    // 위치
    @Volatile var speedKmh: Int = -1
    @Volatile var position: LatLon? = null

    // 도보: 경로 안내
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
    fun build(): HudState = when (mode) {
        HudState.MODE_CAR -> car()
        HudState.MODE_WALK -> walk()
        HudState.MODE_DEMO -> demo()
        else -> HudState()
    }

    private fun car(): HudState {
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

    private fun walk(): HudState {
        val p = progress
        return if (p == null) {
            HudState(mode = HudState.MODE_WALK, dest = destName, note = note.ifEmpty { "목적지를 정해 주세요" })
        } else {
            HudState(
                mode = HudState.MODE_WALK,
                turn = if (p.arrived) Turn.ARRIVE else p.turn,
                distM = p.distToNextM, instr = p.text.take(40),
                remainM = p.remainM, remainMin = p.remainMin,
                dest = destName, bearing = p.bearing, useHead = useHead, note = note,
            )
        }
    }

    /** 안경 표시를 눈으로 확인하기 위한 가짜 주행. 4초마다 장면이 바뀐다. */
    private fun demo(): HudState {
        val scenes = listOf(
            HudState(HudState.MODE_CAR, Turn.STRAIGHT, 1200, "직진", speedKmh = 78, remainM = 42_300,
                remainMin = 38, eta = "15:20", dest = "서울역", aux = "안성휴게소 12km"),
            HudState(HudState.MODE_CAR, Turn.RIGHT, 300, "우회전", speedKmh = 52, remainM = 41_100,
                remainMin = 37, eta = "15:20", dest = "서울역", aux = "주유소 2.1km"),
            HudState(HudState.MODE_CAR, Turn.SLIGHT_LEFT, 800, "왼쪽 고속도로 진입", speedKmh = 64,
                remainM = 40_200, remainMin = 36, eta = "15:19", dest = "서울역"),
            HudState(HudState.MODE_CAR, Turn.UTURN, 150, "유턴", speedKmh = 31, remainM = 2_400,
                remainMin = 6, eta = "15:21", dest = "서울역"),
            HudState(HudState.MODE_WALK, Turn.LEFT, 45, "좌회전 후 120m 이동", remainM = 620,
                remainMin = 9, dest = "시청역 2번 출구", bearing = 270),
            HudState(HudState.MODE_WALK, Turn.ARRIVE, 10, "도착", remainM = 10, remainMin = 1,
                dest = "시청역 2번 출구"),
        )
        val i = ((System.currentTimeMillis() - demoStartedAt) / 4000 % scenes.size).toInt()
        return scenes[i]
    }
}
