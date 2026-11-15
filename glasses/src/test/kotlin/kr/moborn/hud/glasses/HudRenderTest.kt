package kr.moborn.hud.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.robolectric.RuntimeEnvironment
import kr.moborn.hud.HudState
import kr.moborn.hud.Turn
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 안경 없이 HUD 화면을 PNG로 그려 build/hud-preview/ 에 저장한다.
 * 배치를 눈으로 확인하기 위한 것이고, 그림이 비어 있지 않은지만 검사한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudRenderTest {
    private val w = 480
    private val h = 398

    private fun render(name: String, state: HudState?, message: String = "") {
        val view = HudView(RuntimeEnvironment.getApplication())
        if (state != null) view.state = state
        view.message = message
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        var lit = 0
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            if (bmp.getPixel(x, y) and 0x00FFFFFF != 0) lit++
        }
        assertTrue("$name: 아무것도 그려지지 않음", lit > 50)

        val dir = File("build/hud-preview").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderScenes() {
        render("0-waiting", null, "휴대폰 연결을 기다리는 중")
        render("1-car-right-map", HudState(HudState.MODE_CAR, Turn.RIGHT, 300, "우회전", speedKmh = 52,
            remainM = 41_100, remainMin = 37, eta = "15:20", dest = "서울역", aux = "주유소 2.1km",
            path = listOf(0, 0, 0, 300, 200, 310, 260, 420), pathM = 500))
        render("2-car-straight", HudState(HudState.MODE_CAR, Turn.STRAIGHT, 1200, "직진", speedKmh = 78,
            remainM = 42_300, remainMin = 98, eta = "15:20", dest = "서울역", aux = "안성휴게소 12km"))
        render("3-car-uturn", HudState(HudState.MODE_CAR, Turn.UTURN, 150, "유턴", speedKmh = 31,
            remainM = 2_400, remainMin = 6, eta = "15:21"))
        render("4-walk-left-map", HudState(HudState.MODE_WALK, Turn.LEFT, 45, "좌회전 후 120m 이동",
            remainM = 620, remainMin = 9, eta = "15:31", dest = "시청역 2번 출구",
            path = listOf(0, 0, 0, 45, -120, 48, -125, 110), pathM = 150))
        render("5-transit-map", HudState(HudState.MODE_TRANSIT, Turn.STRAIGHT, 180,
            "버스 · 472번 승차, 3개 정류장 이동", remainM = 4_200, remainMin = 21, eta = "15:43",
            dest = "강남역", path = listOf(0, 0, 10, 120, 90, 240, 95, 300), pathM = 300))
        render("6-arrive", HudState(HudState.MODE_WALK, Turn.ARRIVE, 10, "도착", remainM = 10, remainMin = 1,
            dest = "시청역 2번 출구"))
        render("7-idle-note", HudState(HudState.MODE_CAR, speedKmh = 0, note = "내비 안내 대기 중"))
    }
}
