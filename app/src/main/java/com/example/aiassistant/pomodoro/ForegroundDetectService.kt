package com.example.aiassistant.pomodoro

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 前台应用检测服务（无障碍服务）
 * 监听 TYPE_WINDOW_STATE_CHANGED 事件，实时记录当前前台应用包名。
 * 比 UsageStatsManager 更可靠，尤其在 MIUI/HyperOS 上。
 */
class ForegroundDetectService : AccessibilityService() {

    companion object {
        private const val TAG = "AppBlocker"

        @Volatile
        var currentForegroundPackage: String? = null
            private set

        @Volatile
        var instance: ForegroundDetectService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "ForegroundDetectService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg.isNotEmpty() && pkg != packageName) {
                currentForegroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        currentForegroundPackage = null
        super.onDestroy()
    }
}
