package com.example.aiassistant

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.equationl.ncnnandroidppocr.OCR
import com.equationl.ncnnandroidppocr.bean.Device
import com.example.aiassistant.questionbank.QuestionBankManager
import com.equationl.ncnnandroidppocr.bean.DrawModel
import com.equationl.ncnnandroidppocr.bean.ImageSize
import com.equationl.ncnnandroidppocr.bean.ModelType

/**
 * 核心服务：悬浮球截图 + OCR + AI 分析
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val CHANNEL_NAME = "录屏服务"
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_NEED_RESTART = "need_restart"
        const val TAG = "ScreenCapture"
    }

    // ── 窗口与视图 ────────────────────────────────────────────────────
    internal lateinit var windowManager: WindowManager
    internal var floatBallView: View? = null
    internal var floatBallParams: WindowManager.LayoutParams? = null
    internal var areaOverlayView: View? = null
    internal var resultCardView: View? = null

    // ── MediaProjection ────────────────────────────────────────────────
    @Volatile internal var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    @Volatile internal var virtualDisplay: VirtualDisplay? = null
    @Volatile internal var imageReader: ImageReader? = null

    // ── 自动恢复凭据（锁屏后自动重建 MediaProjection，无需用户再次授权） ──
    private var savedResultCode: Int = Int.MIN_VALUE
    private var savedProjectionData: Intent? = null

    // ── 后台线程 ──────────────────────────────────────────────────────
    internal var captureThread: HandlerThread? = null
    internal var captureHandler: Handler? = null

    // ── 屏幕参数 ──────────────────────────────────────────────────────
    internal var screenWidth = 0
    internal var screenHeight = 0
    internal var screenDensity = 0
    internal var displayDensity = 0f

    // ── 主线程 Handler ────────────────────────────────────────────────
    internal val mainHandler = Handler(Looper.getMainLooper())

    // ── 防抖与超时 ────────────────────────────────────────────────────
    @Volatile internal var isCapturing = false
    internal var captureTimeoutRunnable: Runnable? = null

    // ── 屏幕状态 ──────────────────────────────────────────────────────
    private var screenStateReceiver: BroadcastReceiver? = null

    // ── 静默搜题 ──────────────────────────────────────────────────────
    internal var smallBallView: View? = null
    internal var ballMenuView: View? = null
    internal var dictOverlayView: View? = null
    internal var smallBallParams: WindowManager.LayoutParams? = null
    internal var silentSearchText: String? = null
    internal var silentSearchReady = false
    @Volatile internal var isSilentCapture = false

    // ── 缓存截图上下文用于快捷切换重分析 ──
    @Volatile internal var lastQuestionText: String? = null
    @Volatile internal var lastCroppedBitmap: Bitmap? = null

    // ── PaddleOCR ─────────────────────────────────────────────────────
    internal var ocrAvailable = false
    internal var ocrCrashRecovering = false  // 原生 OCR 崩溃恢复标记
    internal val paddleOcr: OCR by lazy {
        // 检查上次是否因原生崩溃退出
        val prefs = getSharedPreferences("ai_assistant_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("ocr_last_call_crashed", false)) {
            Log.w(TAG, "检测到上次 OCR 调用导致崩溃，跳过本地 OCR 初始化")
            ocrCrashRecovering = true
            ocrAvailable = false
            return@lazy OCR()  // 返回空实例，不会被使用
        }

        // 每次初始化前重新设置环境变量，确保 OpenMP 读取到
        setOmpEnvVars()
        val ocr = OCR()
        val initResult = ocr.initModelFromAssert(assets, ModelType.Mobile, ImageSize.Size720, Device.CPU)
        if (initResult) {
            Log.i(TAG, "PaddleOCR 初始化成功")
        } else {
            Log.e(TAG, "PaddleOCR 初始化失败")
            mainHandler.post {
                Toast.makeText(this@ScreenCaptureService, "文字识别引擎初始化失败，请重启应用", Toast.LENGTH_LONG).show()
            }
        }
        ocrAvailable = initResult
        ocr
    }

    /** 设置 OpenMP 环境变量，防止线程亲和性崩溃 */
    private fun setOmpEnvVars() {
        try {
            android.system.Os.setenv("KMP_AFFINITY", "none", true)
            android.system.Os.setenv("OMP_NUM_THREADS", "1", true)
            android.system.Os.setenv("OMP_PROC_BIND", "false", true)
            android.system.Os.setenv("GOMP_CPU_AFFINITY", "0", true)
            android.system.Os.setenv("OPENCV_FOR_THREADS_NUM", "1", true)
        } catch (_: Exception) {}
    }

    // ── 请求 ID（丢弃过期回调） ──────────────────────────────────────
    @Volatile internal var currentRequestId: Long = 0

    // ── 渲染失败重试 ──────────────────────────────────────────────────
    @Volatile private var lastOcrText: String? = null
    @Volatile private var lastImageBase64: String? = null
    internal val retryCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val maxRetries: Int = 3

    // ── 题库匹配 ──────────────────────────────────────────────────────
    @Volatile internal var lastBankMatch: com.example.aiassistant.questionbank.Question? = null

    // ═════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        displayDensity = metrics.density

        captureThread = HandlerThread("CaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        captureHandler?.post { paddleOcr }
        OpenAIApiService.warmUpConnection(AppPreferences.getApiBaseUrl(this).ifBlank { AppPreferences.DEFAULT_BASE_URL })

        registerPrefListener()

        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen turned off — cancelling pending capture")
                        onScreenOff()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.d(TAG, "Screen unlocked — checking MediaProjection state")
                        onScreenUnlocked()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, filter)
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "float_ball_size" -> mainHandler.post { updateFloatBallSize() }
            "silent_search" -> mainHandler.post { updateSmallBallVisibility() }
        }
    }

    internal fun registerPrefListener() {
        getSharedPreferences("ai_assistant_prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefListener)
    }

    internal fun updateFloatBallSize() {
        val view = floatBallView ?: return
        val params = floatBallParams ?: return
        val sizeDp = AppPreferences.getFloatBallSize(this)
        val ballSize = dpToPx(sizeDp)
        params.width = ballSize
        params.height = ballSize
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Update ball size failed", e)
        }
        if (smallBallView != null) {
            removeSmallBall()
            showSmallBall()
        }
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
            // 保存授权凭据，锁屏后可自动恢复，无需用户再次授权
            savedResultCode = resultCode
            savedProjectionData = data.clone() as Intent

            showFloatBall()
            updateSmallBallVisibility()
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
        mainHandler.removeCallbacksAndMessages(null)
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            screenStateReceiver = null
        }
        dismissDictionaryOverlay()
        dismissBallMenu()
        removeFloatBall()
        removeSmallBall()
        removeAreaOverlay()
        removeResultCard()
        releaseMediaProjection()
        captureThread?.quitSafely()
        CloudOcrClient.cancelCurrentRequest()
        OpenAIApiService.cancelCurrentRequest()
        getSharedPreferences("ai_assistant_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    // ═════════════════════════════════════════════════════════════════════
    // MediaProjection
    // ═════════════════════════════════════════════════════════════════════

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system — will auto-recover")
                    mainHandler.post {
                        // 释放旧资源（但保留 savedResultCode/savedProjectionData 用于恢复）
                        releaseMediaProjection()
                        // 尝试自动恢复，不打扰用户
                        if (tryAutoRecoverMediaProjection()) {
                            Log.d(TAG, "MediaProjection auto-recovered after onStop")
                            updateNotification()
                        } else {
                            Log.w(TAG, "Auto-recover failed after onStop")
                            updateNotificationForFailure()
                        }
                    }
                }
            }
            mediaProjection!!.registerCallback(mediaProjectionCallback!!, mainHandler)
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

    /**
     * 使用保存的授权凭据自动恢复 MediaProjection。
     * 锁屏/系统回收后调用，用户无需再次手动授权。
     * @return true 恢复成功
     */
    private fun tryAutoRecoverMediaProjection(): Boolean {
        val code = savedResultCode
        val data = savedProjectionData
        if (code == Int.MIN_VALUE || data == null) {
            Log.w(TAG, "No saved projection credentials for auto-recover")
            return false
        }
        return try {
            setupMediaProjection(code, data.clone() as Intent)
            val ok = mediaProjection != null && virtualDisplay != null && imageReader != null
            if (ok) Log.i(TAG, "MediaProjection auto-recover succeeded")
            else Log.w(TAG, "MediaProjection auto-recover: setup returned incomplete state")
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "Auto-recover SecurityException (token consumed on API 34+): ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Auto-recover failed", e)
            false
        }
    }

    private fun releaseMediaProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) } } catch (_: Exception) {}
        mediaProjectionCallback = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    // ═════════════════════════════════════════════════════════════════════
    // 截图入口
    // ═════════════════════════════════════════════════════════════════════

    internal fun onFloatBallClicked() {
        if (imageReader == null || virtualDisplay == null || mediaProjection == null) {
            if (isCapturing) {
                isCapturing = false
                cancelCaptureTimeout()
            }
            // MediaProjection 失效 — 先尝试自动恢复（无需用户操作）
            if (tryAutoRecoverMediaProjection()) {
                Log.d(TAG, "MediaProjection auto-recovered on ball click")
                updateNotification()
                // 恢复成功，继续执行后续截图逻辑（不 return）
            } else {
                // 自动恢复失败，直接弹系统授权弹窗（无需绕道 MainActivity）
                val consentIntent = Intent(this, MediaProjectionConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(consentIntent)
                return
            }
        }

        if (isCapturing) {
            Toast.makeText(this, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }

        retryCount.set(0)
        isSilentCapture = false
        isCapturing = true
        removeSmallBall() // 避免小球出现在截图中
        scheduleCaptureTimeout()

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

    // ═════════════════════════════════════════════════════════════════════
    // 屏幕状态处理
    // ═════════════════════════════════════════════════════════════════════

    private fun onScreenOff() {
        dismissDictionaryOverlay()
        dismissBallMenu()
        if (isCapturing) {
            Log.d(TAG, "Resetting isCapturing due to screen off")
            isCapturing = false
            isSilentCapture = false
            cancelCaptureTimeout()
            removeAreaOverlay()
            reattachFloatBall()
            reattachSmallBall()
        }
        CloudOcrClient.cancelCurrentRequest()
        OpenAIApiService.cancelCurrentRequest()
    }

    private fun onScreenUnlocked() {
        if (imageReader == null || virtualDisplay == null || mediaProjection == null) {
            Log.d(TAG, "MediaProjection lost during screen lock — attempting auto-recover")
            if (tryAutoRecoverMediaProjection()) {
                Log.d(TAG, "MediaProjection auto-recovered after screen unlock")
                updateNotification()
            } else {
                Log.w(TAG, "Auto-recover failed after unlock — will retry on next tap")
                updateNotificationForFailure()
            }
        } else {
            Log.d(TAG, "MediaProjection still valid after unlock")
            updateNotification()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AI 管道
    // ═════════════════════════════════════════════════════════════════════

    private fun recordWrongQuestionDirectly(text: String, originalBitmap: Bitmap) {
        captureHandler?.post {
            com.example.aiassistant.questionbank.WrongQuestionManager.addWrongQuestion(
                this@ScreenCaptureService,
                text,
                originalBitmap
            )
            originalBitmap.recycle()
            mainHandler.post {
                isCapturing = false
                isSilentCapture = false
                cancelCaptureTimeout()
                Toast.makeText(this@ScreenCaptureService, "📝 错题已成功录入错题本！", Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun sendToAI(bitmap: Bitmap) {
        // 检查 MediaProjection 是否有效
        if (mediaProjection == null || imageReader == null || virtualDisplay == null) {
            bitmap.recycle()
            updateResultCard("❌ 录屏已失效，请重新开启悬浮球")
            if (isSilentCapture) {
                isCapturing = false
                isSilentCapture = false
                cancelCaptureTimeout()
                mainHandler.post { reattachSmallBall() }
            }
            return
        }

        val requestId = System.currentTimeMillis()
        currentRequestId = requestId

        val t0 = System.currentTimeMillis()
        val questionType = AppPreferences.getCurrentQuestionType(this)

        // 错题直录模式下的图形推理题分流
        if (AppPreferences.getFloatClickAction(this) == AppPreferences.CLICK_ACTION_RECORD_WRONG) {
            if (questionType.usesVision) {
                captureHandler?.post {
                    com.example.aiassistant.questionbank.WrongQuestionManager.addWrongQuestion(
                        this@ScreenCaptureService,
                        "[图形推理题]",
                        bitmap
                    )
                    bitmap.recycle()
                    mainHandler.post {
                        isCapturing = false
                        isSilentCapture = false
                        cancelCaptureTimeout()
                        Toast.makeText(this@ScreenCaptureService, "📝 图形错题已成功录入错题本！", Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
        }

        // 图形推理：跳过 OCR，直接发送图片给视觉模型
        if (questionType.usesVision) {
            updateResultCard("正在分析图片...")
            captureHandler?.post {
                val jpegBytes = bitmapToJpeg(bitmap)
                bitmap.recycle()
                if (currentRequestId != requestId) return@post
                val jpegBase64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
                requestVisionAnalysis(jpegBase64, t0, requestId)
            }
            return
        }

        updateResultCard("🔍 正在识别图片文字...")

        val scaled = downscaleForOcr(bitmap)

        captureHandler?.post {
            fun recycleInput() {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }

            if (currentRequestId != requestId) {
                recycleInput()
                return@post
            }

            val ocrMode = AppPreferences.getOcrMode(this@ScreenCaptureService)

            if (ocrMode == AppPreferences.OCR_MODE_CLOUD) {
                val ocrType = AppPreferences.getCloudOcrType(this@ScreenCaptureService)
                val ocrUrl = if (ocrType == AppPreferences.CLOUD_OCR_TYPE_TEXT)
                    AppPreferences.getCloudTextOcrUrl(this@ScreenCaptureService)
                else
                    AppPreferences.getCloudOcrUrl(this@ScreenCaptureService)
                val ocrToken = AppPreferences.getCloudOcrToken(this@ScreenCaptureService)

                if (ocrToken.isBlank()) {
                    recycleInput()
                    updateResultCard("❌ 未配置云端 OCR Token，请在设置中填写")
                    return@post
                }

                if (ocrType == AppPreferences.CLOUD_OCR_TYPE_TEXT) {
                    CloudOcrClient.parseText(bitmap = scaled, url = ocrUrl, token = ocrToken,
                        onSuccess = { rawText ->
                            if (currentRequestId != requestId) return@parseText
                            val cleanedText = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                            if (cleanedText.isBlank()) { recycleInput(); updateResultCard("❌ 云端 OCR 返回空文本"); return@parseText }
                            
                            if (AppPreferences.getFloatClickAction(this@ScreenCaptureService) == AppPreferences.CLICK_ACTION_RECORD_WRONG) {
                                if (scaled !== bitmap) bitmap.recycle()
                                recordWrongQuestionDirectly(cleanedText, scaled)
                                return@parseText
                            }

                            recycleInput()
                            val ocrTime = System.currentTimeMillis() - t0
                            Log.d(TAG, "云端文字OCR完成: ${ocrTime}ms, 字数: ${cleanedText.length}")
                            showLoading("✅ 识别到 ${cleanedText.length} 个字 (${ocrTime}ms)\n正在请求 AI...")
                            requestAiAnalysis(cleanedText, t0, requestId)
                        },
                        onError = { if (currentRequestId == requestId) { recycleInput(); updateResultCard("❌ 云端 OCR 失败：$it") } })
                } else {
                    CloudOcrClient.parseLayout(bitmap = scaled, url = ocrUrl, token = ocrToken,
                        onSuccess = { markdownText ->
                            if (currentRequestId != requestId) return@parseLayout
                            val cleanedText = markdownText.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                            if (cleanedText.isBlank()) { recycleInput(); updateResultCard("❌ 云端 OCR 返回空文本"); return@parseLayout }

                            if (AppPreferences.getFloatClickAction(this@ScreenCaptureService) == AppPreferences.CLICK_ACTION_RECORD_WRONG) {
                                if (scaled !== bitmap) bitmap.recycle()
                                recordWrongQuestionDirectly(cleanedText, scaled)
                                return@parseLayout
                            }

                            recycleInput()
                            val ocrTime = System.currentTimeMillis() - t0
                            Log.d(TAG, "云端布局OCR完成: ${ocrTime}ms, 字数: ${cleanedText.length}")
                            showLoading("✅ 识别到 ${cleanedText.length} 个字 (${ocrTime}ms)\n正在请求 AI...")
                            requestAiAnalysis(cleanedText, t0, requestId)
                        },
                        onError = { if (currentRequestId == requestId) { recycleInput(); updateResultCard("❌ 云端 OCR 失败：$it") } })
                }
            } else {
                if (!ocrAvailable || ocrCrashRecovering) {
                    recycleInput()
                    val msg = if (ocrCrashRecovering)
                        "⚠️ 本地OCR上次崩溃，已自动禁用\n请在设置中配置云端OCR，或重启应用重试"
                    else
                        "❌ 文字识别引擎未就绪，请重启应用"
                    updateResultCard(msg)
                    if (isSilentCapture) {
                        isCapturing = false
                        isSilentCapture = false
                        cancelCaptureTimeout()
                        mainHandler.post { reattachSmallBall() }
                    }
                    return@post
                }

                // 每次 OCR 调用前重新设置环境变量（防止 OpenMP 线程亲和性崩溃）
                setOmpEnvVars()
                // 设置崩溃检测标记（如果进程在 detectBitmap 中崩溃，标记会保留）
                val prefs = getSharedPreferences("ai_assistant_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("ocr_last_call_crashed", true).commit()

                val result = paddleOcr.detectBitmap(scaled, drawModel = DrawModel.None)

                // OCR 成功，清除崩溃标记
                prefs.edit().putBoolean("ocr_last_call_crashed", false).apply()

                if (currentRequestId != requestId) {
                    recycleInput()
                    return@post
                }

                if (result == null) {
                    recycleInput()
                    updateResultCard("❌ 文字识别失败")
                    return@post
                }

                val ocrTime = System.currentTimeMillis() - t0
                val ocrText = result.text

                if (ocrText.isBlank()) {
                    recycleInput()
                    updateResultCard("❌ 未识别到文字，请重新截图")
                    return@post
                }

                val cleanedOcrText = ocrText.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")

                if (AppPreferences.getFloatClickAction(this@ScreenCaptureService) == AppPreferences.CLICK_ACTION_RECORD_WRONG) {
                    prefs.edit().putBoolean("ocr_last_call_crashed", false).apply()
                    if (scaled !== bitmap) bitmap.recycle()
                    recordWrongQuestionDirectly(cleanedOcrText, scaled)
                    return@post
                }

                recycleInput()
                Log.d(TAG, "OCR完成: ${ocrTime}ms, 字数: ${cleanedOcrText.length}")
                showLoading("✅ 识别到 ${cleanedOcrText.length} 个字 (${ocrTime}ms)\n正在请求 AI...")

                requestAiAnalysis(cleanedOcrText, t0, requestId)
            }
        }
    }

    internal fun requestAiAnalysis(ocrText: String, startTime: Long, requestId: Long) {
        lastOcrText = ocrText
        val questionType = AppPreferences.getCurrentQuestionType(this)
        var prompt = AppPreferences.getPromptForType(this, questionType)

        // 题库匹配
        Log.d(TAG, "题库查询: 文本前80字=${ocrText.take(80)}")
        val bankMatch = QuestionBankManager.search(ocrText)
        if (bankMatch != null) {
            Log.d(TAG, "题库命中: ${bankMatch.id}, 答案=${bankMatch.answer}")
        } else {
            Log.d(TAG, "题库未命中 (已加载=${QuestionBankManager.isLoaded()}, 文本长度=${ocrText.length})")
        }
        lastBankMatch = bankMatch

        // 命中题库：在原始prompt前注入题库数据，去掉OCR校对部分
        if (bankMatch != null) {
            val bankContext = buildString {
                appendLine("=== 题库已收录此题，以下为题库数据（正确答案已确定） ===")
                appendLine()
                appendLine("【题库题目】")
                appendLine(bankMatch.stem)
                appendLine()
                if (bankMatch.options.isNotEmpty()) {
                    appendLine("【题库选项】")
                    for ((i, opt) in bankMatch.options.withIndex()) {
                        appendLine("${'A' + i}. ${opt.text}")
                    }
                    appendLine()
                }
                appendLine("【题库正确答案】${bankMatch.answer}")
                appendLine()
                if (bankMatch.analysis.isNotBlank()) {
                    appendLine("【题库参考解析】")
                    appendLine(bankMatch.analysis)
                    appendLine()
                }
                appendLine("=== 以上为题库数据，请以此为基础进行分析 ===")
                appendLine("特别注意：")
                appendLine("1. 正确答案已确定为 ${bankMatch.answer}，请围绕该答案展开分析，对每个选项逐一说明选或不选的理由。")
                appendLine("2. 请在JSON中额外输出 keywords 数组，列出题目中的3-8个关键词/关键语句（用于在题目中标红高亮）。")
                appendLine()
            }
            // 去掉原始prompt中的OCR校对部分（题库数据已经是干净的）
            val ocrStart = prompt.indexOf("⭐")
            val taskStart = prompt.indexOf("任务要求")
            val cleanPrompt = if (ocrStart > 0 && taskStart > ocrStart) {
                // 去掉 intro 中关于 OCR 的描述 + 整个 OCR 校对段落
                val intro = prompt.substring(0, ocrStart)
                    .replace("用户提供的题目文字来自OCR识别，可能存在错别字、漏字、多字或形近字误识别等错误。你的首要任务是：", "")
                    .trim()
                intro + "\n\n" + prompt.substring(taskStart)
            } else {
                prompt
            }
            prompt = bankContext + cleanPrompt
        }
        val selfCheckInstruction = TeacherManager.getSelfCheckInstruction()
        val customR2Prompt = TeacherManager.getCustomR2Prompt(this, questionType)
            ?: AppPreferences.getCustomR2Prompt(this)

        val rounds = StrategyManager.buildRounds()
        val scheme = StrategyManager.activeScheme
        val strategyType = when (scheme?.id) {
            "builtin_single" -> "single"
            "builtin_selfcheck" -> "self_check"
            "builtin_customr2" -> "custom_r2"
            else -> if (scheme?.customR2Prompt?.isNotEmpty() == true) "custom_r2" else "standard"
        }
        val isMultiRound = rounds.size >= 2

        val ocrTime = System.currentTimeMillis() - startTime

        if (isSilentCapture && isMultiRound) {
            showBallProgress("1/3")
        }

        // 命中题库时，用户消息改为引用题库数据
        val userMsg = if (bankMatch != null) {
            "请基于系统提示中的题库数据，按照要求进行详细分析，输出标准JSON格式。"
        } else null

        MultiPassAnalyzer.analyze(
            ocrText = ocrText,
            rounds = rounds,
            defaultPrompt = prompt,
            customR2Prompt = if (strategyType == "custom_r2") (scheme?.customR2Prompt ?: customR2Prompt) else customR2Prompt,
            selfCheckInstruction = selfCheckInstruction,
            strategyType = strategyType,
            r3Prompt = TeacherManager.getR3Prompt(),
            userMessage = userMsg,
            onProgress = { phase, detail ->
                if (currentRequestId != requestId) return@analyze
                if (isSilentCapture) {
                    when (phase) {
                        "R1" -> showBallProgress("1/3")
                        "R2" -> showBallProgress("2/3")
                        "R3" -> showBallProgress("3/3")
                    }
                } else {
                    showLoading(detail ?: phase)
                }
            },
            onComplete = { fullText ->
                if (currentRequestId != requestId) return@analyze
                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "总耗时: ${totalTime}ms (OCR: ${ocrTime}ms, AI: ${totalTime - ocrTime}ms)")
                Log.d(TAG, "AI响应前100字: ${fullText.take(100)}")
                hideBallProgress()
                if (isSilentCapture) {
                    silentSearchText = fullText
                    silentSearchReady = true
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    mainHandler.post { reattachSmallBall() }
                } else {
                    // 预检 JSON 解析，失败则直接重试
                    val json = tryParseJsonResponse(fullText)
                    if (json == null) {
                        Log.w(TAG, "JSON 解析失败，准备重试 (${retryCount.get() + 1}/$maxRetries)")
                        retryAiAnalysis(requestId, startTime)
                    } else {
                        updateResultCard(fullText, isAiResponse = true, onRenderFail = {
                            Log.w(TAG, "渲染异常，准备重试 (${retryCount.get() + 1}/$maxRetries)")
                            retryAiAnalysis(requestId, startTime)
                        })
                        retryCount.set(0)
                    }
                }
            },
            onError = { error ->
                if (currentRequestId != requestId) return@analyze
                hideBallProgress()
                if (isSilentCapture) {
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    mainHandler.post {
                        reattachSmallBall()
                        Toast.makeText(this@ScreenCaptureService, "静默搜题失败：$error", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e(TAG, "AI请求失败: $error")
                    updateResultCard("❌ 请求失败：$error")
                }
            }
        )
    }

    private fun retryAiAnalysis(requestId: Long, startTime: Long) {
        if (retryCount.get() >= maxRetries) {
            Log.w(TAG, "已达最大重试次数 ($maxRetries)，显示原始文本")
            updateResultCard(lastOcrText ?: "重试失败", isAiResponse = false)
            retryCount.set(0)
            isCapturing = false
            cancelCaptureTimeout()
            return
        }
        val ocr = lastOcrText
        if (ocr == null) {
            Log.w(TAG, "lastOcrText 为 null，无法重试")
            updateResultCard("重试失败：无原始文本", isAiResponse = false)
            retryCount.set(0)
            isCapturing = false
            cancelCaptureTimeout()
            return
        }
        val count = retryCount.incrementAndGet()
        showLoading("渲染失败，第${count}次重试中...")
        requestAiAnalysis(ocr, startTime, requestId)
    }

    // ── 视觉分析管道 ──────────────────────────────────────────────────

    internal fun requestVisionAnalysis(imageBase64: String, startTime: Long, requestId: Long) {
        lastImageBase64 = imageBase64
        val questionType = AppPreferences.getCurrentQuestionType(this)
        val prompt = AppPreferences.getPromptForType(this, questionType)
        val round = StrategyManager.buildRounds().firstOrNull()
            ?: RoundConfig(
                AiModelConfig(name = "", baseUrl = AppPreferences.getApiBaseUrl(this),
                    apiKey = AppPreferences.getApiKey(this), model = AppPreferences.getApiModel(this)),
                thinking = false
            )

        showLoading("🎨 视觉模型分析中...")

        MultiPassAnalyzer.analyzeWithImage(
            imageBase64 = imageBase64,
            systemPrompt = prompt,
            round = round,
            onProgress = { phase, detail ->
                if (currentRequestId != requestId) return@analyzeWithImage
                showLoading(detail ?: phase)
            },
            onComplete = { fullText ->
                if (currentRequestId != requestId) return@analyzeWithImage
                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "视觉分析总耗时: ${totalTime}ms")
                hideBallProgress()
                if (isSilentCapture) {
                    silentSearchText = fullText
                    silentSearchReady = true
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    mainHandler.post { reattachSmallBall() }
                } else {
                    val json = tryParseJsonResponse(fullText)
                    if (json == null) {
                        Log.w(TAG, "视觉分析 JSON 解析失败，准备重试 (${retryCount.get() + 1}/$maxRetries)")
                        retryVisionAnalysis(requestId, startTime)
                    } else {
                        updateResultCard(fullText, isAiResponse = true, onRenderFail = {
                            Log.w(TAG, "视觉分析渲染异常，准备重试 (${retryCount.get() + 1}/$maxRetries)")
                            retryVisionAnalysis(requestId, startTime)
                        })
                        retryCount.set(0)
                    }
                }
            },
            onError = { error ->
                if (currentRequestId != requestId) return@analyzeWithImage
                hideBallProgress()
                if (isSilentCapture) {
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    mainHandler.post {
                        reattachSmallBall()
                        Toast.makeText(this@ScreenCaptureService, "视觉分析失败：$error", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    updateResultCard("❌ 视觉分析失败：$error")
                }
            }
        )
    }

    private fun retryVisionAnalysis(requestId: Long, startTime: Long) {
        if (retryCount.get() >= maxRetries) {
            Log.w(TAG, "视觉分析已达最大重试次数 ($maxRetries)，显示原始文本")
            updateResultCard("视觉分析重试失败", isAiResponse = false)
            retryCount.set(0)
            isCapturing = false
            cancelCaptureTimeout()
            return
        }
        val img = lastImageBase64
        if (img == null) {
            Log.w(TAG, "lastImageBase64 为 null，无法重试")
            updateResultCard("重试失败：无原始图片", isAiResponse = false)
            retryCount.set(0)
            isCapturing = false
            cancelCaptureTimeout()
            return
        }
        val count = retryCount.incrementAndGet()
        showLoading("渲染失败，第${count}次重试中...")
        requestVisionAnalysis(img, startTime, requestId)
    }

    internal fun reRunAnalysis() {
        val qType = AppPreferences.getCurrentQuestionType(this)
        val text = lastOcrText
        val base64 = lastImageBase64
        val requestId = System.currentTimeMillis()
        currentRequestId = requestId
        val t0 = System.currentTimeMillis()

        if (qType.usesVision) {
            if (!base64.isNullOrBlank()) {
                requestVisionAnalysis(base64, t0, requestId)
            } else {
                Toast.makeText(this, "图片上下文失效，请重新截屏", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (!text.isNullOrBlank()) {
                showLoading("正在切换配置重分析...")
                requestAiAnalysis(text, t0, requestId)
            } else {
                Toast.makeText(this, "文本上下文失效，请重新截屏", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Bitmap 转 JPEG 字节数组（质量 85%） */
    internal fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    }

    // ═════════════════════════════════════════════════════════════════════
    // 工具
    // ═════════════════════════════════════════════════════════════════════

    internal fun dpToPx(dp: Int): Int = (dp * displayDensity).toInt()
}
