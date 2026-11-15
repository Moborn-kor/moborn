package kr.moborn.hud.phone

import kr.moborn.hud.HudState
import kr.moborn.hud.KakaoParser
import kr.moborn.hud.LatLon
import kr.moborn.hud.Route
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Poi(val name: String, val address: String, val pos: LatLon)

/**
 * 카카오 API 호출. REST API 키 하나로 장소 검색과 자동차·도보·대중교통 경로를 받는다.
 * 키는 사용자가 카카오 개발자 사이트에서 발급받아 앱에 입력한다.
 */
object Kakao {
    const val KEY_PAGE = "https://developers.kakao.com/console/app"

    /** 장소 검색. near가 있으면 가까운 순으로. */
    fun search(key: String, keyword: String, near: LatLon?): List<Poi> {
        var url = "https://dapi.kakao.com/v2/local/search/keyword.json?size=6&query=" +
            URLEncoder.encode(keyword, "UTF-8")
        if (near != null) url += "&x=${near.lon}&y=${near.lat}"
        val docs = JSONObject(get(url, key)).optJSONArray("documents") ?: return emptyList()
        val list = ArrayList<Poi>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val lon = d.optString("x").toDoubleOrNull() ?: continue
            val lat = d.optString("y").toDoubleOrNull() ?: continue
            val addr = d.optString("road_address_name").ifBlank { d.optString("address_name") }
            list += Poi(d.optString("place_name"), addr, LatLon(lat, lon))
        }
        return list
    }

    /** 이동 수단별 경로. travel은 HudState.MODE_CAR / MODE_WALK / MODE_TRANSIT. */
    fun route(key: String, travel: String, from: LatLon, to: LatLon): Route {
        val url = when (travel) {
            HudState.MODE_CAR ->
                "https://apis-navi.kakaomobility.com/v1/directions" +
                    "?origin=${from.lon},${from.lat}&destination=${to.lon},${to.lat}"
            HudState.MODE_TRANSIT ->
                "https://dapi.kakao.com/v2/routing/publictraffic" +
                    "?start_x=${from.lon}&start_y=${from.lat}&end_x=${to.lon}&end_y=${to.lat}"
            else ->
                "https://dapi.kakao.com/v2/routing/walk" +
                    "?start_x=${from.lon}&start_y=${from.lat}&end_x=${to.lon}&end_y=${to.lat}"
        }
        val body = get(url, key)
        // 응답 모양을 확인할 수 있게 앞부분을 기록에 남긴다.
        HudBus.log("카카오 응답(${travel}): " + body.take(500).replace("\n", " "))
        val route = KakaoParser.parse(body)
        if (route.points.size < 2) throw RuntimeException("응답에서 경로를 읽지 못했습니다")
        return route
    }

    private fun get(url: String, key: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("Authorization", "KakaoAK $key")
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("카카오 응답 $code: ${text.take(160)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
