package com.example.aiassistant.pomodoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.MainActivity
import com.example.aiassistant.R

/**
 * 番茄钟应用拦截服务
 * 根据当前专注任务的白名单，拦截不在白名单中的应用
 */
class AppBlockerService : Service() {

    companion object {
        private const val TAG = "AppBlocker"
        const val CHANNEL_ID = "app_blocker_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.example.aiassistant.STOP_APP_BLOCKER"
        const val POLL_INTERVAL_MS = 800L

        @Volatile var isServiceRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AppBlockerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppBlockerService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    @Volatile private var isOverlayShowing = false
    @Volatile private var blockedPackage: String? = null

    private var blockerThread: Thread? = null
    @Volatile private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val ignoredPackages by lazy {
        // 动态检测：通过 PackageManager 动态检索当前设备上注册为系统桌面的所有包名，完美兼容所有定制及第三方 Launcher
        val dynamicLaunchers = try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val pm = this@AppBlockerService.packageManager
            // 兼容高低版本 PackageManager 查询
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve launcher packages dynamically", e)
            emptySet()
        }

        setOf(
            this@AppBlockerService.packageName,
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.bbk.launcher2",  // VIVO OriginOS 真实桌面包名，双重预防保障！
            "com.samsung.android.app.launcher",
            "com.sec.android.app.launcher",
        ) + dynamicLaunchers + AppPreferences.SYSTEM_DEFAULT_PACKAGES
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        
        // 针对 Android 14+ (API 34) 及以上版本，显式声明前台服务类型以符合 targetSdkVersion 34+ 规范，杜绝系统崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // 申请 WakeLock 锁，防止后台冷冻（cgroup freeze）
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIAssistant:AppBlockerWakeLock").apply {
                acquire(30 * 60 * 1000L) // 每次申请 30 分钟锁定
            }
            Log.d(TAG, "WakeLock acquired successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
        
        isRunning = true
        blockerThread = Thread {
            Log.d(TAG, "Blocker background thread started.")
            while (isRunning) {
                try {
                    checkForegroundApp()
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in blocker thread", e)
                }
            }
            Log.d(TAG, "Blocker background thread stopped.")
        }.apply {
            priority = Thread.MAX_PRIORITY // 高优先级避免被抢占
            start()
        }
        
        Log.d(TAG, "Service created. UsageStats state = enabled, overlay=${Settings.canDrawOverlays(this)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        isRunning = false
        blockerThread?.interrupt()
        blockerThread = null

        // 释放 WakeLock 锁
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released.")
            } catch (_: Exception) {}
        }
        wakeLock = null

        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkForegroundApp() {
        if (!AppPreferences.isAppBlockerEnabled(this)) return

        val currentPackage = getForegroundPackage() ?: return
        Log.d(TAG, "FG: $currentPackage | overlay=$isOverlayShowing")

        // 1. 系统UI、桌面、自身——放行并自动消除遮罩
        if (currentPackage in ignoredPackages ||
            currentPackage.contains("packageinstaller", ignoreCase = true) ||
            currentPackage.contains("permissioncontroller", ignoreCase = true)) {
            if (isOverlayShowing) {
                Log.d(TAG, "Auto-dismiss overlay (System/Self): $currentPackage")
                removeOverlay()
            }
            return
        }

        // 2. 遮罩层正在显示时的双向感知
        if (isOverlayShowing) {
            val currentTask = AppPreferences.getAppBlockerCurrentTask(this)
            val whitelist = AppPreferences.getTaskWhitelist(this, currentTask)
            
            if (whitelist.contains(currentPackage)) {
                // 如果切换回了白名单应用，自动消除遮罩
                Log.d(TAG, "Auto-dismiss overlay (Whitelisted): $currentPackage")
                removeOverlay()
                return
            }

            if (currentPackage != blockedPackage) {
                // 如果切换到了另一个被拦截应用，更新遮罩
                Log.d(TAG, "UPDATE overlay (New Blocked App): $currentPackage")
                blockedPackage = currentPackage
                showOverlay(currentPackage)
            }
            return
        }

        // 3. 遮罩层未显示时，检查白名单
        val currentTask = AppPreferences.getAppBlockerCurrentTask(this)
        val whitelist = AppPreferences.getTaskWhitelist(this, currentTask)

        if (!whitelist.contains(currentPackage)) {
            Log.d(TAG, ">>> BLOCK $currentPackage <<<")
            blockedPackage = currentPackage
            showOverlay(currentPackage)
        }
    }

    /**
     * 获取当前前台应用包名。
     * 策略：最近 5 分钟事件流的最后一个真实 ACTIVITY_RESUMED 事件（100% 排除后台预热干扰） ➡️ 最近 5 分钟使用统计最大时间戳兜底
     */
    @Suppress("DEPRECATION")
    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        // 策略1: 从最近 5 分钟的系统事件流中寻找最后一个 ACTIVITY_RESUMED 的包名。
        // 这代表用户最近一次真正拉起的 Activity，100% 准确，绝无桌面背景激活预热的干扰。
        val events = usm.queryEvents(now - 300_000, now)
        if (events != null) {
            var lastResumed: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastResumed = event.packageName
                }
            }
            if (lastResumed != null) {
                return lastResumed
            }
        }

        // 策略2: 如果事件流未获取到，使用最近 5 分钟的使用统计中 lastTimeUsed 最大的应用进行兜底
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 300_000, now)
        if (!stats.isNullOrEmpty()) {
            val top = stats.maxByOrNull { it.lastTimeUsed }
            if (top != null) {
                return top.packageName
            }
        }

        return null
    }

    private fun showOverlay(blockedPkg: String) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(this)) return@post

            // 同步移除旧遮罩，不能使用异步 Handler 延迟，以防止多个 View 重叠或移除迟缓造成的时序错乱和频闪
            removeOverlaySynchronous()

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val windowFlags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = WindowParamsCreator.createLayoutParams(windowFlags)

            val view = LayoutInflater.from(this).inflate(R.layout.layout_app_blocker_overlay, null)

            val tvBlockedApp = view.findViewById<TextView>(R.id.tv_blocked_app)
            try {
                val appInfo = packageManager.getApplicationInfo(blockedPkg, 0)
                val appName = packageManager.getApplicationLabel(appInfo)
                tvBlockedApp.text = "\"$appName\" 不在白名单中"
            } catch (_: Exception) {
                tvBlockedApp.text = "该应用不在白名单中"
            }

            view.findViewById<View>(R.id.btn_back_to_pomodoro).setOnClickListener {
                removeOverlay()
                blockedPackage = null
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("open_pomodoro", true)
                })
            }

            view.findViewById<View>(R.id.btn_stop_focus).setOnClickListener {
                removeOverlay()
                blockedPackage = null
                sendBroadcast(Intent("com.example.aiassistant.STOP_FOCUS").apply { setPackage(packageName) })
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("open_pomodoro", true)
                })
            }

            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

            try {
                wm.addView(view, params)
                overlayView = view
                isOverlayShowing = true
                Log.d(TAG, "Overlay SHOWN for $blockedPkg")
            } catch (e: Exception) {
                Log.e(TAG, "Overlay addView FAILED", e)
            }
        }
    }

    private fun removeOverlay() {
        mainHandler.post {
            removeOverlaySynchronous()
        }
    }

    private fun removeOverlaySynchronous() {
        if (!isOverlayShowing) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView?.let { wm.removeView(it) }
            Log.d(TAG, "Overlay REMOVED synchronously")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay removeView synchronously FAILED", e)
        }
        overlayView = null
        isOverlayShowing = false
    }

    private object WindowParamsCreator {
        fun createLayoutParams(windowFlags: Int): WindowManager.LayoutParams {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                windowFlags,
                PixelFormat.TRANSLUCENT
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "应用拦截",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "番茄钟专注模式应用拦截"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_pomodoro", true)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AppBlockerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentTask = AppPreferences.getAppBlockerCurrentTask(this)
        val whitelist = AppPreferences.getTaskWhitelist(this, currentTask)
        val contentText = if (currentTask.isNotEmpty()) {
            "任务「$currentTask」· 白名单 ${whitelist.size} 个应用"
        } else {
            "白名单 ${whitelist.size} 个应用"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pomodoro)
            .setContentTitle("专注模式运行中")
            .setContentText(contentText)
            .setContentIntent(pendingOpen)
            .addAction(0, "停止拦截", pendingStop)
            .setOngoing(true)
            .build()
    }
}
