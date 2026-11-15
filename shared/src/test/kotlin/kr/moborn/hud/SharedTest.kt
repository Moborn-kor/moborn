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
    fun aheadPathIsHeadingUp() {
        val t = RouteTracker(lRoute())
        val start = LatLon(37.5665, 126.9780)
        t.update(start)
        val path = t.ahead(start, 300)
        // 원점에서 시작, 북쪽 111m 지점은 (0, +111), 그다음 동쪽으로 꺾이면 x가 +88
        assertEquals(listOf(0, 0), path.take(2))
        assertEquals(0.0, path[2].toDouble(), 2.0)
        assertEquals(111.0, path[3].toDouble(), 3.0)
        assertEquals(88.0, path[4].toDouble(), 3.0)
        assertEquals(111.0, path[5].toDouble(), 3.0)

        // 꺾은 뒤에는 동쪽이 진행 방향이므로 남은 경로가 위쪽(+y)으로 보인다
        val later = LatLon(37.5675, 126.9785)
        t.update(later)
        val p2 = t.ahead(later, 300)
        assertEquals(0.0, p2[2].toDouble(), 2.0)
        assertEquals(44.0, p2[3].toDouble(), 3.0)
    }

    @Test
    fun stateCarriesMiniMapPath() {
        val s = HudState(mode = HudState.MODE_WALK, path = listOf(0, 0, 0, 111, 88, 111), pathM = 150)
        assertEquals(s, HudState.fromJson(s.toJson()))
    }

    /** 카카오모빌리티 자동차 길찾기 응답 모양 */
    @Test
    fun parsesKakaoCarResponse() {
        val json = """
        {"trans_id":"x","routes":[{"result_code":0,"result_msg":"길찾기 성공",
          "summary":{"distance":199,"duration":60},
          "sections":[{"distance":199,"duration":60,
            "roads":[
              {"name":"세종대로","distance":111,"vertexes":[126.9780,37.5665,126.9780,37.5675]},
              {"name":"종로","distance":88,"vertexes":[126.9780,37.5675,126.9790,37.5675]}],
            "guides":[
              {"name":"출발지","x":126.9780,"y":37.5665,"distance":0,"type":100,"guidance":"출발지","road_index":0},
              {"name":"","x":126.9780,"y":37.5675,"distance":111,"type":2,"guidance":"우회전","road_index":1},
              {"name":"목적지","x":126.9790,"y":37.5675,"distance":88,"type":101,"guidance":"목적지","road_index":-1}]
          }]}]}
        """.trimIndent()
        val r = KakaoParser.parse(json)
        assertEquals(3, r.points.size)
        assertEquals(199, r.totalM)
        assertEquals(60, r.totalSec)
        assertEquals(listOf(0, 1, 2), r.steps.map { it.pointIndex })
        assertEquals(listOf(Turn.STRAIGHT, Turn.RIGHT, Turn.ARRIVE), r.steps.map { it.turn })
        assertEquals(Turn.RIGHT, RouteTracker(r).update(LatLon(37.5665, 126.9780)).turn)
    }

    /** 도보: 구간별 path.points 와 guidance 로 오는 모양 (좌표쌍 배열, 위도·경도 순서도 섞어서) */
    @Test
    fun parsesNestedStepsWithPathPoints() {
        val json = """
        {"route":{"properties":{"distance":199,"duration":166},
          "legs":[{"steps":[
            {"properties":{"guidance":"세종대로 방면으로 111m 이동","distance":111},
             "path":{"points":[[126.9780,37.5665],[126.9780,37.5675]]}},
            {"properties":{"guidance":"우회전 후 88m 이동","distance":88},
             "path":{"points":[[37.5675,126.9780],[37.5675,126.9790]]}},
            {"properties":{"guidance":"목적지 도착","distance":0}}
          ]}]}}
        """.trimIndent()
        val r = KakaoParser.parse(json)
        assertEquals(3, r.points.size)
        assertEquals(199, r.totalM)
        assertEquals(166, r.totalSec)
        assertEquals(listOf(Turn.STRAIGHT, Turn.RIGHT, Turn.ARRIVE), r.steps.map { it.turn })
        assertEquals(listOf(0, 1, 2), r.steps.map { it.pointIndex })
    }

    /** 대중교통: 구간 종류(type)와 정류장 목록이 섞여 와도 경로 선과 안내만 뽑는다 */
    @Test
    fun parsesTransitStepsAndIgnoresStops() {
        val json = """
        {"bus":1,"subway":0,"routes":[{"type":"BUS","distance":900,"duration":600,"transfers":0,
          "steps":[
            {"type":"WALKING","guidance":"시청앞 정류장까지 걷기","path":{"points":"126.9780,37.5665 126.9780,37.5675"}},
            {"type":"BUS","guidance":"472번 승차, 3개 정류장 이동",
             "stops":[{"name":"시청앞","x":126.9780,"y":37.5675},{"name":"종로1가","x":126.9790,"y":37.5675}],
             "path":{"points":"126.9780,37.5675 126.9790,37.5675"}}
          ]}]}
        """.trimIndent()
        val r = KakaoParser.parse(json)
        assertEquals(3, r.points.size)
        assertEquals(900, r.totalM)
        assertEquals(2, r.steps.size)
        assertEquals("버스 · 472번 승차, 3개 정류장 이동", r.steps[1].text)
        assertEquals(1, r.steps[1].pointIndex)
    }

    @Test
    fun unknownCoordinatesGiveEmptyRoute() {
        val r = KakaoParser.parse("""{"routes":[{"path":{"points":[[523953,1084098],[523960,1084100]]}}]}""")
        assertTrue(r.points.isEmpty())
    }
}
