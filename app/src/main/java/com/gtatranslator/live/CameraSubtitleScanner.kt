package com.gtatranslator.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Lightweight native Camera2 preview + TV subtitle region grabber.
 * Includes a fast frame-difference check so Auto-Scan only triggers Gemini
 * when the English subtitle on the TV screen actually changes.
 */
class CameraSubtitleScanner(
    private val context: Context,
    private val textureView: TextureView,
    private val onStatus: (String) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var previewSize: Size = Size(1280, 720)
    private var lastFingerprint: IntArray? = null
    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true
        startBackgroundThread()

        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (isStarted) openCamera()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    fun stop() {
        isStarted = false
        lastFingerprint = null
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        stopBackgroundThread()
    }

    fun resetChangeDetector() {
        lastFingerprint = null
    }

    /**
     * Must be called on Main/UI thread because TextureView.getBitmap accesses UI surface.
     * Returns compressed JPEG bytes, or null if frame is unavailable or unchanged (when requireChange=true).
     */
    fun captureSubtitleJpeg(cropBottomOnly: Boolean, requireChange: Boolean): ByteArray? {
        if (!textureView.isAvailable) return null
        val fullBmp = textureView.getBitmap(640, 360) ?: return null
        val targetBmp = if (cropBottomOnly) {
            val startY = (fullBmp.height * 0.52f).toInt().coerceIn(0, fullBmp.height - 40)
            val cropH = (fullBmp.height - startY).coerceAtLeast(40)
            val cropped = Bitmap.createBitmap(fullBmp, 0, startY, fullBmp.width, cropH)
            fullBmp.recycle()
            cropped
        } else {
            fullBmp
        }

        val fp = computeFingerprint(targetBmp)
        if (requireChange && lastFingerprint != null) {
            val diff = meanDifference(fp, lastFingerprint!!)
            if (diff < 12.0) {
                targetBmp.recycle()
                return null
            }
        }
        lastFingerprint = fp

        val out = ByteArrayOutputStream()
        targetBmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        targetBmp.recycle()
        return out.toByteArray()
    }

    private fun computeFingerprint(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, 24, 12, true)
        val pixels = IntArray(24 * 12)
        small.getPixels(pixels, 0, 24, 0, 0, 24, 12)
        small.recycle()
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 30 + g * 59 + b * 11) / 100
        }
    }

    private fun meanDifference(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return 100.0
        var sum = 0L
        for (i in a.indices) {
            sum += abs(a[i] - b[i])
        }
        return sum.toDouble() / a.size
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("ViceCameraThread").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        try {
            bgThread?.join(400)
        } catch (_: Exception) {
        }
        bgThread = null
        bgHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val backId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()

            if (backId == null) {
                onStatus("Kamera nebola nájdená.")
                return
            }

            val chars = manager.getCameraCharacteristics(backId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            if (!sizes.isNullOrEmpty()) {
                previewSize = sizes
                    .filter { it.width <= 1280 && it.height <= 960 }
                    .maxByOrNull { it.width * it.height }
                    ?: sizes.first()
            }

            manager.openCamera(backId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isStarted) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onStatus("Chyba kamery ($error).")
                }
            }, bgHandler)
        } catch (e: Exception) {
            onStatus("Nepodarilo sa spustiť kameru: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createPreviewSession() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isStarted || cameraDevice == null) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, bgHandler)
                            onStatus("Kamera pripravená • Namier na anglické titulky v TV")
                        } catch (_: Exception) {
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onStatus("Nepodarilo sa spustiť náhľad kamery.")
                    }
                },
                bgHandler
            )
        } catch (e: Exception) {
            onStatus("Chyba náhľadu kamery: ${e.message}")
        }
    }
}
