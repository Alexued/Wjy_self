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
import com.example.aiassistant.questionbank.WrongQuestionManager
import com.equationl.ncnnandroidppocr.bean.DrawModel
import com.equationl.ncnnandroidppocr.bean.ImageSize
import com.equationl.ncnnandroidppocr.bean.ModelType
import org.json.JSONObject
import org.json.JSONArray

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

        @Volatile var instance: ScreenCaptureService? = null
        @Volatile var isDictOcrMode = false
    }

    // ── 窗口与视图 ────────────────────────────────────────────────────
    internal lateinit var windowManager: WindowManager
    internal var floatBallView: View? = null
    internal var floatBallParams: WindowManager.LayoutParams? = null
    internal var areaOverlayView: View? = null
    internal var resultCardView: View? = null
    internal val usedToolsInCurrentRequest = java.util.Collections.synchronizedSet(mutableSetOf<String>())


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
    private var orientationListener: BroadcastReceiver? = null

    // ── 静默搜题 ──────────────────────────────────────────────────────
    internal var smallBallView: View? = null
    internal var ballMenuView: View? = null
    internal var dictOverlayView: View? = null
    internal var smallBallParams: WindowManager.LayoutParams? = null
    internal var silentSearchText: String? = null
    internal var silentSearchReady = false
    @Volatile internal var isSilentCapture = false

    // ── 防止重复弹出授权页面 ──────────────────────────────────────────
    @Volatile internal var isRequestingConsent = false

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
    @Volatile internal var primaryModelError: String? = null

    // ── 题库匹配 ──────────────────────────────────────────────────────
    @Volatile internal var lastBankMatch: com.example.aiassistant.questionbank.Question? = null

    // ═════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // 先以普通前台服务启动，避免 Android 14+ 在 MediaProjection 授权令牌尚未
        // 绑定到服务前就声明 mediaProjection 类型而触发 SecurityException 闪退。
        // 拿到录屏授权并创建 MediaProjection 后，再升级为 mediaProjection 类型。
        @Suppress("DEPRECATION")
        startForeground(NOTIFICATION_ID, buildNotification())

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

        // 监听屏幕旋转
        orientationListener = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                    mainHandler.post { onScreenRotation() }
                }
            }
        }
        registerReceiver(orientationListener, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))

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
                    MediaProjectionConsentActivity.ACTION_CONSENT_DENIED -> {
                        Log.d(TAG, "Consent denied — resetting flag")
                        isRequestingConsent = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(MediaProjectionConsentActivity.ACTION_CONSENT_DENIED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, filter)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, FloatBallTileService::class.java)
            )
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "float_ball_size" -> mainHandler.post { updateFloatBallSize() }
            "silent_search" -> mainHandler.post { updateSmallBallVisibility() }
            "keep_screen_on" -> mainHandler.post { updateKeepScreenOnState() }
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
            isRequestingConsent = false  // 授权成功，重置标记

            try {
                // Android 10+：在真正拿到授权后再声明 mediaProjection 前台服务类型。
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                }
                setupMediaProjection(resultCode, data)
                showFloatBall()
                updateSmallBallVisibility()
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection setup failed", e)
                Toast.makeText(this, "录屏初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
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
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            screenStateReceiver = null
        }
        orientationListener?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            orientationListener = null
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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, FloatBallTileService::class.java)
            )
        }
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
                    Log.d(TAG, "MediaProjection stopped by system")
                    mainHandler.post {
                        // 释放旧资源
                        releaseMediaProjection()
                        // Android 14+ token 是一次性的，已被消耗，无法自动恢复
                        // 用户下次点击悬浮球时会重新弹出授权
                        updateNotificationForFailure()
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
            } else if (!isRequestingConsent) {
                // 自动恢复失败，直接弹系统授权弹窗（无需绕道 MainActivity）
                isRequestingConsent = true
                val consentIntent = Intent(this, MediaProjectionConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                startActivity(consentIntent)
                return
            } else {
                return  // 已经在等待授权，不重复弹窗
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

    private fun onScreenRotation() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val newWidth = metrics.widthPixels
        val newHeight = metrics.heightPixels

        // 屏幕尺寸变化时重新调整悬浮球位置
        if (newWidth != screenWidth || newHeight != screenHeight) {
            Log.d(TAG, "Screen rotation detected: ${screenWidth}x${screenHeight} -> ${newWidth}x${newHeight}")
            screenWidth = newWidth
            screenHeight = newHeight
            screenDensity = metrics.densityDpi
            displayDensity = metrics.density

            // 重新调整悬浮球位置
            adjustFloatBallPosition()
            updateSmallBallPosition()
        }
    }

    private fun adjustFloatBallPosition() {
        val view = floatBallView ?: return
        val params = floatBallParams ?: return
        val ballSize = params.width

        // 确保悬浮球在屏幕范围内
        if (params.x + ballSize > screenWidth) {
            params.x = screenWidth - ballSize - dpToPx(16)
        }
        if (params.y + ballSize > screenHeight) {
            params.y = screenHeight - ballSize - dpToPx(16)
        }
        if (params.x < 0) params.x = dpToPx(16)
        if (params.y < 0) params.y = dpToPx(16)

        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "adjustFloatBallPosition failed", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AI 管道
    // ═════════════════════════════════════════════════════════════════════

    private fun recordWrongQuestionDirectly(text: String, originalBitmap: Bitmap) {
        captureHandler?.post {
            // 先搜题库，命中则用题库结构化数据
            val bankMatch = QuestionBankManager.search(text)
            if (bankMatch != null) {
                WrongQuestionManager.addFromBank(this@ScreenCaptureService, bankMatch, originalBitmap)
                Log.d(TAG, "错题录入：命中题库 ${bankMatch.id}")
            } else {
                WrongQuestionManager.addFromOcr(this@ScreenCaptureService, text, originalBitmap)
                Log.d(TAG, "错题录入：题库未命中，保存OCR文本")
            }
            originalBitmap.recycle()
            mainHandler.post {
                isCapturing = false
                isSilentCapture = false
                cancelCaptureTimeout()
                val msg = if (bankMatch != null) "📝 错题已录入（来自题库）" else "📝 错题已录入（OCR识别）"
                Toast.makeText(this@ScreenCaptureService, msg, Toast.LENGTH_LONG).show()
            }
        }
    }


    internal fun handleAnalysisCrash(stage: String, throwable: Throwable, bitmap: Bitmap? = null) {
        Log.e(TAG, "AI分析链路异常: $stage", throwable)
        try { bitmap?.recycle() } catch (_: Exception) {}
        mainHandler.post {
            hideBallProgress()
            updateResultCard("❌ AI 分析失败（$stage）：${throwable.message ?: throwable.javaClass.simpleName}\n\n请到 设置 → 数据备份与管理 → 导出问题日志，把日志文件发给开发者继续定位。", isAiResponse = false)
            isCapturing = false
            isSilentCapture = false
            cancelCaptureTimeout()
            reattachSmallBall()
        }
    }

    fun getActiveModelConfig(): AiModelConfig? {
        val activeModelId = AppPreferences.getActiveModelId(this)
        return ModelManager.get(activeModelId) ?: ModelManager.allModels.firstOrNull()
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

        val isVisionMode = (questionType == QuestionType.TU_XING_TUI_LI) || 
                           (AppPreferences.getAnalysisMode(this) == AppPreferences.ANALYSIS_MODE_VISION)

        // 异步转换 Base64 并保存，同时进行模式分流
        captureHandler?.post {
            val jpegBytes = bitmapToJpeg(bitmap)
            val jpegBase64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
            lastImageBase64 = jpegBase64

            // 错题直录分流
            if (AppPreferences.getFloatClickAction(this@ScreenCaptureService) == AppPreferences.CLICK_ACTION_RECORD_WRONG) {
                if (isVisionMode) {
                    WrongQuestionManager.addFromOcr(
                        this@ScreenCaptureService,
                        if (questionType == QuestionType.TU_XING_TUI_LI) "[图形推理题]" else "[错题截图]",
                        bitmap
                    )
                    bitmap.recycle()
                    mainHandler.post {
                        isCapturing = false
                        isSilentCapture = false
                        cancelCaptureTimeout()
                        Toast.makeText(this@ScreenCaptureService, "📝 错题已成功录入错题本！", Toast.LENGTH_LONG).show()
                    }
                    return@post
                }
            }

            // 图形推理/截图模式：直接走视觉管道
            if (isVisionMode) {
                bitmap.recycle()
                mainHandler.post {
                    updateResultCard("正在分析图片...")
                    val activeModel = getActiveModelConfig()
                    if (activeModel != null && !activeModel.isVision) {
                        hideBallProgress()
                        updateResultCard(
                            "⚠️ 当前模型「${activeModel.name}」不支持多模态识图。\n\n" +
                            "💡 图形推理或截图模式需要识别图像，请点击顶部「👤 老师」或进入系统设置切换为支持识图的模型（如 gpt-4o 等），以展示图文解析。",
                            isAiResponse = false
                        )
                        isCapturing = false
                        cancelCaptureTimeout()
                        return@post
                    }
                    requestVisionAnalysis(jpegBase64, t0, requestId)
                }
                return@post
            }

            // 正常文字解析管道
            mainHandler.post {
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

                        setOmpEnvVars()
                        val prefs = getSharedPreferences("ai_assistant_prefs", MODE_PRIVATE)
                        prefs.edit().putBoolean("ocr_last_call_crashed", true).commit()

                        val result = paddleOcr.detectBitmap(scaled, drawModel = DrawModel.None)
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
        }
    }

    private fun buildCandidateModels(isVisionMode: Boolean): List<AiModelConfig> {
        val activeModel = getActiveModelConfig()
        val list = mutableListOf<AiModelConfig>()
        
        // 1. 首选当前活跃模型
        if (activeModel != null) {
            list.add(activeModel)
        }
        
        // 2. 依次把所有其他模型作为后备备用模型
        val others = ModelManager.allModels.filter { it.id != activeModel?.id }
        if (isVisionMode) {
            list.addAll(others.filter { it.isVision })
            list.addAll(others.filter { !it.isVision })
        } else {
            list.addAll(others)
        }
        
        // 3. 兜底保障
        if (list.isEmpty()) {
            list.add(
                AiModelConfig(
                    name = "默认备用模型",
                    baseUrl = AppPreferences.getApiBaseUrl(this),
                    apiKey = AppPreferences.getApiKey(this),
                    model = AppPreferences.getApiModel(this)
                )
            )
        }
        return list
    }

    internal fun requestAiAnalysis(
        ocrText: String, 
        startTime: Long, 
        requestId: Long,
        modelIndex: Int = 0,
        candidates: List<AiModelConfig> = emptyList()
    ) {
        if (isDictOcrMode) {
            isDictOcrMode = false
            Log.i(TAG, "词典识词模式触发，OCR文本前100字=${ocrText.take(100)}")
            mainHandler.post {
                hideBallProgress()
                performDictOcrSearch(ocrText)
            }
            return
        }

        lastOcrText = ocrText
        val questionType = AppPreferences.getCurrentQuestionType(this)
        var prompt = AppPreferences.getPromptForType(this, questionType)

        // 题库匹配
        Log.i(TAG, "题库查询: 文本前80字=${ocrText.take(80)}")
        val bankMatch = QuestionBankManager.search(ocrText)
        if (bankMatch != null) {
            Log.i(TAG, "题库命中: ${bankMatch.id}, 答案=${bankMatch.answer}")
        } else {
            Log.i(TAG, "题库未命中 (已加载=${QuestionBankManager.isLoaded()}, 文本长度=${ocrText.length})")
        }
        lastBankMatch = bankMatch

        // 题库原题是否含有图片判定
        val hasBankImage = bankMatch != null && (
            bankMatch.stem.contains("<img") || 
            bankMatch.stem.contains("![") || 
            bankMatch.analysis.contains("<img") || 
            bankMatch.analysis.contains("![") ||
            (bankMatch.stem.contains("http") && (bankMatch.stem.contains(".png") || bankMatch.stem.contains(".jpg") || bankMatch.stem.contains(".jpeg") || bankMatch.stem.contains(".webp")))
        )

        val modelList = if (candidates.isEmpty()) buildCandidateModels(isVisionMode = false) else candidates
        val currentModel = modelList.getOrNull(modelIndex)

        if (modelIndex == 0) {
            primaryModelError = null
            usedToolsInCurrentRequest.clear()
        }

        if (currentModel == null) {
            Log.e(TAG, "模型故障转移链已尝试完毕，全部失败")
            updateResultCard("❌ 所有可用 AI 模型均请求失败，最后重试已终止。")
            hideBallProgress()
            return
        }

        if (hasBankImage) {
            Log.d(TAG, "题库命中且包含图片！自动升级为多模态识图管道")
            if (!currentModel.isVision) {
                var foundVisionModel = false
                for (i in modelIndex until modelList.size) {
                    if (modelList[i].isVision) {
                        Log.d(TAG, "当前模型「${currentModel.name}」不支持识图，自动跳跃至「${modelList[i].name}」发起识图请求")
                        val jpegBase64 = lastImageBase64
                        if (jpegBase64 != null) {
                            requestVisionAnalysis(jpegBase64, startTime, requestId, i, modelList)
                            return
                        }
                        foundVisionModel = true
                        break
                    }
                }
                if (!foundVisionModel) {
                    hideBallProgress()
                    updateResultCard(
                        "⚠️ 题库中该题包含图片信息，但当前模型链中所有模型均不支持【多模态识图】能力。\n\n" +
                        "💡 请在设置中切换或导入支持识图的模型（如 gpt-4o 或 Gemini 等）。",
                        isAiResponse = false
                    )
                    isCapturing = false
                    cancelCaptureTimeout()
                    return
                }
            } else {
                val jpegBase64 = lastImageBase64
                if (jpegBase64 != null) {
                    requestVisionAnalysis(jpegBase64, startTime, requestId, modelIndex, modelList)
                    return
                }
            }
        }

        val useTools = AppPreferences.isToolCallingEnabled(this) &&
                com.example.aiassistant.skills.ToolRegistry.hasTools()

        if (useTools) {
            prompt = getUniversalAgentPrompt()
        }

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
            if (useTools) {
                prompt = bankContext + prompt
            } else {
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
        }
        val baseUrl = currentModel.baseUrl
        val apiKey = currentModel.apiKey
        val model = currentModel.model
        val apiType = currentModel.apiType
        val thinking = currentModel.thinkingDefault
        val thinkingBudget = currentModel.thinkingBudget

        val ocrTime = System.currentTimeMillis() - startTime

        val userMsg = if (bankMatch != null) {
            "请基于系统提示中的题库数据，按照要求进行详细分析，输出标准JSON格式。"
        } else null

        val modelLabel = if (modelIndex > 0) "【备用】${currentModel.name}" else currentModel.name
        showLoading("⚡ AI 正在深度解析中...\n当前模型: $modelLabel")

        val onCompleteCallback = { fullText: String ->
            if (currentRequestId == requestId) {
                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "总耗时: ${totalTime}ms (OCR: ${ocrTime}ms, AI: ${totalTime - ocrTime}ms)")
                hideBallProgress()
                if (isSilentCapture) {
                    silentSearchText = fullText
                    silentSearchReady = true
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    mainHandler.post { reattachSmallBall() }
                } else {
                    performJsonValidationAndRepair(fullText, requestId, startTime, 0, isVision = false)
                }
            }
        }

        val onErrorCallback = { error: String ->
            if (currentRequestId == requestId) {
                if (modelIndex == 0) {
                    primaryModelError = "主模型「${currentModel.name}」请求失败：$error"
                }
                
                if (modelIndex + 1 < modelList.size) {
                    val nextModel = modelList[modelIndex + 1]
                    Log.w(TAG, "模型「${currentModel.name}」请求失败: $error. 自动切换至备用模型「${nextModel.name}」")
                    mainHandler.post {
                        val cleanMsg = when {
                            error.contains("401", ignoreCase = true) || error.contains("Unauthorized", ignoreCase = true) -> "API Key 校验未通过"
                            error.contains("timeout", ignoreCase = true) || error.contains("ConnectException", ignoreCase = true) -> "连接超时，请确认是否需要开启科学网络"
                            error.contains("403", ignoreCase = true) -> "无权限访问 (403)，请确认模型权限与额度"
                            error.contains("429", ignoreCase = true) -> "请求过于频繁 (429)"
                            else -> error.take(60)
                        }
                        Toast.makeText(this@ScreenCaptureService, "⚠️ 主模型「${currentModel.name}」失败: $cleanMsg\n已自动切换备用「${nextModel.name}」", Toast.LENGTH_LONG).show()
                        requestAiAnalysis(ocrText, startTime, requestId, modelIndex + 1, modelList)
                    }
                } else {
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
                        updateResultCard("❌ 所有模型均请求失败，最后错误：$error")
                    }
                }
            }
        }

        if (useTools) {
            val toolsArray = com.example.aiassistant.skills.ToolRegistry.toOpenAiToolsArrayForType(questionType)
            OpenAIApiService.analyzeWithTools(
                context = this@ScreenCaptureService,
                ocrText = ocrText,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                thinking = thinking,
                userMessage = userMsg,
                apiType = apiType,
                thinkingBudget = thinkingBudget,
                tools = toolsArray,
                onToolCall = { toolName ->
                    val displayName = when (toolName) {
                        "get_solving_skill" -> "解题技巧"
                        "get_typical_special_rule" -> "专项考点"
                        "query_question_bank" -> "本地题库"
                        else -> toolName
                    }
                    usedToolsInCurrentRequest.add(displayName)
                    showLoading("AI 正在使用工具：$displayName...")
                },
                onComplete = onCompleteCallback,
                onError = onErrorCallback
            )
        } else {
            OpenAIApiService.analyzeText(
                ocrText = ocrText,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                thinking = thinking,
                userMessage = userMsg,
                apiType = apiType,
                thinkingBudget = thinkingBudget,
                onComplete = onCompleteCallback,
                onError = onErrorCallback
            )
        }
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

    internal fun requestVisionAnalysis(
        imageBase64: String, 
        startTime: Long, 
        requestId: Long,
        modelIndex: Int = 0,
        candidates: List<AiModelConfig> = emptyList()
    ) {
        lastImageBase64 = imageBase64
        val questionType = AppPreferences.getCurrentQuestionType(this)
        var prompt = AppPreferences.getPromptForType(this, questionType)

        // 识别暂存的 lastOcrText 题库匹配
        val ocrText = lastOcrText
        val bankMatch = if (!ocrText.isNullOrBlank()) QuestionBankManager.search(ocrText) else null
        if (bankMatch != null) {
            Log.d(TAG, "视觉模式匹配题库命中: 强制注入参考答案 ${bankMatch.answer}")
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
                appendLine("=== 以上为题库数据，请结合输入的图片进行深度分析 ===")
                appendLine("特别注意：")
                appendLine("1. 正确答案已确定为 ${bankMatch.answer}，请围绕该答案展开分析，对每个选项逐一说明选或不选的理由。")
                appendLine("2. 请在JSON中额外输出 keywords 数组，列出题目中的3-8个关键词/关键语句。")
                appendLine()
            }
            prompt = bankContext + prompt
        }

        // 彻底的“视觉化处理”：如果是识图模式来的，就不要说 OCR，直接解析图片内容
        val ocrStart = prompt.indexOf("⭐")
        val taskStart = prompt.indexOf("任务要求")
        var visionPrompt = if (ocrStart > 0 && taskStart > ocrStart) {
            val intro = prompt.substring(0, ocrStart)
            intro + "\n\n" + prompt.substring(taskStart)
        } else {
            prompt
        }

        visionPrompt = visionPrompt
            .replace("用户提供的题目文字来自OCR识别，可能存在错别字、漏字、多字或形近字误识别等错误。你的首要任务是：", "用户提供的是题目截图。你的首要任务是直接识别并解析图片中的题目内容：")
            .replace("用户提供的题目文字来自 OCR 识别，可能存在错别字、漏字、多字、形近字误识别、标点错误、选项序号识别错误、设问混入题干等问题。你的任务是：", "用户提供的是题目截图。你的任务是直接识别并解析图片中的题目内容：")
            .replace("用户提供的题目文字来自OCR识别", "用户提供的是题目截图")
            .replace("用户提供的题目文字来自 OCR 识别", "用户提供的是题目截图")
            .replace("校对 OCR 题目", "解析图片中的题目")
            .replace("校对OCR题目", "解析图片中的题目")
            .replace("校对OCR文字", "解析图片内容")
            .replace("校对 OCR 文字", "解析图片内容")
            .replace("OCR原题", "图片原题")
            .replace("OCR识别", "直接识图")
            .replace("OCR 识别", "直接识图")

        val modelList = if (candidates.isEmpty()) buildCandidateModels(isVisionMode = true) else candidates
        val currentModel = modelList.getOrNull(modelIndex)

        if (modelIndex == 0) {
            primaryModelError = null
            usedToolsInCurrentRequest.clear()
        }

        if (currentModel == null) {
            Log.e(TAG, "多模态容错链尝试完毕，均失败")
            updateResultCard("❌ 所有可用视觉/备用大模型均调用失败。")
            hideBallProgress()
            return
        }

        // 如果备用模型不支持识图，且我们有 OCR 文本，自动退化调用它的 analyzeText
        if (!currentModel.isVision) {
            val ocr = lastOcrText
            if (!ocr.isNullOrBlank()) {
                Log.w(TAG, "备用模型「${currentModel.name}」不支持视觉多模态，自动退化执行文字分析管道")
                requestAiAnalysis(ocr, startTime, requestId, modelIndex, modelList)
                return
            }
        }

        val baseUrl = currentModel.baseUrl
        val apiKey = currentModel.apiKey
        val model = currentModel.model
        val apiType = currentModel.apiType
        val thinking = currentModel.thinkingDefault
        val thinkingBudget = currentModel.thinkingBudget

        val useTools = AppPreferences.isToolCallingEnabled(this) &&
                com.example.aiassistant.skills.ToolRegistry.hasTools()

        val promptForVision = if (useTools) {
            getUniversalAgentPrompt()
                .replace("用户提供的题目文字来自OCR识别，可能存在错别字或格式混乱", "用户提供了题目截图，可能包含图形和文字")
                .replace("分析用户提供的题目文本，提取核心题干", "分析用户提供的题目图片，提取核心题干和图形要素")
        } else {
            visionPrompt
        }

        val finalVisionPrompt = if (useTools && bankMatch != null) {
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
                appendLine("=== 以上为题库数据，请结合输入的图片进行深度分析 ===")
                appendLine("特别注意：")
                appendLine("1. 正确答案已确定为 ${bankMatch.answer}，请围绕该答案展开分析，对每个选项逐一说明选或不选的理由。")
                appendLine("2. 请在JSON中额外输出 keywords 数组，列出题目中的3-8个关键词/关键语句。")
                appendLine()
            }
            bankContext + promptForVision
        } else {
            promptForVision
        }

        val modelLabel = if (modelIndex > 0) "【备用】${currentModel.name}" else currentModel.name
        showLoading("🎨 视觉模型分析中...\n当前模型: $modelLabel")

        val onCompleteCallback = { fullText: String ->
            if (currentRequestId == requestId) {
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
                    performJsonValidationAndRepair(fullText, requestId, startTime, 0, isVision = true)
                }
            }
        }

        val onErrorCallback = { error: String ->
            if (currentRequestId == requestId) {
                if (modelIndex == 0) {
                    primaryModelError = "主模型「${currentModel.name}」请求失败：$error"
                }
                
                if (modelIndex + 1 < modelList.size) {
                    val nextModel = modelList[modelIndex + 1]
                    Log.w(TAG, "视觉模型「${currentModel.name}」调用失败: $error. 自动尝试备用「${nextModel.name}」")
                    mainHandler.post {
                        val cleanMsg = when {
                            error.contains("401", ignoreCase = true) || error.contains("Unauthorized", ignoreCase = true) -> "API Key 校验未通过"
                            error.contains("timeout", ignoreCase = true) || error.contains("ConnectException", ignoreCase = true) -> "连接超时，请确认是否需要开启科学网络"
                            error.contains("403", ignoreCase = true) -> "无权限访问 (403)，请确认模型权限与额度"
                            error.contains("429", ignoreCase = true) -> "请求过于频繁 (429)"
                            else -> error.take(60)
                        }
                        Toast.makeText(this@ScreenCaptureService, "⚠️ 视觉模型「${currentModel.name}」失败: $cleanMsg\n已自动切换备用「${nextModel.name}」", Toast.LENGTH_LONG).show()
                        requestVisionAnalysis(imageBase64, startTime, requestId, modelIndex + 1, modelList)
                    }
                } else {
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
                        Log.e(TAG, "视觉大模型请求失败: $error")
                        updateResultCard("❌ 所有备用视觉模型均请求失败，最后错误：$error")
                    }
                }
            }
        }

        if (useTools) {
            val toolsArray = com.example.aiassistant.skills.ToolRegistry.toOpenAiToolsArrayForType(questionType)
            OpenAIApiService.analyzeWithTools(
                context = this@ScreenCaptureService,
                ocrText = lastOcrText ?: "",
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                prompt = finalVisionPrompt,
                thinking = thinking,
                userMessage = if (bankMatch != null) "请基于系统提示中的题库数据与输入的图片，按照要求进行详细分析，输出标准JSON格式。" else null,
                apiType = apiType,
                thinkingBudget = thinkingBudget,
                tools = toolsArray,
                imageBase64 = imageBase64,  // 多模态传图参数
                onToolCall = { toolName ->
                    val displayName = when (toolName) {
                        "get_solving_skill" -> "解题技巧"
                        "get_typical_special_rule" -> "专项考点"
                        "query_question_bank" -> "本地题库"
                        else -> toolName
                    }
                    usedToolsInCurrentRequest.add(displayName)
                    showLoading("AI 正在使用工具：$displayName...")
                },
                onComplete = onCompleteCallback,
                onError = onErrorCallback
            )
        } else {
            OpenAIApiService.analyzeWithImage(
                imageBase64 = imageBase64,
                systemPrompt = finalVisionPrompt,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                thinking = thinking,
                apiType = apiType,
                thinkingBudget = thinkingBudget,
                onComplete = onCompleteCallback,
                onError = onErrorCallback
            )
        }
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

    // ── 高级 JSON 校验与智能自愈 (Max 2 Attempts) ────────────────────────────

    private fun validateJsonResponse(text: String): String? {
        android.util.Log.d("AIAssistantVerify", "validateJsonResponse: Received text for validation. Length: ${text.length}")
        val json = try {
            var cleaned = text.trim()
            val fencePattern = Regex("```[a-zA-Z]*\\s*")
            cleaned = cleaned.replace(fencePattern, "")
            cleaned = cleaned.replace("```", "")
            val firstBrace = cleaned.indexOf('{')
            val lastBrace = cleaned.lastIndexOf('}')
            if (firstBrace != -1 && lastBrace > firstBrace) {
                cleaned = cleaned.substring(firstBrace, lastBrace + 1)
            }
            JSONObject(cleaned)
        } catch (e: Exception) {
            val parseError = "JSON.parse 解析失败: ${e.message}"
            android.util.Log.e("AIAssistantVerify", "$parseError. Raw text sent to JSON parser: \n$text")
            return parseError
        }

        val resultError = doSchemaValidation(json)
        if (resultError != null) {
            android.util.Log.e("AIAssistantVerify", "JSON Schema/Business Validation FAILED: $resultError\nValidated JSON Object: $json")
            return resultError
        }

        android.util.Log.i("AIAssistantVerify", "JSON Validation PASSED successfully!")
        return null
    }

    private fun doSchemaValidation(json: JSONObject): String? {

        if (!json.has("correct_answer")) return "Schema 校验失败: 缺少 correct_answer 字段"

        val type = detectSchemaType(json)
        when (type) {
            QuestionType.LUO_JI_PAN_DUAN -> {
                if (json.has("logical_chain")) {
                    val chain = json.optJSONArray("logical_chain")
                    if (chain == null) return "Schema 校验失败: logical_chain 必须为数组"
                    for (i in 0 until chain.length()) {
                        val step = chain.optJSONObject(i) ?: return "Schema 校验失败: logical_chain 中的元素必须为对象"
                        if (!step.has("premise") || !step.has("deduction")) {
                            return "Schema 校验失败: logical_chain 步骤 ${i+1} 必须包含 premise 和 deduction"
                        }
                    }
                }
                if (!json.has("options_analysis")) return "Schema 校验失败: 缺少 options_analysis 选项分析"
                val opts = json.optJSONArray("options_analysis")
                if (opts == null || opts.length() < 4) return "Schema 校验失败: options_analysis 必须是长度不少于 4 的数组"
            }
            QuestionType.PIAN_DUAN_YUE_DU -> {
                if (!json.has("question_type")) return "Schema 校验失败: 片段阅读必须含有 question_type 题型字段"
                val opts = json.optJSONArray("options_analysis")
                if (opts == null || opts.length() < 4) return "Schema 校验失败: options_analysis 选项分析必须是长度不少于 4 的数组"
            }
            QuestionType.LUO_JI_TIAN_KONG -> {
                // Prompt 输出字段：question, core_meaning, skill_tags, breakthrough,
                // blanks_analysis[{position, context_hint, candidates[{word, correct, ...}], answer, summary}]
                if (!json.has("question")) return "Schema 校验失败: 逻辑填空必须含有 question 文段字段"
                val blanks = json.optJSONArray("blanks_analysis") ?: json.optJSONArray("blanks")
                if (blanks == null) return "Schema 校验失败: 逻辑填空必须含有 blanks_analysis 数组"
                if (blanks.length() == 0) return "Schema 校验失败: blanks_analysis 不能为空"
                for (i in 0 until blanks.length()) {
                    val b = blanks.optJSONObject(i) ?: return "Schema 校验失败: blanks_analysis 元素必须是对象"
                    if (!b.has("position") || !b.has("answer")) {
                        return "Schema 校验失败: blanks_analysis 元素必须含有 position 和 answer 字段"
                    }
                }
            }
            QuestionType.YU_JU_BIAO_DA -> {
                // Prompt 输出字段：question, question_subtype, structure_analysis,
                // key_clues[], correct_answer, options_analysis[], pitfall, analysis
                if (!json.has("question_subtype") && !json.has("structure_analysis") && !json.has("analysis")) {
                    return "Schema 校验失败: 语句表达必须含有 question_subtype 或 structure_analysis 或 analysis 字段"
                }
                val opts = json.optJSONArray("options_analysis")
                if (opts == null || opts.length() < 4) return "Schema 校验失败: options_analysis 必须是长度不少于 4 的数组"
            }
            QuestionType.TU_XING_TUI_LI -> {
                if (!json.has("pattern_type") || !json.has("rule_description")) {
                    return "Schema 校验失败: 图形推理必须含有 pattern_type 和 rule_description 字段"
                }
            }
            QuestionType.DING_YI_PAN_DUAN -> {
                if (!json.has("definition_text") || !json.has("key_elements")) {
                    return "Schema 校验失败: 定义判断必须含有 definition_text 和 key_elements 字段"
                }
                val keyElements = json.optJSONArray("key_elements") ?: return "Schema 校验失败: key_elements 必须是数组"
                if (keyElements.length() == 0) return "Schema 校验失败: key_elements 不能为空"
                val opts = json.optJSONArray("options_analysis")
                if (opts == null || opts.length() < 4) return "Schema 校验失败: options_analysis 必须是长度不少于 4 的数组"
            }
            QuestionType.LEI_BI_TUI_LI -> {
                if (!json.has("word_pair") || !json.has("relationship_type")) {
                    return "Schema 校验失败: 类比推理必须含有 word_pair 和 relationship_type 字段"
                }
            }
        }

        val correctAns = json.optString("correct_answer", "").trim()
        if (correctAns.isEmpty()) return "业务一致性校验失败: correct_answer 字段值为空"

        // 判断是否为选非题（选错误/不正确/不属于/对应错误的）
        val questionText = json.optString("question", "")
        val askType = json.optString("ask_type", "")
        val isSelectWrong = questionText.contains("不正确") || questionText.contains("错误的是") ||
                questionText.contains("不属于") || questionText.contains("对应错误") ||
                questionText.contains("不恰当") || questionText.contains("不恰当的是") ||
                askType.contains("不属于")

        val opts = json.optJSONArray("options_analysis")
        if (opts != null && opts.length() > 0) {
            var foundAnswerInOpts = false
            var trueCount = 0
            for (i in 0 until opts.length()) {
                val o = opts.optJSONObject(i) ?: continue
                val correct = o.optBoolean("correct", false) || o.optBoolean("matches", false)
                if (correct) trueCount++
                val optName = o.optString("option", "").trim()
                // 只匹配选项标识符（A、B、C、D），避免匹配到选项文本中的字母
                // 支持格式："A"、"A."、"A. ..."、"A、..."
                val optPrefix = optName.takeWhile { it.isLetter() || it == '.' || it == '、' }.trimEnd('.', '、').trim()
                val isMatch = optPrefix.equals(correctAns, ignoreCase = true) ||
                        optName.startsWith("$correctAns.", ignoreCase = true) ||
                        optName.startsWith("$correctAns、", ignoreCase = true) ||
                        optName.startsWith("$correctAns ", ignoreCase = true) ||
                        (optName.length <= 3 && optName.equals(correctAns, ignoreCase = true))
                if (isMatch) {
                    foundAnswerInOpts = true
                    // 选非题：正确答案对应的选项应该是 correct=false（因为它本身是错误的）
                    // 选是题：正确答案对应的选项应该是 correct=true
                    if (isSelectWrong && correct) {
                        return "业务一致性校验失败: 选非题 correct_answer 为 $correctAns，但该选项被标记为正确(correct=true)"
                    }
                    if (!isSelectWrong && !correct) {
                        return "业务一致性校验失败: correct_answer 为 $correctAns，但在 options_analysis 中该选项被标记为错误(correct=false)"
                    }
                }
            }
            if (trueCount == 0 && !isSelectWrong) {
                return "业务一致性校验失败: options_analysis 中没有一个选项被标记为正确(correct/matches=true)"
            }
            // 选非题允许多个选项正确（只有一个是错误的）
            if (trueCount > 1 && !isSelectWrong) {
                return "业务一致性校验失败: options_analysis 中有多个选项被标为正确，单选题应只有一个正确选项"
            }
            // 选非题应该只有一个选项是错误的（correct=false）
            if (isSelectWrong && trueCount != opts.length() - 1) {
                return "业务一致性校验失败: 选非题应该只有一个选项的对应是错误的"
            }
            if (!foundAnswerInOpts) {
                return "业务一致性校验失败: options_analysis 中未能找到与 correct_answer='$correctAns' 匹配的选项前缀"
            }
        }

        if (json.has("diagram_type")) {
            val diagType = json.optString("diagram_type", "")
            when (diagType) {
                "arrow_graph" -> {
                    val nodes = json.optJSONArray("nodes") ?: return "Schema 校验失败: diagram_type 为 arrow_graph 时，必须有 nodes 数组"
                    val edges = json.optJSONArray("edges") ?: return "Schema 校验失败: diagram_type 为 arrow_graph 时，必须有 edges 数组"
                    val nodeIds = mutableSetOf<String>()
                    for (i in 0 until nodes.length()) {
                        val n = nodes.optJSONObject(i) ?: return "Schema 校验失败: nodes 元素必须是对象"
                        val id = n.optString("id", "")
                        if (id.isEmpty()) return "Schema 校验失败: nodes 元素必须含有 id 字段"
                        nodeIds.add(id)
                    }
                    for (i in 0 until edges.length()) {
                        val e = edges.optJSONObject(i) ?: return "Schema 校验失败: edges 元素必须是对象"
                        val from = e.optString("from", "")
                        val to = e.optString("to", "")
                        if (from.isEmpty() || to.isEmpty()) return "Schema 校验失败: edges 必须包含 from 和 to 字段"
                        if (!nodeIds.contains(from)) return "业务一致性校验失败: edges 中的 from='$from' 在 nodes 中不存在"
                        if (!nodeIds.contains(to)) return "业务一致性校验失败: edges 中的 to='$to' 在 nodes 中不存在"
                    }
                }
                "euler_diagram" -> {
                    val circles = json.optJSONArray("circles") ?: return "Schema 校验失败: diagram_type 为 euler_diagram 时，必须有 circles 数组"
                    val relations = json.optJSONArray("relations") ?: return "Schema 校验失败: diagram_type 为 euler_diagram 时，必须有 relations 数组"
                    val circleIds = mutableSetOf<String>()
                    for (i in 0 until circles.length()) {
                        val c = circles.optJSONObject(i) ?: return "Schema 校验失败: circles 元素必须是对象"
                        val id = c.optString("id", "")
                        if (id.isEmpty()) return "Schema 校验失败: circles 元素必须含有 id 字段"
                        circleIds.add(id)
                    }
                    for (i in 0 until relations.length()) {
                        val r = relations.optJSONObject(i) ?: return "Schema 校验失败: relations 元素必须是对象"
                        val between = r.optJSONArray("between") ?: return "Schema 校验失败: relations 必须包含 between 数组"
                        for (j in 0 until between.length()) {
                            val cid = between.optString(j, "")
                            if (!circleIds.contains(cid)) return "业务一致性校验失败: relations.between 中的 circle_id='$cid' 在 circles 中不存在"
                        }
                    }
                }
                "matrix_table" -> {
                    val headers = json.optJSONArray("headers") ?: return "Schema 校验失败: diagram_type 为 matrix_table 时，必须有 headers 数组"
                    val rows = json.optJSONArray("rows") ?: return "Schema 校验失败: diagram_type 为 matrix_table 时，必须有 rows 二维数组"
                    val colCount = headers.length()
                    for (i in 0 until rows.length()) {
                        val r = rows.optJSONArray(i) ?: return "Schema 校验失败: rows 元素必须是数组"
                        if (r.length() != colCount) return "业务一致性校验失败: 第 ${i+1} 行的列数 (${r.length()}) 与 headers 数量 ($colCount) 不匹配"
                    }
                }
            }
        }
        return null
    }

    private fun performJsonValidationAndRepair(
        rawText: String,
        requestId: Long,
        startTime: Long,
        repairAttempts: Int,
        isVision: Boolean
    ) {
        val errorMsg = validateJsonResponse(rawText)
        if (errorMsg == null) {
            Log.i(TAG, "JSON 校验成功！耗时: ${System.currentTimeMillis() - startTime}ms")
            updateResultCard(rawText, isAiResponse = true, onRenderFail = {
                Log.w(TAG, "渲染解析异常，重新发起重试...")
                if (isVision) {
                    retryVisionAnalysis(requestId, startTime)
                } else {
                    retryAiAnalysis(requestId, startTime)
                }
            })
            retryCount.set(0)
        } else {
            Log.w(TAG, "JSON 校验失败: $errorMsg, 尝试智能自愈: $repairAttempts/2")
            if (repairAttempts < 2) {
                mainHandler.post {
                    showLoading("⚡ 发现数据不规范，AI 智能修复中 (${repairAttempts + 1}/2)...")
                }

                val repairPrompt = """
                    你是 JSON 数据格式修复专家，专为公务员解析系统提供 100% 完美的规范数据。
                    大模型之前生成的解析 JSON 未能通过系统校验（存在语法错误、Schema 缺失或业务逻辑矛盾）。
                    
                    你的任务是：
                    1. 仔细阅读【校验错误日志】，准确定位问题所在；
                    2. 参考【原始待修复 JSON】，在保留其推理结论和分析内容的前提下，将 JSON 结构修复为完全合法、格式正确且业务一致的规范 JSON；
                    3. 特别注意：
                       - correct_answer 必须与 options_analysis 中 correct=true (或 matches=true) 的那个 option 字母一致；
                       - options_analysis 必须包含 A, B, C, D 四个选项的分析，且有且仅有一个 correct (或 matches) 为 true；
                       - 如果包含 diagram_type，确保对应的图结构数据一致（例如 edges 中的 from 和 to 必须存在于 nodes 中）；
                    4. 【重要要求】：请直接输出修复后的 JSON 字符串，绝对不要包含任何 ```json 或 ``` 格式包裹，不要包含 any 前导或后随的解释说明文字。
                """.trimIndent()

                val userMsg = """
                    === 校验错误日志 ===
                    $errorMsg
                    
                    === 原始待修复 JSON ===
                    $rawText
                """.trimIndent()

                val activeModel = getActiveModelConfig()
                val baseUrl = activeModel?.baseUrl ?: AppPreferences.getApiBaseUrl(this)
                val apiKey = activeModel?.apiKey ?: AppPreferences.getApiKey(this)
                val model = activeModel?.model ?: AppPreferences.getApiModel(this)
                val apiType = activeModel?.apiType ?: "openai"

                OpenAIApiService.analyzeText(
                    ocrText = "",
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    prompt = repairPrompt,
                    thinking = false,
                    userMessage = userMsg,
                    apiType = apiType,
                    onComplete = { repairedText ->
                        if (currentRequestId != requestId) return@analyzeText
                        Log.d(TAG, "AI 修复响应成功，重新校验...")
                        performJsonValidationAndRepair(repairedText, requestId, startTime, repairAttempts + 1, isVision)
                    },
                    onError = { err ->
                        if (currentRequestId != requestId) return@analyzeText
                        Log.e(TAG, "AI 修复请求失败: $err")
                        if (isVision) {
                            retryVisionAnalysis(requestId, startTime)
                        } else {
                            retryAiAnalysis(requestId, startTime)
                        }
                    }
                )
            } else {
                Log.w(TAG, "已达最大修复次数，重置状态重试整道题...")
                if (isVision) {
                    retryVisionAnalysis(requestId, startTime)
                } else {
                    retryAiAnalysis(requestId, startTime)
                }
            }
        }
    }

    private fun getUniversalAgentPrompt(): String {
        return """
            你是一个顶级公务员行测考试解题专家（智能助教）。
            你的任务是对用户给出的行测题目（可能来自OCR识别，包含部分错别字或格式混乱）进行深度智能分析，并输出完美契合题型的结构化 JSON 解析。

            【⚠️核心工作流指令（非常重要）】
            为了保证解题的权威性和极致准确性，你被赋予了以下本地工具库进行「三层渐进式披露」加载：
            1. `get_solving_skill(question_type, sub_type)`: 获取某大题型下细分子题型的核心解题方法、选择原则与避坑高频陷阱（第二层：按需加载方法论）。
            2. `get_typical_special_rule(term)`: 获取各行测模块在高频专项考点、被定义概念、典型常识误区或公式上的标准解析要素与极限排除法则（第三层：按需加载特定考点深度细则）。

            【📢前置环节说明】：
            系统内置了题库前置检索环节。如果题目已被收录，系统会在本 Prompt 的头部以 `=== 题库已收录此题 ===` 块的形式自动注入经过人工校核 of 官方标准题干、选项、正确答案及官方解析。
            如果存在前置题库数据，代表正确答案已确凿无疑，你必须以该标准题库数据为基准展开分析，且保证最终输出的 `correct_answer` 与题库给出的正确答案完全一致。

            【执行步骤】：
            第一步：分析用户提供的题目文本，提取核心题干，识别它属于行测五大模块中的哪个【大题型】。
            第二步：【调用工具 - 第二层】—— 你必须首先调用 `get_solving_skill` 获取对应细分【小题型】的专属方法。
            第三步：【调用工具 - 第三层】—— 若遇到以下高频特征考点，你必须紧接着调用 `get_typical_special_rule(term)` 调取专属避坑细则与公式手册（严防凭感觉做题）：
                - 如果当前是「定义判断」题，且题干的被定义词属于高频典型词（如“体验式采访”、“隐性饥饿”、“无感审批”、“最大最小准则”等），调用 `get_typical_special_rule(term = "被定义词")`；
                - 如果当前是「类比推理」题，且涉及“鳄鱼/鲸鱼等生物常识判断”（非种属误判）、“9大命名方式”、“物理与化学变化变化判定”或“并列之矛盾与反对区别”，调用 `get_typical_special_rule(term = "非种属误判" 或 "9大命名方式" 或 "物理变化与化学变化" 或 "并列之矛盾与反对")`；
                - 如果当前是「逻辑判断」题，且考查“真假推理矛盾关系”或“翻译推理公式连锁推理”，调用 `get_typical_special_rule(term = "真假矛盾公式表" 或 "翻译推理公式秘籍")`；
                - 如果当前是「逻辑填空」题，且考查“公文时政搭配特点”或“双刃剑/紧箍咒等比喻意境”，调用 `get_typical_special_rule(term = "黄金公文热词搭配" 或 "常考比喻与本体隐喻")`。
            第四步：在工具返回你的专属技巧后，仔细阅读，将这些策略严密运用到题目解析中，对题目和每个选项进行极其专业的剖析。
            第五步：根据题目的最终判定题型，输出对应的结构化 JSON，严禁掺杂 Markdown 代码块包裹以外的任何闲聊文字。

            【输出 JSON 格式规范】
            请根据你识别出的题目类别，严格输出以下 7 种 JSON 结构之一：

            1. [片段阅读] 格式：
            {
              "question_type": "片段阅读",
              "passage_type": "中心理解" | "细节判断" | "下文推断" | "标题拟定",
              "structure_type": ["转折结构", "因果结构", "递进结构", "并列结构", "总分/分总结构"],
              "question": "清洗后的干净题干",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "选项详细理由，指出如何对应原文的对策句或主题词"},
                {"option": "B", "correct": false, "reason": "选项详细错误原因，标明是属于「细节非主旨/无中生有/偷换概念/偏离核心」中的哪种"}
              ],
              "logical_labels": [
                {"label": "关联词/逻辑词", "reason": "该逻辑词在文段行文脉络中的具体承接/转折作用说明"}
              ],
              "analysis": "一句话核心秒杀解析",
              "keywords": ["关键词1", "关键词2"]
            }

            2. [逻辑填空] 格式（用 " ________ " 作为空格占位符）：
            {
              "question_type": "逻辑填空",
              "skill_tags": ["词义辨析", "搭配用法", "成语填空"],
              "question": "含有 ________ 的完整题干",
              "core_meaning": "文段的核心主旨意思",
              "breakthrough": "核心破题点（如解释性关系或反对关系线索）",
              "correct_answer": "A",
              "blanks_analysis": [
                {
                  "position": "空格1",
                  "context_hint": "该空的语境线索或修饰关系",
                  "answer": "正确填入的词",
                  "summary": "该空的选择小结",
                  "candidates": [
                    {"word": "词语A", "correct": true, "dimension": "词义轻重/搭配对象", "reason": "正确理由"},
                    {"word": "词语B", "correct": false, "dimension": "词义轻重/褒贬色彩", "reason": "排除理由"}
                  ]
                }
              ],
              "verification": "带回验证句意是否顺畅",
              "accumulation": "本题相关的成语或核心实词积累",
              "keywords": ["关键词1", "关键词2"]
            }

            3. [语句表达] 格式：
            {
              "question_type": "语句表达",
              "question_subtype": "语句填入题" | "语句排序题",
              "question": "干净题干",
              "correct_answer": "A",
              "structure_analysis": "文段整体行文脉络与逻辑层级分析",
              "key_clues": ["排序或填入的绝对捆绑线索1", "绝对捆绑线索2"],
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "正确理由"},
                {"option": "B", "correct": false, "reason": "排除理由"}
              ],
              "pitfall": "容易被语感误导的经典易错点",
              "keywords": ["关键词1", "关键词2"]
            }

            4. [定义判断] 格式：
            {
              "question_type": "定义判断",
              "ask_type": "属于" | "不属于",
              "definition_text": "定义原文",
              "key_elements": ["核心要素1(如主体)", "核心要素2(如手段)", "核心要素3(如结果)"],
              "question": "题干设问和选项前情",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "完全契合所有关键定义要素的详细剖析"},
                {"option": "B", "correct": false, "reason": "缺少哪项核心要素的详细说明"}
              ],
              "analysis": "一句话判定精要",
              "keywords": ["关键词1", "关键词2"]
            }

            5. [类比推理] 格式：
            {
              "question_type": "类比推理",
              "word_pair": "题干词组(如：微风：狂风)",
              "relationship_type": "核心关系(如：语义关系-程度递进)",
              "relationship_analysis": "题干两个词的深层逻辑关系分析",
              "sentence": "题干词组造句验证",
              "technique": {
                "level1": "一级逻辑关系",
                "level2": "二级辨析关系",
                "key_point": "区分干扰项的核判断点"
              },
              "options_table": [
                {"option": "A. 词1：词2", "relationship": "选项关系", "match": true, "reason": "比对说明"},
                {"option": "B. 词1：词2", "relationship": "选项关系", "match": false, "reason": "比对说明"}
              ],
              "secondary_analysis": "详细的一二级二级辨析对比过程",
              "correct_answer": "A",
              "pitfall": "经典易错方向陷阱",
              "summary": "解此类题的核心总结",
              "analysis": "秒杀解析",
              "keywords": ["关键词1", "关键词2"]
            }

            6. [逻辑判断] 格式：
            {
              "question_type": "逻辑判断",
              "reasoning_type": "论证削弱" | "论证加强" | "翻译推理" | "真假推理" | "分析推理",
              "argument_structure": "论点与论据的推出式结构梳理",
              "diagram_type": "逻辑图描述(可选)",
              "logical_chain": [
                {"step": 1, "premise": "前提/论据", "deduction": "具体推导/强化/削弱逻辑"}
              ],
              "question": "干净题干",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "如何起到最强加强/削弱/翻译契合作用"},
                {"option": "B", "correct": false, "reason": "无关项/加强程度过弱/诉诸无知等排除原因"}
              ],
              "analysis": "核心推导小结",
              "keywords": ["关键词1", "keywords2"]
            }

            7. [图形推理] 格式：
            {
              "question_type": "图形推理",
              "pattern_type": "位置变化" | "样式变化" | "数量规律" | "属性规律" | "空间重构",
              "rule_description": "图形整体核心变化规律精要描述",
              "visual_analysis": "视觉细节剖析",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "规律完全契合"},
                {"option": "B", "correct": false, "reason": "不符合什么移动步长或翻转规律"}
              ],
              "analysis": "秒杀解析",
              "keywords": ["关键词1", "关键词2"]
            }
        """.trimIndent()
    }

    private fun performDictOcrSearch(ocrText: String) {
        val cleanedText = ocrText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        // 1. 本地题库库检索原题
        val bankMatch = com.example.aiassistant.questionbank.QuestionBankManager.search(cleanedText)
        val wordsToSearch = mutableListOf<String>()

        if (bankMatch != null) {
            Log.i(TAG, "词库识图模式 - 题库命中！ID=${bankMatch.id}, 选项数=${bankMatch.options.size}")
            for ((i, opt) in bankMatch.options.withIndex()) {
                Log.i(TAG, "  选项$i: text='${opt.text}'")
            }
            val extracted = extractCleanWordsFromOptions(bankMatch.options)
            Log.i(TAG, "词库识图模式 - 从选项中提取到 ${extracted.size} 个词: $extracted")
            wordsToSearch.addAll(extracted)
        } else {
            Log.i(TAG, "词库识图模式 - 题库未命中，执行OCR文本选项词汇智能提取")
            val ocrOptions = extractCleanWordsFromOcrOptions(cleanedText)
            if (ocrOptions.isNotEmpty()) {
                Log.i(TAG, "成功从 OCR 文本中提取到选项词汇: $ocrOptions")
                wordsToSearch.addAll(ocrOptions)
            } else {
                Log.i(TAG, "OCR 文本中无明显选项格式，走智能滑动窗口文本扫描")
                wordsToSearch.addAll(extractCandidatesFromText(cleanedText))
            }
        }

        // 2. 内存词库匹配
        val finalMatchList = mutableListOf<com.example.aiassistant.dictionary.DictItem>()
        for (word in wordsToSearch) {
            val dictRes = com.example.aiassistant.dictionary.DictionaryManager.search(word)
            if (!dictRes.isEmpty) {
                // 优先挑选精确完全相等词条
                val matchedItem = dictRes.items.firstOrNull { item ->
                    val itemWord = when (item) {
                        is com.example.aiassistant.dictionary.DictItem.IdiomItem -> item.data.word
                        is com.example.aiassistant.dictionary.DictItem.WordItem -> item.data.word
                        is com.example.aiassistant.dictionary.DictItem.CiItem -> item.data.ci
                        is com.example.aiassistant.dictionary.DictItem.XiehouyuItem -> item.data.riddle
                    }
                    itemWord == word
                }
                if (matchedItem != null) {
                    finalMatchList.add(matchedItem)
                }
            }
        }

        // 3. 去重
        val distinctList = finalMatchList.distinctBy { item ->
            when (item) {
                is com.example.aiassistant.dictionary.DictItem.IdiomItem -> item.data.word
                is com.example.aiassistant.dictionary.DictItem.WordItem -> item.data.word
                is com.example.aiassistant.dictionary.DictItem.CiItem -> item.data.ci
                is com.example.aiassistant.dictionary.DictItem.XiehouyuItem -> item.data.riddle
            }
        }

        Log.i(TAG, "词库匹配完成，搜索词数=${wordsToSearch.size}，命中词汇量=${distinctList.size}")

        // 4. 显示浮空卡片结果
        mainHandler.post {
            isCapturing = false
            cancelCaptureTimeout()
            showDictOcrResultCard(distinctList, cleanedText)
        }
    }

    private fun extractCleanWordsFromOptions(options: List<com.example.aiassistant.questionbank.QuestionOption>): List<String> {
        val list = mutableListOf<String>()
        for (opt in options) {
            val rawOpt = opt.text.trim()
            if (rawOpt.isNotBlank()) {
                // 正则过滤 [A-D] 后可能伴随的英文句点、中文顿号、空格等干扰
                val cleanOpt = rawOpt.replaceFirst(Regex("^[A-D][\\s\\.\\s、]*"), "").trim()
                if (cleanOpt.isNotBlank()) {
                    // 按空格/标点切分，提取所有 2-10 字的中文词
                    val parts = cleanOpt.split(Regex("[\\s\\p{Punct}、\\u3001]+"))
                    for (p in parts) {
                        val trimP = p.trim()
                        if (trimP.length in 2..10 && trimP.matches(Regex("[\\u4e00-\\u9fa5]+"))) {
                            list.add(trimP)
                        }
                    }
                }
            }
        }
        Log.i(TAG, "选项词汇提取结果: ${list.size} 个词 <- ${options.map { it.text }}")
        return list
    }

    private fun extractCleanWordsFromOcrOptions(text: String): List<String> {
        val candidates = mutableListOf<String>()
        // 匹配 A/B/C/D(包含大小写) 开头，后跟中文和常见标点的选项内容
        val optionRegex = Regex("[A-D|a-d][\\s\\.\\s、\\s.-]*([\\u4e00-\\u9fa5\\s\\p{Punct}、\\u3001]+)")
        val matches = optionRegex.findAll(text)
        
        for (match in matches) {
            val content = match.groups[1]?.value?.trim() ?: continue
            if (content.isNotBlank()) {
                val parts = content.split(Regex("[\\s\\p{Punct}、\\u3001]+"))
                for (p in parts) {
                    val trimP = p.trim()
                    if (trimP.length in 2..10) {
                        candidates.add(trimP)
                    }
                }
            }
        }
        return candidates.distinct()
    }

    private fun extractCandidatesFromText(text: String): List<String> {
        val stopWords = setOf(
            "下列", "关于", "符合", "正确", "错误", "一项", "选择", "选项", "根据", "文中", 
            "划横", "使用", "不当", "填入", "括号", "最恰", "成语", "词语", "意思", "解释", 
            "说明", "表达", "指出", "部分", "内容", "什么", "怎么", "哪里", "哪个", "能够",
            "可以", "可能", "应该", "为了", "因此", "所以", "但是", "然而", "如果", "进行"
        )
        val words = mutableListOf<String>()
        val blocks = text.split(Regex("[^\\u4e00-\\u9fa5]+"))
        for (block in blocks) {
            if (block.isBlank()) continue
            if (block.length in 2..4) {
                if (block !in stopWords) {
                    words.add(block)
                }
            } else if (block.length > 4) {
                // 用滑动窗口切片出所有可能成语或词组片段
                for (len in 4 downTo 2) {
                    for (i in 0..block.length - len) {
                        val sub = block.substring(i, i + len)
                        if (sub !in stopWords) {
                            words.add(sub)
                        }
                    }
                }
            }
        }
        return words.distinct().take(30)
    }
}
