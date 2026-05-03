package com.example.aiassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 核心服务：
 *  ① 维持前台通知
 *  ② 启动时创建 VirtualDisplay（持续运行，但不消耗 CPU）
 *  ③ 点击悬浮球 → 按需从 ImageReader 取最新帧 → 裁剪 → AI 解析
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val CHANNEL_NAME = "录屏服务"
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val TAG = "ScreenCapture"
    }

    private lateinit var windowManager: WindowManager

    // 悬浮球
    private var floatBallView: View? = null
    private var floatBallParams: WindowManager.LayoutParams? = null

    // 区域选择
    private var areaOverlayView: View? = null

    // 结果卡片
    private var resultCardView: View? = null

    // MediaProjection（持续运行）
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 后台线程，用于处理截图
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // 屏幕参数
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // Android 14+ 要求 startForeground 明确指定 foregroundServiceType
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // 后台线程
        captureThread = HandlerThread("CaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        }

        if (resultCode != Int.MIN_VALUE && data != null) {
            // 先显示悬浮球（即使 MediaProjection 出问题也能看到球）
            showFloatBall()
            // 再初始化录屏
            try {
                setupMediaProjection(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection setup failed", e)
                Toast.makeText(this, "录屏初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "录屏授权无效，请重试", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeFloatBall()
        removeAreaOverlay()
        removeResultCard()
        releaseMediaProjection()
        captureThread?.quitSafely()
    }

    // ─────────────────────────────────────────────────────────────────────
    // MediaProjection：启动时创建，持续运行
    // 不使用 OnImageAvailableListener（避免每帧处理的巨大开销）
    // 只在需要时调用 acquireLatestImage() 按需取帧
    // ─────────────────────────────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        // Android 14+（API 34+）要求：必须先注册 Callback 才能 createVirtualDisplay
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    mainHandler.post {
                        releaseMediaProjection()
                        Toast.makeText(this@ScreenCaptureService, "录屏已被系统终止", Toast.LENGTH_SHORT).show()
                    }
                }
            }, mainHandler)
        }

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "AIAssistantCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        Log.d(TAG, "VirtualDisplay created: ${screenWidth}x${screenHeight}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // 悬浮球
    // ─────────────────────────────────────────────────────────────────────

    private fun showFloatBall() {
        if (floatBallView != null) return

        val ballSize = dpToPx(60)
        floatBallParams = WindowManager.LayoutParams(
            ballSize, ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - ballSize - dpToPx(16)
            y = screenHeight / 3
        }

        floatBallView = LayoutInflater.from(this).inflate(R.layout.layout_float_ball, null)
        setupFloatBallTouch(floatBallView!!)
        windowManager.addView(floatBallView, floatBallParams)
    }

    private fun removeFloatBall() {
        floatBallView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            floatBallView = null
        }
    }

    private fun detachFloatBall() {
        floatBallView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
    }

    private fun reattachFloatBall() {
        floatBallView?.let {
            try { windowManager.addView(it, floatBallParams) } catch (_: Exception) {}
        }
    }

    private fun setupFloatBallTouch(view: View) {
        val touchSlop = dpToPx(10).toFloat()
        var initialX = 0; var initialY = 0
        var touchStartX = 0f; var touchStartY = 0f
        var isDrag = false

        val longPressRunnable = Runnable {
            if (!isDrag) {
                Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
                AppPreferences.setFloatEnabled(this, false)
                stopSelf()
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatBallParams!!.x
                    initialY = floatBallParams!!.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDrag = false
                    mainHandler.postDelayed(longPressRunnable, 800)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDrag && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        isDrag = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDrag) {
                        floatBallParams!!.x = (initialX + dx).toInt()
                        floatBallParams!!.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(view, floatBallParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDrag) {
                        onFloatBallClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 截图入口
    // ─────────────────────────────────────────────────────────────────────

    private fun onFloatBallClicked() {
        if (imageReader == null || virtualDisplay == null) {
            Toast.makeText(this, "录屏未就绪，请重启应用", Toast.LENGTH_SHORT).show()
            return
        }

        val mode = AppPreferences.getCaptureMode(this)
        when (mode) {
            AppPreferences.MODE_FIXED_AREA -> {
                if (AppPreferences.isFixedRegionSet(this)) {
                    captureAndCrop(AppPreferences.getFixedRegion(this))
                } else {
                    captureAndShowSelector(saveAsFixed = true)
                }
            }
            else -> captureAndShowSelector(saveAsFixed = false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 方案 A：固定区域截图
    // ─────────────────────────────────────────────────────────────────────

    private fun captureAndCrop(cropRect: Rect) {
        detachFloatBall()

        // 等一帧刷新（悬浮球移除后的画面）
        mainHandler.postDelayed({
            captureHandler?.post {
                val bitmap = grabFrame()
                mainHandler.post {
                    reattachFloatBall()
                    if (bitmap != null) {
                        val cropped = cropBitmap(bitmap, cropRect)
                        if (cropped != null) {
                            showResultCard()
                            sendToAI(cropped)
                        } else {
                            Toast.makeText(this@ScreenCaptureService, "裁剪失败", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@ScreenCaptureService, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, 500)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 方案 B：自定义区域截图
    // ─────────────────────────────────────────────────────────────────────

    private fun captureAndShowSelector(saveAsFixed: Boolean) {
        detachFloatBall()

        mainHandler.postDelayed({
            captureHandler?.post {
                val bitmap = grabFrame()
                mainHandler.post {
                    if (bitmap != null) {
                        showAreaSelectionOverlay(bitmap, saveAsFixed)
                    } else {
                        reattachFloatBall()
                        Toast.makeText(this@ScreenCaptureService, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, 500)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 按需取帧（核心）
    // ─────────────────────────────────────────────────────────────────────

    private fun grabFrame(): Bitmap? {
        val reader = imageReader ?: return null
        var image = reader.acquireLatestImage()

        if (image == null) {
            // 可能缓冲区为空，等一小段时间再试
            Thread.sleep(100)
            image = reader.acquireLatestImage()
        }

        if (image == null) {
            Log.w(TAG, "acquireLatestImage returned null")
            return null
        }

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)

            // 裁掉行填充
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "grabFrame error", e)
            null
        } finally {
            image.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 区域选择覆盖层
    // ─────────────────────────────────────────────────────────────────────

    private fun showAreaSelectionOverlay(fullBitmap: Bitmap, saveAsFixed: Boolean) {
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
                if (cropped != null) {
                    showResultCard()
                    sendToAI(cropped)
                } else {
                    Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
                }
            },
            onCancelled = {
                removeAreaOverlay()
                reattachFloatBall()
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

    private fun removeAreaOverlay() {
        areaOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            areaOverlayView = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bitmap 裁剪
    // ─────────────────────────────────────────────────────────────────────

    private fun cropBitmap(source: Bitmap, rect: Rect): Bitmap? {
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

    // ─────────────────────────────────────────────────────────────────────
    // 结果卡片
    // ─────────────────────────────────────────────────────────────────────

    private fun showResultCard() {
        removeResultCard()

        val params = WindowManager.LayoutParams(
            dpToPx(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(32)
        }

        resultCardView = LayoutInflater.from(this).inflate(R.layout.layout_float_result, null)
        resultCardView?.findViewById<TextView>(R.id.btn_close_result)?.setOnClickListener {
            removeResultCard()
        }
        windowManager.addView(resultCardView, params)
    }

    private fun updateResultCard(text: String) {
        mainHandler.post {
            resultCardView?.let { card ->
                card.findViewById<View>(R.id.tv_loading)?.visibility = View.GONE
                card.findViewById<ScrollView>(R.id.scroll_result)?.visibility = View.VISIBLE
                card.findViewById<TextView>(R.id.tv_result)?.text = text
            }
        }
    }

    private fun removeResultCard() {
        resultCardView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            resultCardView = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // AI 调用
    // ─────────────────────────────────────────────────────────────────────

    private fun sendToAI(bitmap: Bitmap) {
        // 测试版本内置火山引擎 API 配置
        val baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        val apiKey = "1ee0cbb1-6df3-4c03-8c10-e4559ba3edf2"
        val model = "doubao-seed-2-0-mini-260215"
        
        var prompt = AppPreferences.getPrompt(this)
        if (prompt.isBlank()) {
            prompt = "你看见了什么？"
        }

        // 使用流式 API，逐步显示 AI 回答（大幅降低首字等待时间）
        OpenAIApiService.analyzeImageStream(
            bitmap = bitmap,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            onChunk = { _, fullTextSoFar ->
                // 每收到一段文字就刷新卡片（流式输出）
                updateResultCard(fullTextSoFar)
            },
            onComplete = { fullText ->
                updateResultCard(fullText)
            },
            onError = { error ->
                updateResultCard("❌ 请求失败：$error")
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // 通知
    // ─────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "AI伴学录屏服务通知渠道" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI伴学运行中")
            .setContentText("点击悬浮球可截图并获取 AI 解析")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 释放资源
    // ─────────────────────────────────────────────────────────────────────

    private fun releaseMediaProjection() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
