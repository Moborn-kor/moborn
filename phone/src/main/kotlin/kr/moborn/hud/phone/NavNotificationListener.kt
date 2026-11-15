package kr.moborn.hud.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kr.moborn.hud.NavTextParser

/** 내비 앱의 길안내 알림을 읽어 회전 방향과 거리를 뽑는다. */
class NavNotificationListener : NotificationListenerService() {

    private var lastDump = ""

    override fun onListenerConnected() {
        HudBus.log("알림 읽기 준비됨")
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in NAV_APPS && sbn.packageName == HudBus.navApp) {
            HudBus.nav = null
            HudBus.navApp = ""
            HudBus.changed()
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val name = NAV_APPS[sbn.packageName] ?: return
        val e = sbn.notification.extras
        val texts = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_TITLE_BIG,
        ).mapNotNull { e.getCharSequence(it)?.toString() }.filter { it.isNotBlank() }.distinct()

        // 알림에 실제로 어떤 문구가 오는지 기록한다. 같은 내용은 한 번만.
        val dump = "$name 알림: " + texts.joinToString(" | ")
        if (dump != lastDump) {
            lastDump = dump
            HudBus.log(dump)
        }

        val parsed = NavTextParser.parse(texts)
        if (parsed.useful) {
            HudBus.nav = parsed
            HudBus.navApp = sbn.packageName
            HudBus.changed()
        }
    }

    companion object {
        val NAV_APPS = mapOf(
            "com.skt.tmap.ku" to "티맵",
            "com.skt.skaf.l001mtm091" to "티맵",
            "com.nhn.android.nmap" to "네이버지도",
            "com.locnall.KimGiSa" to "카카오내비",
            "net.daum.android.map" to "카카오맵",
            "com.google.android.apps.maps" to "구글지도",
        )
    }
}
