package kr.moborn.hud

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 휴대폰과 안경이 블루투스로 주고받는 약속. */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("6d6f626f-726e-4855-4400-000000000001")
    const val SERVICE_NAME = "MobornHUD"
}

/** 회전 종류. angle은 위쪽(직진)을 0도로 본 시계 방향 각도. */
enum class Turn(val angle: Int, val label: String) {
    NONE(0, ""),
    STRAIGHT(0, "직진"),
    SLIGHT_LEFT(-45, "왼쪽 방향"),
    LEFT(-90, "좌회전"),
    SHARP_LEFT(-135, "크게 좌회전"),
    SLIGHT_RIGHT(45, "오른쪽 방향"),
    RIGHT(90, "우회전"),
    SHARP_RIGHT(135, "크게 우회전"),
    UTURN(180, "유턴"),
    ARRIVE(0, "도착");
}

/**
 * 안경에 표시할 내용 전부. 값이 없으면 숫자는 -1, 글자는 빈 문자열.
 * 한 줄짜리 JSON으로 바꿔 전송한다.
 */
data class HudState(
    val mode: String = MODE_IDLE,
    val turn: Turn = Turn.NONE,
    /** 다음 안내 지점까지 남은 거리(m) */
    val distM: Int = -1,
    val instr: String = "",
    val road: String = "",
    val speedKmh: Int = -1,
    val remainM: Int = -1,
    val remainMin: Int = -1,
    /** 도착 예정 시각 "15:20" */
    val eta: String = "",
    val dest: String = "",
    /** 휴게소, 주유소 같은 보조 정보 한 줄 */
    val aux: String = "",
    /** 다음 지점의 절대 방위(북쪽 0도, 시계 방향). 도보에서 머리 방향 화살표에 쓴다. */
    val bearing: Int = -1,
    val useHead: Boolean = false,
    /** 상태 안내 문구(경로 탐색 중 등) */
    val note: String = "",
    /** 미니맵: 앞으로 갈 경로. 내 위치가 원점, 진행 방향이 +y인 미터 좌표 [x0,y0,x1,y1,...] */
    val path: List<Int> = emptyList(),
    /** 미니맵이 담는 앞쪽 거리(m) */
    val pathM: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("mode", mode); put("turn", turn.name); put("distM", distM)
        put("instr", instr); put("road", road); put("speedKmh", speedKmh)
        put("remainM", remainM); put("remainMin", remainMin); put("eta", eta)
        put("dest", dest); put("aux", aux); put("bearing", bearing)
        put("useHead", useHead); put("note", note)
        if (path.isNotEmpty()) {
            put("path", JSONArray(path)); put("pathM", pathM)
        }
    }.toString()

    companion object {
        const val MODE_IDLE = "idle"
        const val MODE_CAR = "car"
        const val MODE_WALK = "walk"
        const val MODE_TRANSIT = "transit"
        const val MODE_DEMO = "demo"

        fun fromJson(line: String): HudState? = try {
            val o = JSONObject(line)
            HudState(
                mode = o.optString("mode", MODE_IDLE),
                turn = runCatching { Turn.valueOf(o.optString("turn", "NONE")) }.getOrDefault(Turn.NONE),
                distM = o.optInt("distM", -1),
                instr = o.optString("instr", ""),
                road = o.optString("road", ""),
                speedKmh = o.optInt("speedKmh", -1),
                remainM = o.optInt("remainM", -1),
                remainMin = o.optInt("remainMin", -1),
                eta = o.optString("eta", ""),
                dest = o.optString("dest", ""),
                aux = o.optString("aux", ""),
                bearing = o.optInt("bearing", -1),
                useHead = o.optBoolean("useHead", false),
                note = o.optString("note", ""),
                path = o.optJSONArray("path")?.let { a -> List(a.length()) { a.optInt(it) } } ?: emptyList(),
                pathM = o.optInt("pathM", 0),
            )
        } catch (e: Exception) {
            null
        }
    }
}

object Fmt {
    fun dist(m: Int): String = when {
        m < 0 -> ""
        m < 1000 -> "${m}m"
        m < 10_000 -> String.format("%.1fkm", m / 1000.0)
        else -> "${m / 1000}km"
    }

    fun mins(min: Int): String = when {
        min < 0 -> ""
        min < 60 -> "${min}분"
        else -> "${min / 60}시간 ${min % 60}분"
    }
}
