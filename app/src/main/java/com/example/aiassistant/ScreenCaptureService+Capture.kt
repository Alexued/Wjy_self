package com.example.aiassistant

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * 截图管道：取帧、裁剪、区域选择、OCR 预处理、超时管理
 */

// ── 截图入口 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.captureAndCrop(cropRect: Rect) {
    detachFloatBall()

    mainHandler.postDelayed({
        captureHandler?.post {
            val bitmap = grabFrame()
            mainHandler.post {
                reattachFloatBall()
                if (bitmap != null) {
                    val cropped = cropBitmap(bitmap, cropRect)
                    bitmap.recycle()
                    if (cropped != null) {
                        if (!isSilentCapture) showResultCard()
                        sendToAI(cropped)
                    } else {
                        isCapturing = false
                        isSilentCapture = false
                        cancelCaptureTimeout()
                        reattachSmallBall()
                        Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    reattachSmallBall()
                    Toast.makeText(this, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }, 150)
}

internal fun ScreenCaptureService.captureAndShowSelector(saveAsFixed: Boolean) {
    detachFloatBall()

    mainHandler.postDelayed({
        captureHandler?.post {
            val bitmap = grabFrame()
            mainHandler.post {
                if (bitmap != null) {
                    showAreaSelectionOverlay(bitmap, saveAsFixed)
                } else {
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    reattachFloatBall()
                    reattachSmallBall()
                    Toast.makeText(this, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }, 150)
}

// ── 按需取帧 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.grabFrame(): Bitmap? {
    val reader = imageReader ?: return null
    var image = reader.acquireLatestImage()

    if (image == null) {
        Thread.sleep(30)
        image = reader.acquireLatestImage()
    }

    if (image == null) {
        Log.w(ScreenCaptureService.TAG, "acquireLatestImage returned null")
        return null
    }

    return try {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        var bmp: Bitmap? = null
        try {
            bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
        } catch (e: Exception) {
            bmp?.recycle()
            throw e
        }

        if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bmp!!, 0, 0, screenWidth, screenHeight)
            bmp!!.recycle()
            cropped
        } else {
            bmp!!
        }
    } catch (e: Exception) {
        Log.e(ScreenCaptureService.TAG, "grabFrame error", e)
        null
    } finally {
        try { image.close() } catch (_: Exception) {}
    }
}

// ── Bitmap 裁剪 ──────────────────────────────────────────────────────

internal fun ScreenCaptureService.cropBitmap(source: Bitmap, rect: Rect): Bitmap? {
    return try {
        val left   = rect.left.coerceIn(0, source.width - 1)
        val top    = rect.top.coerceIn(0, source.height - 1)
        val right  = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    } catch (e: Exception) {
        null
    }
}

// ── OCR 预处理 ────────────────────────────────────────────────────────

/** OCR 前缩小图片，加速识别（长边限制 960px 提速显著） */
internal fun ScreenCaptureService.downscaleForOcr(bitmap: Bitmap, maxSide: Int = 960): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxSide && h <= maxSide) return bitmap
    val scale = maxSide.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
}

// ── 超时管理 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.scheduleCaptureTimeout() {
    cancelCaptureTimeout()
    captureTimeoutRunnable = Runnable {
        if (isCapturing) {
            Log.w(ScreenCaptureService.TAG, "Capture timed out after 30s — resetting isCapturing")
            isCapturing = false
            cancelCaptureTimeout()
            reattachFloatBall()
            Toast.makeText(this, "截图超时，请重试", Toast.LENGTH_SHORT).show()
        }
    }
    mainHandler.postDelayed(captureTimeoutRunnable!!, 30_000L)
}

internal fun ScreenCaptureService.cancelCaptureTimeout() {
    captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    captureTimeoutRunnable = null
}

// ── 区域选择覆盖层 ────────────────────────────────────────────────────

internal fun ScreenCaptureService.showAreaSelectionOverlay(fullBitmap: Bitmap, saveAsFixed: Boolean) {
    removeAreaOverlay()

    val overlay = AreaSelectionOverlay(
        context = this,
        onAreaSelected = { rect ->
            removeAreaOverlay()
            reattachFloatBall()

            if (saveAsFixed) {
                AppPreferences.setFixedRegion(this, rect)
            }

            val cropped = cropBitmap(fullBitmap, rect)
            fullBitmap.recycle()
            if (cropped != null) {
                if (!isSilentCapture) showResultCard()
                sendToAI(cropped)
            } else {
                isCapturing = false
                isSilentCapture = false
                cancelCaptureTimeout()
                reattachSmallBall()
                Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
            }
        },
        onCancelled = {
            isCapturing = false
            isSilentCapture = false
            cancelCaptureTimeout()
            removeAreaOverlay()
            reattachFloatBall()
            reattachSmallBall()
            fullBitmap.recycle()
            if (isSilentCapture) {
                isSilentCapture = false
                reattachSmallBall()
            }
        }
    )

    val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0; y = 0
    }

    areaOverlayView = overlay
    windowManager.addView(overlay, params)
}

internal fun ScreenCaptureService.removeAreaOverlay() {
    areaOverlayView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        areaOverlayView = null
    }
}
