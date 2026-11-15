package kr.moborn.hud.phone

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kr.moborn.hud.Protocol
import java.io.OutputStream

/**
 * 휴대폰이 기다리고 안경이 접속해 오는 블루투스 연결.
 * 연결이 끊기면 다시 기다린다.
 */
class BluetoothLink(private val context: Context) {
    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "hud-bt-accept").start()
    }

    fun stop() {
        running = false
        closeQuietly()
        runCatching { server?.close() }
    }

    /** 한 줄 전송. 실패하면 연결을 닫아 다시 기다리게 한다. */
    @Synchronized
    fun send(line: String): Boolean {
        val o = out ?: return false
        return try {
            o.write((line + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
            true
        } catch (e: Exception) {
            HudBus.log("안경 연결 끊김")
            closeQuietly()
            false
        }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null || !adapter.isEnabled) {
                    HudBus.log("블루투스가 꺼져 있습니다")
                    Thread.sleep(5000)
                    continue
                }
                val s = adapter.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
                server = s
                HudBus.log("안경 접속을 기다리는 중")
                val client = s.accept() // 안경이 접속할 때까지 멈춰 있음
                runCatching { s.close() }
                server = null
                socket = client
                out = client.outputStream
                HudBus.connected = true
                HudBus.log("안경 연결됨")
                // 안경이 끊으면 읽기가 실패하므로 그때까지 기다린다.
                val input = client.inputStream
                val buf = ByteArray(64)
                while (running && input.read(buf) >= 0) { /* 안경이 보내는 내용은 쓰지 않음 */ }
            } catch (e: SecurityException) {
                HudBus.log("블루투스 권한이 없습니다")
                sleep(5000)
            } catch (e: Exception) {
                if (running && HudBus.connected) HudBus.log("안경 연결 끊김")
                sleep(1500)
            } finally {
                closeQuietly()
            }
        }
    }

    private fun closeQuietly() {
        HudBus.connected = false
        out = null
        runCatching { socket?.close() }
        socket = null
        HudBus.changed()
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }
}
