package kr.moborn.hud.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import kr.moborn.hud.HudState
import kr.moborn.hud.LatLon
import kr.moborn.hud.RouteTracker
import kotlin.math.roundToInt

/** 화면이 꺼져도 계속 도는 본체: 안경 연결, 위치 수신, 안경으로 전송. */
class HudService : Service(), LocationListener {

    private lateinit var link: BluetoothLink
    @Volatile private var alive = false
    private var offRouteSince = 0L
    private var lastRerouteAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        running = true
        startAsForeground()
        link = BluetoothLink(this).also { it.start() }
        startLocation()
        Thread({ sendLoop() }, "hud-send").start()
        HudBus.log("서비스 시작")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        running = false
        link.stop()
        runCatching { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) }
        HudBus.log("서비스 중지")
        super.onDestroy()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "HUD 연결", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Moborn HUD 실행 중")
            .setContentText("안경으로 안내 정보를 보내고 있습니다")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = 0
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(1, n, type)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            HudBus.log("서비스 시작 실패: 권한을 먼저 허용해 주세요 (${e.javaClass.simpleName})")
            stopSelf()
        }
    }

    private fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            HudBus.log("위치 권한이 없어 속도와 도보 안내를 쓸 수 없습니다")
            return
        }
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
        } catch (e: Exception) {
            HudBus.log("위치 수신 실패: ${e.message}")
        }
    }

    override fun onLocationChanged(loc: Location) {
        val pos = LatLon(loc.latitude, loc.longitude)
        HudBus.position = pos
        HudBus.speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else -1

        val tracker = HudBus.tracker
        if (HudBus.mode == HudState.MODE_WALK && tracker != null) {
            val p = tracker.update(pos)
            HudBus.progress = p
            if (p.arrived) HudBus.note = "도착"
            checkReroute(p.offRouteM, pos)
        }
        HudBus.changed()
    }

    /** 경로에서 40m 넘게 10초 이상 벗어나면 다시 탐색한다. 20초에 한 번까지만. */
    private fun checkReroute(offM: Int, pos: LatLon) {
        val now = System.currentTimeMillis()
        if (offM <= 40) {
            offRouteSince = 0
            return
        }
        if (offRouteSince == 0L) offRouteSince = now
        if (now - offRouteSince < 10_000 || now - lastRerouteAt < 20_000) return
        lastRerouteAt = now
        offRouteSince = 0
        val dest = currentDest ?: return
        val key = Prefs.appKey(this)
        HudBus.note = "경로 재탐색 중"
        Thread {
            try {
                val route = Tmap.pedestrian(key, pos, dest)
                HudBus.tracker = RouteTracker(route)
                HudBus.note = ""
                HudBus.log("경로 재탐색 완료")
            } catch (e: Exception) {
                HudBus.note = ""
                HudBus.log("재탐색 실패: ${e.message}")
            }
        }.start()
    }

    /** 내용이 바뀌면 바로, 안 바뀌어도 2초마다 한 번 보낸다. */
    private fun sendLoop() {
        var last = ""
        var lastAt = 0L
        while (alive) {
            val line = HudBus.build().toJson()
            val now = System.currentTimeMillis()
            if (HudBus.connected && (line != last || now - lastAt > 2000)) {
                if (link.send(line)) {
                    last = line
                    lastAt = now
                }
            }
            runCatching { Thread.sleep(400) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object {
        const val ACTION_STOP = "kr.moborn.hud.STOP"
        private const val CHANNEL = "hud"
        @Volatile var running = false
        @Volatile var currentDest: Poi? = null
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("hud", Context.MODE_PRIVATE)
    fun appKey(c: Context): String = p(c).getString("tmapKey", "") ?: ""
    fun setAppKey(c: Context, v: String) = p(c).edit().putString("tmapKey", v.trim()).apply()
}
