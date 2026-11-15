package kr.moborn.hud

import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** a에서 b를 바라보는 방위. 북쪽 0도, 시계 방향, 0~359. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /**
     * 점 p를 선분 a-b에 내렸을 때 (선분 위 비율 0~1, 선분까지 거리 m).
     * 좁은 범위라 평면으로 근사한다.
     */
    fun project(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val kx = 111_320.0 * cos(Math.toRadians(a.lat))
        val ky = 110_540.0
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t
        val dy = py - by * t
        return t to sqrt(dx * dx + dy * dy)
    }
}

/** 경로 위 안내 지점. pointIndex는 Route.points 안의 위치. */
data class Step(val pointIndex: Int, val turn: Turn, val text: String)

data class Route(
    val points: List<LatLon>,
    val steps: List<Step>,
    val totalM: Int,
    val totalSec: Int,
)

data class Progress(
    val turn: Turn,
    val distToNextM: Int,
    val text: String,
    val remainM: Int,
    val remainMin: Int,
    /** 다음 안내 지점의 절대 방위 */
    val bearing: Int,
    /** 경로에서 벗어난 거리(m) */
    val offRouteM: Int,
    val arrived: Boolean,
)

/** 현재 위치를 경로에 맞춰 다음 안내와 남은 거리를 계산한다. */
class RouteTracker(val route: Route) {
    private val cum = DoubleArray(route.points.size)
    private var seg = 0

    init {
        for (i in 1 until route.points.size) {
            cum[i] = cum[i - 1] + Geo.distance(route.points[i - 1], route.points[i])
        }
    }

    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum.last()

    fun update(pos: LatLon): Progress {
        val pts = route.points
        if (pts.size < 2) return Progress(Turn.NONE, -1, "", -1, -1, -1, 0, false)

        // 가장 가까운 선분 찾기. 먼저 현재 위치 근처만 보고, 멀어졌으면 전체를 본다.
        var best = nearest(pos, (seg - 3).coerceAtLeast(0), (seg + 12).coerceAtMost(pts.size - 2))
        if (best.third > 30) {
            val full = nearest(pos, 0, pts.size - 2)
            if (full.third < best.third) best = full
        }
        seg = best.first
        val along = cum[seg] + (cum[seg + 1] - cum[seg]) * best.second
        val remain = (lengthM - along).coerceAtLeast(0.0)

        // 지나가지 않은 첫 안내 지점 (출발 지점 안내는 건너뛴다)
        val next = route.steps.firstOrNull { it.pointIndex > 0 && cum[it.pointIndex] > along + 3 }
            ?: route.steps.lastOrNull()
        val target = pts[(next?.pointIndex ?: (pts.size - 1)).coerceIn(0, pts.size - 1)]
        val distToNext = if (next == null) remain else (cum[next.pointIndex] - along).coerceAtLeast(0.0)

        val speed = if (route.totalSec > 0 && route.totalM > 0) {
            route.totalM.toDouble() / route.totalSec
        } else {
            1.2
        }
        return Progress(
            turn = next?.turn ?: Turn.ARRIVE,
            distToNextM = distToNext.roundToInt(),
            text = next?.text ?: "",
            remainM = remain.roundToInt(),
            remainMin = Math.ceil(remain / speed / 60).toInt(),
            bearing = Geo.bearing(pos, target).roundToInt() % 360,
            offRouteM = best.third.roundToInt(),
            arrived = remain < 15,
        )
    }

    /** (선분 번호, 선분 위 비율, 거리 m) */
    private fun nearest(pos: LatLon, from: Int, to: Int): Triple<Int, Double, Double> {
        var bi = from
        var bt = 0.0
        var bd = Double.MAX_VALUE
        for (i in from..to) {
            val (t, d) = Geo.project(pos, route.points[i], route.points[i + 1])
            if (d < bd) {
                bd = d; bt = t; bi = i
            }
        }
        return Triple(bi, bt, bd)
    }
}

/** 티맵 보행자 경로 응답(GeoJSON)을 Route로 바꾼다. */
object TmapParser {
    fun turnOf(code: Int): Turn = when (code) {
        11 -> Turn.STRAIGHT
        12 -> Turn.LEFT
        13 -> Turn.RIGHT
        14 -> Turn.UTURN
        16 -> Turn.SHARP_LEFT
        17 -> Turn.SLIGHT_LEFT
        18 -> Turn.SLIGHT_RIGHT
        19 -> Turn.SHARP_RIGHT
        201 -> Turn.ARRIVE
        else -> Turn.STRAIGHT // 횡단보도, 육교, 계단 등은 문구로 안내
    }

    fun parsePedestrian(json: String): Route {
        val features = JSONObject(json).getJSONArray("features")
        val points = ArrayList<LatLon>()
        val steps = ArrayList<Step>()
        var totalM = 0
        var totalSec = 0
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val g = f.getJSONObject("geometry")
            val p = f.optJSONObject("properties") ?: JSONObject()
            if (p.has("totalDistance")) totalM = p.optInt("totalDistance")
            if (p.has("totalTime")) totalSec = p.optInt("totalTime")
            val c = g.getJSONArray("coordinates")
            when (g.getString("type")) {
                "Point" -> {
                    val idx = (points.size - 1).coerceAtLeast(0)
                    steps += Step(idx, turnOf(p.optInt("turnType", 11)), p.optString("description", ""))
                }
                "LineString" -> for (j in 0 until c.length()) {
                    val xy = c.getJSONArray(j)
                    val ll = LatLon(xy.getDouble(1), xy.getDouble(0))
                    if (points.isEmpty() || points.last() != ll) points += ll
                }
            }
        }
        return Route(points, steps, totalM, totalSec)
    }
}
