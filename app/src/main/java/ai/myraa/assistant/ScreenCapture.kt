package ai.myraa.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Screen frames as small JPEGs.
 * Android 11+: uses the Accessibility screenshot API (no extra prompt).
 * Android 8-10 (or fallback): uses MediaProjection after the user allows screen capture once.
 */
object ScreenCapture {
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var pw = 0
    private var ph = 0
    @Volatile private var lastJpeg: ByteArray? = null

    @Suppress("DEPRECATION")
    fun startProjection(ctx: Context, mp: MediaProjection) {
        stop()
        projection = mp
        val dm = DisplayMetrics()
        ctx.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        pw = dm.widthPixels / 2; ph = dm.heightPixels / 2
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, Handler(Looper.getMainLooper()))
        val r = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = mp.createVirtualDisplay("myraa", pw, ph, dm.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    fun stop() {
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        vd = null; reader = null; projection = null
    }

    fun grabJpeg(): ByteArray? {
        val acc = MyraaAccessibilityService.inst
        if (Build.VERSION.SDK_INT >= 30 && acc != null) {
            var out: ByteArray? = null
            val l = CountDownLatch(1)
            try {
                acc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = r.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                            out = bmp?.copy(Bitmap.Config.ARGB_8888, false)?.let { toJpeg(it) }
                            hb.close()
                        } catch (_: Exception) {}
                        l.countDown()
                    }
                    override fun onFailure(errorCode: Int) { l.countDown() }
                })
                l.await(2, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            if (out != null) { lastJpeg = out; return out }
        }
        return grabProjection() ?: lastJpeg
    }

    private fun grabProjection(): ByteArray? {
        val img = reader?.acquireLatestImage() ?: return lastJpeg
        try {
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * pw
            val full = Bitmap.createBitmap(pw + rowPad / p.pixelStride, ph, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val j = toJpeg(Bitmap.createBitmap(full, 0, 0, pw, ph))
            lastJpeg = j
            return j
        } finally { img.close() }
    }

    private fun toJpeg(b: Bitmap): ByteArray {
        val s = 800f / max(b.width, b.height)
        val sc = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        val o = ByteArrayOutputStream()
        sc.compress(Bitmap.CompressFormat.JPEG, 55, o)
        return o.toByteArray()
    }
}
