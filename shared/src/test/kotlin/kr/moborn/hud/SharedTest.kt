package kr.moborn.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTest {
    @Test
    fun stateSurvivesJsonRoundTrip() {
        val s = HudState(
            mode = HudState.MODE_CAR, turn = Turn.RIGHT, distM = 300, instr = "우회전",
            speedKmh = 62, remainM = 12_400, remainMin = 18, eta = "15:20",
            dest = "서울역", aux = "안성휴게소 12km", bearing = 45, useHead = true,
        )
        assertEquals(s, HudState.fromJson(s.toJson()))
        assertEquals(null, HudState.fromJson("not json"))
    }

    @Test
    fun parsesTurnAndDistance() {
        val p = NavTextParser.parse(listOf("300m 앞 우회전", "남은 거리 12.4km · 18분 · 오후 3:20 도착 예정"))
        assertEquals(Turn.RIGHT, p.turn)
        assertEquals(300, p.distM)
        assertEquals(12_400, p.remainM)
        assertEquals(18, p.remainMin)
        assertEquals("15:20", p.eta)
        assertTrue(p.useful)
    }

    @Test
    fun parsesHoursAndKilometers() {
        val p = NavTextParser.parse(listOf("1.2km 앞 왼쪽 고속도로 진입", "1시간 25분 남음"))
        assertEquals(Turn.SLIGHT_LEFT, p.turn)
        assertEquals(1200, p.distM)
        assertEquals(85, p.remainMin)
    }

    @Test
    fun restAreaDistanceIsNotTurnDistance() {
        val p = NavTextParser.parse(listOf("안성휴게소 12km", "500m 앞 좌회전"))
        assertEquals("안성휴게소 12km", p.aux)
        assertEquals(500, p.distM)
        assertEquals(Turn.LEFT, p.turn)
    }

    @Test
    fun plainStatusTextIsNotUseful() {
        val p = NavTextParser.parse(listOf("T맵", "길안내 중입니다"))
        assertFalse(p.useful)
    }

    @Test
    fun arrivalIsNotConfusedWithEta() {
        assertEquals(Turn.ARRIVE, NavTextParser.parse(listOf("목적지에 도착했습니다")).turn)
        assertEquals(Turn.NONE, NavTextParser.parse(listOf("오후 3:20 도착 예정")).turn)
        assertEquals(Turn.UTURN, NavTextParser.parse(listOf("200m 앞 유턴")).turn)
    }

    @Test
    fun geoBasics() {
        val a = LatLon(37.5665, 126.9780)
        val north = LatLon(37.5675, 126.9780)
        val east = LatLon(37.5665, 126.9790)
        assertEquals(111.0, Geo.distance(a, north), 2.0)
        assertEquals(0.0, Geo.bearing(a, north), 0.5)
        assertEquals(90.0, Geo.bearing(a, east), 0.5)
    }

    /** 북쪽으로 약 111m 간 뒤 동쪽으로 약 88m 가는 ㄱ자 경로 */
    private fun lRoute(): Route {
        val p0 = LatLon(37.5665, 126.9780)
        val p1 = LatLon(37.5675, 126.9780)
        val p2 = LatLon(37.5675, 126.9790)
        return Route(
            points = listOf(p0, p1, p2),
            steps = listOf(
                Step(0, Turn.STRAIGHT, "출발"),
                Step(1, Turn.RIGHT, "우회전"),
                Step(2, Turn.ARRIVE, "도착"),
            ),
            totalM = 199, totalSec = 166,
        )
    }

    @Test
    fun trackerFollowsRoute() {
        val t = RouteTracker(lRoute())
        val start = t.update(LatLon(37.5665, 126.9780))
        assertEquals(Turn.RIGHT, start.turn)
        assertEquals(111.0, start.distToNextM.toDouble(), 3.0)
        assertEquals(199.0, start.remainM.toDouble(), 5.0)
        assertEquals(0, start.bearing)
        assertFalse(start.arrived)

        val mid = t.update(LatLon(37.5670, 126.9780))
        assertEquals(Turn.RIGHT, mid.turn)
        assertEquals(55.0, mid.distToNextM.toDouble(), 3.0)

        val afterTurn = t.update(LatLon(37.5675, 126.9785))
        assertEquals(Turn.ARRIVE, afterTurn.turn)
        assertEquals(44.0, afterTurn.distToNextM.toDouble(), 3.0)
        assertEquals(90.0, afterTurn.bearing.toDouble(), 2.0)

        val end = t.update(LatLon(37.5675, 126.9790))
        assertTrue(end.arrived)
    }

    @Test
    fun trackerReportsOffRoute() {
        val t = RouteTracker(lRoute())
        val off = t.update(LatLon(37.5670, 126.9770)) // 서쪽으로 약 88m 벗어남
        assertTrue(off.offRouteM > 60)
    }

    @Test
    fun parsesTmapPedestrianResponse() {
        val json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[126.9780,37.5665]},
           "properties":{"totalDistance":199,"totalTime":166,"pointType":"SP","turnType":200,"description":"출발"}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[126.9780,37.5665],[126.9780,37.5675]]},
           "properties":{"distance":111,"time":92}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[126.9780,37.5675]},
           "properties":{"pointType":"GP","turnType":13,"description":"우회전 후 88m 이동"}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[126.9780,37.5675],[126.9790,37.5675]]},
           "properties":{"distance":88,"time":74}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[126.9790,37.5675]},
           "properties":{"pointType":"EP","turnType":201,"description":"도착"}}
        ]}
        """.trimIndent()
        val r = TmapParser.parsePedestrian(json)
        assertEquals(3, r.points.size)
        assertEquals(199, r.totalM)
        assertEquals(166, r.totalSec)
        assertEquals(listOf(0, 1, 2), r.steps.map { it.pointIndex })
        assertEquals(listOf(Turn.STRAIGHT, Turn.RIGHT, Turn.ARRIVE), r.steps.map { it.turn })
        assertEquals(Turn.RIGHT, RouteTracker(r).update(LatLon(37.5665, 126.9780)).turn)
    }
}
