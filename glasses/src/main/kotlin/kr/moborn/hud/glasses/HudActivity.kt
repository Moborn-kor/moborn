package kr.moborn.hud.glasses

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import kr.moborn.hud.HudState
import kr.moborn.hud.Protocol
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 안경 쪽 앱. 조작이 필요 없도록, 켜면 페어링된 기기들에 차례로 접속을 시도해
 * 휴대폰의 Moborn HUD를 찾고, 받은 내용을 화면에 그린다.
 */
class HudActivity : Activity(), SensorEventListener {

    private lateinit var view: HudView
    @Volatile private var alive = false
    @Volatile private var socket: BluetoothSocket? = null
    private var worker: Thread? = null

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = HudView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        alive = true
        worker = Thread({ connectLoop() }, "hud-bt-client").also { it.start() }
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        alive = false
        runCatching { socket?.close() }
        worker?.interrupt()
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        super.onPause()
    }

    private fun prefs() = getSharedPreferences("hud", Context.MODE_PRIVATE)

    private fun connectLoop() {
        while (alive) {
            try {
                val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null || !adapter.isEnabled) {
                    view.message = "블루투스가 꺼져 있습니다"
                    Thread.sleep(3000)
                    continue
                }
                val bonded = adapter.bondedDevices?.toList().orEmpty()
                if (bonded.isEmpty()) {
                    view.message = "페어링된 휴대폰이 없습니다"
                    Thread.sleep(3000)
                    continue
                }
                // 지난번에 성공한 기기를 먼저 시도한다.
                val last = prefs().getString("last", null)
                val ordered = bonded.sortedByDescending { it.address == last }
                var connected = false
                for (d in ordered) {
                    if (!alive) return
                    view.message = "휴대폰 찾는 중: ${nameOf(d)}"
                    if (session(d)) {
                        connected = true
                        break
                    }
                }
                if (!connected) {
                    view.message = "휴대폰에서 Moborn HUD의 시작을 눌러 주세요"
                    Thread.sleep(2500)
                } else {
                    view.message = "연결이 끊겼습니다. 다시 연결하는 중"
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: SecurityException) {
                view.message = "블루투스 권한이 없습니다"
                runCatching { Thread.sleep(3000) }
            } catch (e: Exception) {
                runCatching { Thread.sleep(2000) }
            }
        }
    }

    /** 한 기기에 접속해 끊길 때까지 내용을 받는다. 접속에 성공했으면 true. */
    private fun session(device: BluetoothDevice): Boolean {
        val s = try {
            device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID).also { it.connect() }
        } catch (e: Exception) {
            return false
        }
        socket = s
        prefs().edit().putString("last", device.address).apply()
        view.message = "연결됨. 안내를 기다리는 중"
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            while (alive) {
                val line = reader.readLine() ?: break
                val state = HudState.fromJson(line) ?: continue
                view.message = ""
                view.state = state
            }
        } catch (e: Exception) {
            // 끊김
        } finally {
            runCatching { s.close() }
            socket = null
        }
        return true
    }

    private fun nameOf(d: BluetoothDevice): String = try {
        d.name ?: d.address
    } catch (e: SecurityException) {
        d.address
    }

    // 머리 방향: 안경을 쓴 채 앞을 볼 때의 방위를 구한다.
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orient)
        view.headAzimuth = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
