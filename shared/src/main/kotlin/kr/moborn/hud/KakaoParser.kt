package kr.moborn.hud

import org.json.JSONArray
import org.json.JSONObject

/**
 * 카카오 길찾기 응답(자동차, 도보, 대중교통)을 Route로 바꾼다.
 *
 * 세 API의 응답 모양이 서로 다르고 도보·대중교통은 2026년에 새로 나온 것이라,
 * 특정 필드 경로에 기대지 않고 다음 규칙으로 읽는다.
 *  - 경로 선: "vertexes", "points", "path", "coords", "coordinates" 아래의 좌표 목록
 *  - 안내 지점: "guidance" 문구를 가진 객체 (좌표 x, y 또는 자기 경로의 첫 점)
 *  - 전체 거리·시간: 가장 바깥쪽에서 처음 만나는 "distance", "duration"
 * 여러 경로가 오면 첫 번째만 쓴다.
 */
object KakaoParser {
    private val LINE_KEYS = setOf("vertexes", "points", "path", "coords", "coordinates", "polyline")

    private class Guide(val text: String, val type: String, val at: LatLon?, val firstPointIndex: Int)

    fun parse(json: String): Route {
        var root: Any = JSONObject(json)
        // 경로가 여러 개면 첫 번째만
        (root as JSONObject).optJSONArray("routes")?.let { if (it.length() > 0) root = it.get(0) }

        // 구간 경계에서 같은 점이 겹쳐 오므로, 일단 그대로 모은 뒤 겹친 점을 합친다.
        val raw = ArrayList<LatLon>()
        val guides = ArrayList<Guide>()
        walk(root, null, raw, guides, 0)

        val points = ArrayList<LatLon>()
        val indexOf = IntArray(raw.size) // 모은 순서 -> 합친 뒤 위치
        for (i in raw.indices) {
            if (points.isEmpty() || points.last() != raw[i]) points += raw[i]
            indexOf[i] = points.size - 1
        }
        val lastIndex = (points.size - 1).coerceAtLeast(0)

        val steps = guides.map { g ->
            val idx = when {
                g.at != null && points.isNotEmpty() -> nearestIndex(points, g.at)
                g.firstPointIndex < raw.size -> indexOf[g.firstPointIndex]
                else -> lastIndex
            }
            Step(idx, turnOf(g), label(g))
        }.sortedBy { it.pointIndex }

        return Route(
            points = points,
            steps = steps,
            totalM = firstNumber(root, "distance"),
            totalSec = firstNumber(root, "duration"),
        )
    }

    private fun turnOf(g: Guide): Turn {
        val t = NavTextParser.turnOf(g.text)
        if (t != Turn.NONE) return t
        return if (g.text.contains("도착") || g.text.contains("목적지")) Turn.ARRIVE else Turn.STRAIGHT
    }

    /** 대중교통 구간은 종류를 앞에 붙인다. */
    private fun label(g: Guide): String = when (g.type.uppercase()) {
        "BUS" -> "버스 · ${g.text}"
        "SUBWAY" -> "지하철 · ${g.text}"
        else -> g.text
    }

    /**
     * parentStart: 이 노드를 담은 객체가 시작될 때까지 모인 점의 수.
     * 안내 문구가 자기 경로 없이 "properties" 같은 곳에 들어 있으면 부모 구간의 시작점을 쓴다.
     */
    private fun walk(node: Any?, key: String?, points: ArrayList<LatLon>, guides: ArrayList<Guide>, parentStart: Int) {
        when (node) {
            is JSONObject -> {
                val start = points.size
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    walk(node.opt(k), k, points, guides, start)
                }
                val text = node.optString("guidance", "")
                if (text.isNotBlank()) {
                    val x = node.optDouble("x", Double.NaN)
                    val y = node.optDouble("y", Double.NaN)
                    val hasOwnLine = points.size > start
                    guides += Guide(text, node.optString("type", ""), toLatLon(x, y), if (hasOwnLine) start else parentStart)
                }
            }
            is JSONArray -> {
                if (key != null && key in LINE_KEYS && addLine(node, points)) return
                for (i in 0 until node.length()) walk(node.opt(i), key, points, guides, parentStart)
            }
            is String -> if (key != null && key in LINE_KEYS) addNumbers(numbersIn(node), points)
        }
    }

    /** 좌표 목록이면 points에 더하고 true. 모양: [x,y,x,y,…], [[x,y],…], [{x,y},…] */
    private fun addLine(arr: JSONArray, points: ArrayList<LatLon>): Boolean {
        if (arr.length() == 0) return false
        val first = arr.opt(0)
        return when {
            first is Number -> {
                addNumbers(List(arr.length()) { arr.optDouble(it) }, points)
                true
            }
            first is JSONArray && first.length() >= 2 && first.opt(0) is Number -> {
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONArray(i) ?: continue
                    add(toLatLon(p.optDouble(0), p.optDouble(1)), points)
                }
                true
            }
            first is JSONObject && first.has("x") && first.has("y") && !first.has("guidance") -> {
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    add(toLatLon(p.optDouble("x", Double.NaN), p.optDouble("y", Double.NaN)), points)
                }
                true
            }
            else -> false
        }
    }

    private fun addNumbers(nums: List<Double>, points: ArrayList<LatLon>) {
        var i = 0
        while (i + 1 < nums.size) {
            add(toLatLon(nums[i], nums[i + 1]), points)
            i += 2
        }
    }

    private fun add(p: LatLon?, points: ArrayList<LatLon>) {
        if (p != null) points += p
    }

    /** 순서가 (경도, 위도)든 (위도, 경도)든 한국 범위로 판별한다. 범위 밖이면 null. */
    private fun toLatLon(a: Double, b: Double): LatLon? = when {
        a.isNaN() || b.isNaN() -> null
        a in 124.0..132.0 && b in 33.0..39.5 -> LatLon(b, a)
        b in 124.0..132.0 && a in 33.0..39.5 -> LatLon(a, b)
        else -> null
    }

    private fun numbersIn(s: String): List<Double> =
        Regex("""-?\d+(?:\.\d+)?""").findAll(s).mapNotNull { it.value.toDoubleOrNull() }.toList()

    private fun nearestIndex(points: List<LatLon>, p: LatLon): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in points.indices) {
            val d = Geo.distance(points[i], p)
            if (d < bestD) {
                bestD = d; best = i
            }
        }
        return best
    }

    /** 얕은 곳부터 찾아 처음 나오는 숫자 값. 없으면 0. */
    private fun firstNumber(root: Any, name: String): Int {
        var level = listOf(root)
        repeat(6) {
            val next = ArrayList<Any>()
            for (n in level) {
                when (n) {
                    is JSONObject -> {
                        val v = n.opt(name)
                        if (v is Number) return v.toInt()
                        val keys = n.keys()
                        while (keys.hasNext()) n.opt(keys.next())?.let { next += it }
                    }
                    is JSONArray -> for (i in 0 until n.length()) n.opt(i)?.let { next += it }
                }
            }
            level = next
        }
        return 0
    }
}
