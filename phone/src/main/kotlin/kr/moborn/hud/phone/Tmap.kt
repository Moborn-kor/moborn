package kr.moborn.hud.phone

import kr.moborn.hud.LatLon
import kr.moborn.hud.Route
import kr.moborn.hud.TmapParser
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Poi(val name: String, val address: String, val pos: LatLon)

/** 티맵 공개 API 호출. appKey는 사용자가 SK open API에서 발급받아 앱에 입력한다. */
object Tmap {
    private const val BASE = "https://apis.openapi.sk.com/tmap"

    /** 장소 검색. near가 있으면 그 주변을 우선한다. */
    fun search(appKey: String, keyword: String, near: LatLon?): List<Poi> {
        var url = "$BASE/pois?version=1&count=6&reqCoordType=WGS84GEO&resCoordType=WGS84GEO" +
            "&searchKeyword=" + URLEncoder.encode(keyword, "UTF-8")
        if (near != null) url += "&centerLat=${near.lat}&centerLon=${near.lon}&searchtypCd=R&radius=0"
        val body = request(url, appKey, null)
        if (body.isBlank()) return emptyList() // 결과 없음은 빈 응답으로 온다
        val arr = JSONObject(body).optJSONObject("searchPoiInfo")?.optJSONObject("pois")?.optJSONArray("poi")
            ?: return emptyList()
        val list = ArrayList<Poi>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val lat = p.optString("frontLat").toDoubleOrNull() ?: p.optString("noorLat").toDoubleOrNull() ?: continue
            val lon = p.optString("frontLon").toDoubleOrNull() ?: p.optString("noorLon").toDoubleOrNull() ?: continue
            val addr = listOf("upperAddrName", "middleAddrName", "lowerAddrName")
                .map { p.optString(it) }.filter { it.isNotBlank() }.joinToString(" ")
            list += Poi(p.optString("name"), addr, LatLon(lat, lon))
        }
        return list
    }

    /** 보행자 경로 */
    fun pedestrian(appKey: String, from: LatLon, to: Poi): Route {
        val json = JSONObject().apply {
            put("startX", from.lon.toString()); put("startY", from.lat.toString())
            put("endX", to.pos.lon.toString()); put("endY", to.pos.lat.toString())
            put("reqCoordType", "WGS84GEO"); put("resCoordType", "WGS84GEO")
            put("startName", URLEncoder.encode("출발", "UTF-8"))
            put("endName", URLEncoder.encode(to.name.ifBlank { "도착" }, "UTF-8"))
        }
        val body = request("$BASE/routes/pedestrian?version=1", appKey, json.toString())
        return TmapParser.parsePedestrian(body)
    }

    private fun request(url: String, appKey: String, postBody: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("appKey", appKey)
            c.setRequestProperty("Accept", "application/json")
            if (postBody != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("티맵 응답 $code: ${text.take(120)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
