package kr.moborn.hud

/** 내비 앱 알림 문구에서 뽑아낸 안내 정보. */
data class ParsedNav(
    val turn: Turn = Turn.NONE,
    val distM: Int = -1,
    val remainM: Int = -1,
    val remainMin: Int = -1,
    val eta: String = "",
    val aux: String = "",
    val instr: String = "",
) {
    /** 화면에 쓸 만한 정보가 하나라도 있는가 */
    val useful: Boolean
        get() = turn != Turn.NONE || distM >= 0 || remainM >= 0 || remainMin >= 0 || eta.isNotEmpty()
}

/**
 * 티맵, 네이버 지도 등의 길안내 알림 문구를 해석한다.
 * 앱마다 문구가 다르고 바뀔 수 있어서, 특정 형식에 기대지 않고 단어와 숫자를 찾아낸다.
 */
object NavTextParser {
    private const val NUM = """(\d+(?:[.,]\d+)?)"""
    private val DIST = Regex("""$NUM\s*(km|㎞|킬로미터|킬로|m|미터)""", RegexOption.IGNORE_CASE)
    private val AUX = Regex(
        """([가-힣A-Za-z0-9()]*(?:휴게소|주유소|충전소|졸음쉼터))[^·|\n]{0,12}?$NUM\s*(km|㎞|m)""",
        RegexOption.IGNORE_CASE,
    )
    private val HOURS = Regex("""(\d+)\s*시간(?:\s*(\d+)\s*분)?""")
    private val MINS = Regex("""(\d+)\s*분(?!기)""")
    private val CLOCK = Regex("""(오전|오후)?\s*(\d{1,2}):(\d{2})""")
    private val ARRIVED = Regex("""목적지[^·|\n]{0,10}도착|도착했|도착하였""")

    fun parse(texts: List<String>): ParsedNav {
        val parts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        var all = parts.joinToString(" · ")
        if (all.isEmpty()) return ParsedNav()

        // 1. 휴게소·주유소 거리는 회전 거리와 헷갈리지 않게 먼저 떼어낸다.
        var aux = ""
        AUX.find(all)?.let { m ->
            aux = "${m.groupValues[1]} ${m.groupValues[2]}${m.groupValues[3].lowercase()}"
            all = all.removeRange(m.range)
        }

        // 2. 도착 예정 시각
        var eta = ""
        CLOCK.find(all)?.let { m ->
            var h = m.groupValues[2].toInt()
            if (m.groupValues[1] == "오후" && h < 12) h += 12
            if (m.groupValues[1] == "오전" && h == 12) h = 0
            eta = String.format("%02d:%s", h, m.groupValues[3])
            all = all.removeRange(m.range)
        }

        // 3. 남은 시간
        var remainMin = -1
        val hm = HOURS.find(all)
        if (hm != null) {
            remainMin = hm.groupValues[1].toInt() * 60 + (hm.groupValues[2].toIntOrNull() ?: 0)
        } else {
            MINS.find(all)?.let { remainMin = it.groupValues[1].toInt() }
        }

        // 4. 거리: 앞뒤에 '남', '총', '도착'이 붙으면 전체 남은 거리, 아니면 다음 안내까지 거리
        var distM = -1
        var remainM = -1
        val unlabeled = ArrayList<Int>()
        for (m in DIST.findAll(all)) {
            val meters = toMeters(m.groupValues[1], m.groupValues[2])
            val from = (m.range.first - 6).coerceAtLeast(0)
            val to = (m.range.last + 7).coerceAtMost(all.length)
            val around = all.substring(from, to)
            if (around.contains("남") || around.contains("총") || around.contains("도착")) {
                if (remainM < 0) remainM = meters
            } else {
                unlabeled += meters
            }
        }
        if (unlabeled.isNotEmpty()) distM = unlabeled[0]
        if (remainM < 0 && unlabeled.size >= 2 && unlabeled[1] > unlabeled[0]) remainM = unlabeled[1]

        // 5. 회전 방향
        val turn = turnOf(all)
        val instr = parts.firstOrNull { turnOf(it) != Turn.NONE }?.take(30) ?: ""

        return ParsedNav(turn, distM, remainM, remainMin, eta, aux, instr)
    }

    fun turnOf(s: String): Turn = when {
        ARRIVED.containsMatchIn(s) -> Turn.ARRIVE
        s.contains("유턴") || s.contains("U턴") || s.contains("u턴") -> Turn.UTURN
        Regex("""(7|8)시\s*방향""").containsMatchIn(s) -> Turn.SHARP_LEFT
        Regex("""(10|11)시\s*방향""").containsMatchIn(s) -> Turn.SLIGHT_LEFT
        Regex("""(?<!\d)(1|2)시\s*방향""").containsMatchIn(s) -> Turn.SLIGHT_RIGHT
        Regex("""(4|5)시\s*방향""").containsMatchIn(s) -> Turn.SHARP_RIGHT
        s.contains("좌회전") -> Turn.LEFT
        s.contains("우회전") -> Turn.RIGHT
        s.contains("왼쪽") || s.contains("좌측") -> Turn.SLIGHT_LEFT
        s.contains("오른쪽") || s.contains("우측") -> Turn.SLIGHT_RIGHT
        s.contains("직진") -> Turn.STRAIGHT
        else -> Turn.NONE
    }

    private fun toMeters(num: String, unit: String): Int {
        val v = num.replace(",", "").toDoubleOrNull() ?: return -1
        val km = unit.lowercase().let { it == "km" || it == "㎞" || it.startsWith("킬로") }
        return if (km) (v * 1000).toInt() else v.toInt()
    }
}
