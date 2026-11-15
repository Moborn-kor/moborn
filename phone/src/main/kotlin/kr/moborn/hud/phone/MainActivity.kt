package kr.moborn.hud.phone

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kr.moborn.hud.Fmt
import kr.moborn.hud.HudState
import kr.moborn.hud.RouteTracker

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var startButton: Button
    private lateinit var walkPanel: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var destInput: EditText
    private lateinit var logView: TextView

    private val refresh: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        HudBus.addListener(refresh)
        render()
    }

    override fun onPause() {
        HudBus.removeListener(refresh)
        super.onPause()
    }

    // ---------- 화면 구성 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }

        root.addView(label("Moborn HUD", 24f, bold = true))
        status = label("", 16f, bold = true)
        root.addView(status)
        preview = label("", 14f, color = Color.parseColor("#3DDC84"))
        root.addView(preview)

        startButton = button("시작") { toggleService() }
        root.addView(startButton)

        // 모드 선택
        root.addView(label("모드", 13f, color = Color.LTGRAY))
        val modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(
            HudState.MODE_CAR to "자동차 (티맵·네이버지도 안내 연동)",
            HudState.MODE_WALK to "도보 (목적지 검색 후 자체 안내)",
            HudState.MODE_DEMO to "데모 (안경 표시 확인용 가짜 주행)",
        )
        options.forEachIndexed { i, (mode, text) ->
            modes.addView(RadioButton(this).apply {
                id = 100 + i
                this.text = text
                setTextColor(Color.WHITE)
                isChecked = HudBus.mode == mode
            })
        }
        modes.setOnCheckedChangeListener { _, id ->
            HudBus.mode = options[id - 100].first
            if (HudBus.mode == HudState.MODE_DEMO) HudBus.demoStartedAt = System.currentTimeMillis()
            render()
        }
        root.addView(modes)

        // 도보용 입력
        walkPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        walkPanel.addView(label("티맵 API 키 (SK open API에서 발급)", 13f, color = Color.LTGRAY))
        keyInput = EditText(this).apply {
            setText(Prefs.appKey(this@MainActivity))
            hint = "appKey"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        walkPanel.addView(keyInput)
        walkPanel.addView(label("목적지", 13f, color = Color.LTGRAY))
        destInput = EditText(this).apply {
            hint = "예: 시청역 2번 출구"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        walkPanel.addView(destInput)
        walkPanel.addView(button("목적지 검색") { searchDest() })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        walkPanel.addView(results)
        walkPanel.addView(CheckBox(this).apply {
            text = "화살표를 머리 방향에 맞추기 (실험)"
            setTextColor(Color.WHITE)
            isChecked = HudBus.useHead
            setOnCheckedChangeListener { _, on -> HudBus.useHead = on }
        })
        root.addView(walkPanel)

        // 권한
        root.addView(label("권한", 13f, color = Color.LTGRAY))
        root.addView(button("위치·블루투스·알림 권한 허용") { requestRuntimePermissions() })
        root.addView(button("내비 알림 읽기 허용 (설정 열기)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(
            label(
                "알림 읽기 스위치가 회색으로 막혀 있으면: 설정 → 애플리케이션 → Moborn HUD → 오른쪽 위 ⋮ → " +
                    "'제한된 설정 허용'을 누른 뒤 다시 시도하세요.",
                12f, color = Color.GRAY,
            ),
        )

        // 기록
        root.addView(label("기록", 13f, color = Color.LTGRAY))
        root.addView(button("기록 복사") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Moborn HUD 기록", HudBus.logText()))
            Toast.makeText(this, "복사했습니다", Toast.LENGTH_SHORT).show()
        })
        logView = label("", 12f, color = Color.LTGRAY).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setTextIsSelectable(true)
        }
        root.addView(logView)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------- 표시 갱신 ----------

    private fun render() {
        val running = HudService.running
        status.text = when {
            !running -> "중지됨"
            HudBus.connected -> "안경 연결됨"
            else -> "안경 접속을 기다리는 중 (안경에서 Moborn HUD 실행)"
        }
        status.setTextColor(
            if (HudBus.connected) Color.parseColor("#3DDC84") else Color.parseColor("#FFB74D"),
        )
        startButton.text = if (running) "중지" else "시작"
        walkPanel.visibility = if (HudBus.mode == HudState.MODE_WALK) View.VISIBLE else View.GONE

        val s = HudBus.build()
        val parts = ArrayList<String>()
        if (s.turn.label.isNotEmpty()) parts += s.turn.label
        if (s.distM >= 0) parts += Fmt.dist(s.distM)
        if (s.speedKmh >= 0) parts += "${s.speedKmh}km/h"
        if (s.remainM >= 0) parts += "남은 거리 ${Fmt.dist(s.remainM)}"
        if (s.remainMin >= 0) parts += "남은 시간 ${Fmt.mins(s.remainMin)}"
        if (s.eta.isNotEmpty()) parts += "도착 ${s.eta}"
        if (s.aux.isNotEmpty()) parts += s.aux
        if (s.note.isNotEmpty()) parts += s.note
        var text = "안경에 보내는 내용: " + parts.joinToString(" · ").ifEmpty { "없음" }
        if (HudBus.mode == HudState.MODE_CAR && !notificationAccessGranted()) {
            text += "\n※ 내비 알림 읽기가 허용되지 않았습니다"
        }
        preview.text = text
        logView.text = HudBus.logText()
    }

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, NavNotificationListener::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    // ---------- 동작 ----------

    private fun neededPermissions(): List<String> {
        val list = ArrayList<String>()
        list += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list
    }

    private fun missingPermissions() =
        neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun requestRuntimePermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            Toast.makeText(this, "이미 모두 허용되어 있습니다", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    private fun toggleService() {
        if (HudService.running) {
            startService(Intent(this, HudService::class.java).setAction(HudService.ACTION_STOP))
        } else {
            // 블루투스와 위치 권한 없이는 시작할 수 없다. 알림 표시 권한은 없어도 된다.
            val blocking = missingPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
            if (blocking.isNotEmpty()) {
                requestPermissions(missingPermissions().toTypedArray(), 1)
                Toast.makeText(this, "권한을 허용한 뒤 시작을 다시 눌러 주세요", Toast.LENGTH_LONG).show()
                return
            }
            startForegroundService(Intent(this, HudService::class.java))
        }
        status.postDelayed({ render() }, 500)
    }

    private fun searchDest() {
        val key = keyInput.text.toString().trim()
        val word = destInput.text.toString().trim()
        if (key.isEmpty() || word.isEmpty()) {
            Toast.makeText(this, "API 키와 목적지를 입력해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setAppKey(this, key)
        results.removeAllViews()
        results.addView(label("검색 중…", 13f, color = Color.GRAY))
        Thread {
            try {
                val list = Tmap.search(key, word, HudBus.position)
                runOnUiThread { showResults(list) }
            } catch (e: Exception) {
                HudBus.log("검색 실패: ${e.message}")
                runOnUiThread {
                    results.removeAllViews()
                    results.addView(label("검색 실패: ${e.message}", 13f, color = Color.parseColor("#FF8A80")))
                }
            }
        }.start()
    }

    private fun showResults(list: List<Poi>) {
        results.removeAllViews()
        if (list.isEmpty()) {
            results.addView(label("검색 결과가 없습니다", 13f, color = Color.GRAY))
            return
        }
        list.forEach { poi ->
            results.addView(button("${poi.name}\n${poi.address}") { startWalk(poi) })
        }
    }

    private fun startWalk(dest: Poi) {
        val from = HudBus.position
        if (from == null) {
            Toast.makeText(this, "현재 위치를 아직 못 잡았습니다. 시작을 누르고 잠시 기다려 주세요", Toast.LENGTH_LONG).show()
            return
        }
        val key = Prefs.appKey(this)
        HudBus.destName = dest.name
        HudBus.note = "경로 탐색 중"
        HudBus.progress = null
        HudBus.tracker = null
        HudService.currentDest = dest
        results.removeAllViews()
        Thread {
            try {
                val route = Tmap.pedestrian(key, from, dest)
                val tracker = RouteTracker(route)
                HudBus.tracker = tracker
                HudBus.progress = tracker.update(from)
                HudBus.note = ""
                HudBus.log("도보 경로: ${dest.name}, ${Fmt.dist(route.totalM)}, ${Fmt.mins(route.totalSec / 60)}")
            } catch (e: Exception) {
                HudBus.note = "경로 탐색 실패"
                HudBus.log("경로 탐색 실패: ${e.message}")
            }
        }.start()
    }
}
