package com.example.aiassistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * 透明 Activity，仅用于弹出 MediaProjection 系统授权弹窗
 * 从悬浮球或下拉磁贴点击触发，授权后直接将结果发回 ScreenCaptureService
 */
class MediaProjectionConsentActivity : Activity() {

    companion object {
        private const val TAG = "ConsentActivity"
        private const val REQUEST_CODE = 1001
        const val ACTION_CONSENT_DENIED = "com.example.aiassistant.CONSENT_DENIED"
        
        @Volatile
        var isShowing = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Called, isShowing = $isShowing")
        if (isShowing) {
            Log.w(TAG, "onCreate: Already showing, finishing immediate.")
            finish()
            return
        }
        isShowing = true
        
        // 延迟 150ms 执行，确保透明 Activity Window 已经成功挂载并获得焦点，
        // 从而完美绕过 MIUI/HyperOS 等国内系统对于“后台或无焦点透明 Activity 直接拉起系统敏感授权框”的安全拦截！
        handler.postDelayed({
            try {
                Log.d(TAG, "Executing createScreenCaptureIntent now...")
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaProjection consent dialog", e)
                Toast.makeText(this, "无法启动录屏授权: ${e.message}", Toast.LENGTH_LONG).show()
                isShowing = false
                finish()
            }
        }, 150)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode = $requestCode, resultCode = $resultCode (OK = $RESULT_OK), hasData = ${data != null}")
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "onActivityResult: Consent GRANTED! Starting ScreenCaptureService...")
                // 授权成功，发给服务
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
                try {
                    startForegroundService(serviceIntent)
                    AppPreferences.setFloatEnabled(this, true)
                    Log.d(TAG, "ScreenCaptureService started successfully and FloatEnabled preference set to true.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to startForegroundService", e)
                    Toast.makeText(this, "启动悬浮窗服务失败: ${e.message}", Toast.LENGTH_LONG).show()
                }

                // 立即刷新快捷控制中心磁贴状态
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try {
                        android.service.quicksettings.TileService.requestListeningState(
                            this, android.content.ComponentName(this, FloatBallTileService::class.java)
                        )
                        Log.d(TAG, "Requested Quick Setting Tile state refresh.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request tile listening state refresh", e)
                    }
                }
            } else {
                Log.w(TAG, "onActivityResult: Consent DENIED by user or cancelled by system.")
                Toast.makeText(this, "需要录屏权限才能截图", Toast.LENGTH_SHORT).show()
                // 用户拒绝授权，通知服务重置标记
                sendBroadcast(Intent(ACTION_CONSENT_DENIED))
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: ConsentActivity finishing.")
        isShowing = false
        handler.removeCallbacksAndMessages(null)
    }
}
