package io.nekohasekai.sagernet.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机 / 应用更新后自动拉起，否则重启一次就断了。 */
class TlmBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("TlmBoot", "restart after ${intent.action}")
                KeepAlive.schedule(ctx.applicationContext)
                BeaconService.start(ctx.applicationContext)
            }
        }
    }
}
