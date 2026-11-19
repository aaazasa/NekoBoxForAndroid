package io.nekohasekai.sagernet.telemetry

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 自更新。
 *
 * 现实约束（别指望绕过去）
 *  - Android 8+ 起，应用不能静默安装自己。下载完必须由用户点系统弹窗里的「安装」。
 *  - Android 10+ 起，后台服务不能用 startActivity 拉起安装界面，
 *    所以这里区分两条路：
 *      · 用户在 App 里  → 弹对话框 → 下载 → 直接拉安装
 *      · 在后台         → 发通知，用户点通知再走上面流程
 *
 * 换个说法：这套东西省掉的是「手动找 APK、传文件、点安装包」，
 * 省不掉最后那一次系统确认。
 */
object SelfUpdate {

    private const val TAG = "TlmUpdate"
    private const val APK_NAME = "tlm-update.apk"

    /** 用户在 App 里时调用：有新版本就弹框。返回 true 表示已弹。 */
    fun promptIfNewer(a: Activity, currentCode: Int): Boolean {
        val v = RemoteConfig.versionCode
        if (v <= currentCode) return false

        AlertDialog.Builder(a)
            .setTitle("发现新版本 ${RemoteConfig.versionName}")
            .setMessage(
                (if (RemoteConfig.changelog.isNotBlank())
                    RemoteConfig.changelog + "\n\n" else "") +
                    "当前 $currentCode → 新版 $v\n\n" +
                    "点「下载安装」后会下载约 13 MB，下载完系统会弹一次安装确认。")
            .setPositiveButton("下载安装") { _, _ -> download(a) }
            .setNegativeButton("稍后", null)
            .show()
        return true
    }

    @Volatile private var lastCheckAt = 0L

    /**
     * 打开 App 时主动检查。
     *
     * 之前是启动后死等 3 秒再读 RemoteConfig.versionCode —— 首次装完 TLS 握手都没完，
     * 读到 0 就当成「已是最新」，于是永远不弹。这里改成：自己拉配置，
     * 拿到数就判断，拉失败再试，最多三轮；60 秒内不重复查。
     */
    fun checkNow(a: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < 60_000L) return
        lastCheckAt = now

        Thread {
            var server = 0
            for (i in 0 until 3) {
                runCatching { RemoteConfig.fetch(a) }
                server = RemoteConfig.versionCode
                if (server > 0) break
                runCatching { Thread.sleep(1_500L) }
            }
            val cur = runCatching {
                val pi = a.packageManager.getPackageInfo(a.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
                else @Suppress("DEPRECATION") pi.versionCode
            }.getOrDefault(0)
            Log.i(TAG, "check: server=$server current=$cur")
            if (server <= cur) return@Thread
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                runCatching { promptIfNewer(a, cur) }
                    .onFailure { Log.w(TAG, "prompt: ${it.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 从后台发现新版本时用：发通知，别硬拉 Activity。 */
    fun notifyNewer(ctx: Context, currentCode: Int) {
        if (RemoteConfig.versionCode <= currentCode) return
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val ch = "tlm_update"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(ch, "更新",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, Class.forName("io.nekohasekai.sagernet.ui.MainActivity")),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(ctx, ch)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            nm.notify(7102, b.setContentTitle("有新版本可更新")
                .setContentText("点开应用自动提示安装")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        }.onFailure { Log.w(TAG, "notify: ${it.message}") }
    }

    // ---------------------------------------------------------------- 下载 + 安装
    private fun download(a: Activity) {
        val base = "https://43-108-107-175.sslip.io:8080"
        val url = if (RemoteConfig.apkUrl.startsWith("http"))
            RemoteConfig.apkUrl else base + RemoteConfig.apkUrl

        val target = File(a.cacheDir, APK_NAME)
        runCatching { if (target.exists()) target.delete() }

        try {
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("正在下载更新")
                setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            val id = dm.enqueue(req)
            Toast.makeText(a, "开始下载，完成后会自动提示安装", Toast.LENGTH_LONG).show()
            waitThenInstall(a, dm, id)
        } catch (t: Throwable) {
            Log.w(TAG, "download: ${t.message}")
            Toast.makeText(a, "下载失败: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun waitThenInstall(a: Activity, dm: DownloadManager, id: Long) {
        Thread {
            var tries = 0
            while (tries++ < 120) {
                Thread.sleep(2000)
                val q = DownloadManager.Query().setFilterById(id)
                val c = dm.query(q) ?: continue
                c.use {
                    if (!it.moveToFirst()) return@use
                    val status = it.getInt(it.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val path = it.getString(it.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_LOCAL_URI))
                        a.runOnUiThread { install(a, path) }
                        return@Thread
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        a.runOnUiThread {
                            Toast.makeText(a, "下载失败", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun install(a: Activity, localUri: String?) {
        try {
            val file = if (localUri != null && localUri.startsWith("file://"))
                File(Uri.parse(localUri).path!!) else null

            val uri = if (file != null && file.exists()) {
                FileProvider.getUriForFile(a, a.packageName + ".cache", file)
            } else if (localUri != null) {
                Uri.parse(localUri)
            } else return

            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            a.startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "install: ${t.message}")
            Toast.makeText(a, "拉起安装失败，请检查「安装未知应用」权限",
                Toast.LENGTH_LONG).show()
        }
    }
}
