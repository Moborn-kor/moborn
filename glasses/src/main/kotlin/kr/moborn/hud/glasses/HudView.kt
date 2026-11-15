package kr.moborn.hud.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import kr.moborn.hud.Fmt
import kr.moborn.hud.HudState
import kr.moborn.hud.Turn
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 안경 화면. 검은색은 투명하게 보이므로 배경은 검정, 내용은 밝은 선과 글자만 쓴다.
 * 위: 화살표와 다음 안내까지 거리. 아래: 속도, 남은 거리·시간, 목적지.
 */
class HudView(context: Context) : View(context) {

    var state: HudState = HudState()
        set(v) {
            field = v
            postInvalidate()
        }

    /** 연결 전 안내 문구. 비어 있으면 안내 화면을 그린다. */
    var message: String = "휴대폰 연결을 기다리는 중"
        set(v) {
            field = v
            postInvalidate()
        }

    /** 머리가 향한 방위(북쪽 0도). 센서가 없으면 null. */
    var headAzimuth: Float? = null
        set(v) {
            field = v
            if (state.useHead) postInvalidate()
        }

    private val fg = Color.rgb(64, 255, 128)
    private val dim = Color.rgb(40, 170, 90)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val u = min(w, h) / 100f // 화면 크기에 맞춘 단위

        if (message.isNotEmpty()) {
            drawCentered(c, "Moborn HUD", w / 2, h * 0.40f, 9 * u, fg)
            drawCentered(c, message, w / 2, h * 0.60f, 6 * u, dim)
            return
        }

        val s = state
        val hasGuide = s.turn != Turn.NONE || s.distM >= 0

        // --- 위쪽: 화살표 + 거리 ---
        val arrowSize = 30 * u
        val arrowCx = w * 0.30f
        val arrowCy = h * 0.36f
        if (hasGuide) {
            line.strokeWidth = 5.5f * u
            line.color = fg
            val head = headAzimuth
            if (s.mode == HudState.MODE_WALK && s.useHead && s.bearing >= 0 && head != null) {
                drawStraightArrow(c, arrowCx, arrowCy, arrowSize, s.bearing - head)
            } else {
                drawTurnArrow(c, arrowCx, arrowCy, arrowSize, s.turn)
            }
            text.textAlign = Paint.Align.LEFT
            if (s.distM >= 0) {
                text.color = fg
                text.textSize = 20 * u
                c.drawText(Fmt.dist(s.distM), w * 0.52f, h * 0.36f, text)
            }
            val label = s.instr.ifEmpty { s.turn.label }
            if (label.isNotEmpty()) {
                text.color = dim
                text.textSize = 7 * u
                c.drawText(ellipsize(label, w * 0.46f), w * 0.52f, h * 0.50f, text)
            }
        } else if (s.note.isNotEmpty()) {
            drawCentered(c, s.note, w / 2, h * 0.36f, 8 * u, dim)
        }

        // --- 가운데 줄: 보조 정보(휴게소·주유소) 또는 상태 문구 ---
        val mid = when {
            s.aux.isNotEmpty() -> s.aux
            hasGuide && s.note.isNotEmpty() -> s.note
            else -> ""
        }
        if (mid.isNotEmpty()) drawCentered(c, ellipsize(mid, w * 0.9f, 6.5f * u), w / 2, h * 0.66f, 6.5f * u, dim)

        // --- 아래쪽: 속도 | 남은 거리·시간 | 도착 시각 ---
        val baseY = h * 0.84f
        if (s.speedKmh >= 0) {
            text.textAlign = Paint.Align.LEFT
            text.color = fg
            text.textSize = 13 * u
            val v = s.speedKmh.toString()
            c.drawText(v, w * 0.04f, baseY, text)
            val vw = text.measureText(v)
            text.textSize = 5 * u
            text.color = dim
            c.drawText("km/h", w * 0.04f + vw + u, baseY, text)
        }
        val remain = listOf(Fmt.dist(s.remainM), Fmt.mins(s.remainMin)).filter { it.isNotEmpty() }.joinToString("  ")
        if (remain.isNotEmpty()) drawCentered(c, remain, w * 0.56f, baseY, 8 * u, fg)
        if (s.eta.isNotEmpty()) {
            text.textAlign = Paint.Align.RIGHT
            text.color = fg
            text.textSize = 8 * u
            c.drawText(s.eta, w * 0.96f, baseY, text)
        }

        // --- 맨 아래: 목적지 ---
        if (s.dest.isNotEmpty()) {
            drawCentered(c, ellipsize("→ ${s.dest}", w * 0.9f, 5.5f * u), w / 2, h * 0.96f, 5.5f * u, dim)
        }
    }

    /** 아래에서 올라와 회전 방향으로 꺾이는 화살표 */
    private fun drawTurnArrow(c: Canvas, cx: Float, cy: Float, size: Float, turn: Turn) {
        val half = size / 2
        when (turn) {
            Turn.ARRIVE -> {
                // 도착: 깃발
                c.drawLine(cx - half * 0.4f, cy + half, cx - half * 0.4f, cy - half, line)
                val flag = Path().apply {
                    moveTo(cx - half * 0.4f, cy - half)
                    lineTo(cx + half * 0.7f, cy - half * 0.55f)
                    lineTo(cx - half * 0.4f, cy - half * 0.1f)
                }
                c.drawPath(flag, line)
            }
            Turn.UTURN -> {
                val r = half * 0.45f
                val p = Path().apply {
                    moveTo(cx + r, cy + half)
                    lineTo(cx + r, cy - half + r)
                    arcTo(cx - r, cy - half, cx + r, cy - half + 2 * r, 0f, -180f, false)
                    lineTo(cx - r, cy + half * 0.2f)
                }
                c.drawPath(p, line)
                arrowHead(c, cx - r, cy + half * 0.2f, 180f, half * 0.5f)
            }
            Turn.NONE, Turn.STRAIGHT -> drawStraightArrow(c, cx, cy, size, 0f)
            else -> {
                // 줄기는 아래에서 가운데까지, 거기서 회전 각도로 꺾는다.
                val a = Math.toRadians(turn.angle.toDouble())
                val ex = cx + (half * sin(a)).toFloat()
                val ey = cy - (half * cos(a)).toFloat()
                val p = Path().apply {
                    moveTo(cx, cy + half)
                    lineTo(cx, cy)
                    lineTo(ex, ey)
                }
                c.drawPath(p, line)
                arrowHead(c, ex, ey, turn.angle.toFloat(), half * 0.5f)
            }
        }
    }

    /** 가운데를 중심으로 angle만큼 돌린 곧은 화살표. 0도가 위쪽. */
    private fun drawStraightArrow(c: Canvas, cx: Float, cy: Float, size: Float, angle: Float) {
        val half = size / 2
        val a = Math.toRadians(angle.toDouble())
        val dx = (half * sin(a)).toFloat()
        val dy = (half * cos(a)).toFloat()
        c.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, line)
        arrowHead(c, cx + dx, cy - dy, angle, half * 0.5f)
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, angle: Float, len: Float) {
        for (side in intArrayOf(-150, 150)) {
            val a = Math.toRadians((angle + side).toDouble())
            c.drawLine(x, y, x + (len * sin(a)).toFloat(), y - (len * cos(a)).toFloat(), line)
        }
    }

    private fun drawCentered(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size
        text.color = color
        c.drawText(s, x, y, text)
    }

    /** 폭을 넘으면 끝을 …로 줄인다. size를 주면 그 글자 크기로 잰다. */
    private fun ellipsize(s: String, maxWidth: Float, size: Float = text.textSize): String {
        val old = text.textSize
        text.textSize = size
        var out = s
        if (text.measureText(out) > maxWidth) {
            while (out.length > 1 && text.measureText("$out…") > maxWidth) out = out.dropLast(1)
            out = "$out…"
        }
        text.textSize = old
        return out
    }
}
