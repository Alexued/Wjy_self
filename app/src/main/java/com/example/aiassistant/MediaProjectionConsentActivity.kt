package com.example.aiassistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * 透明 Activity，仅用于弹出 MediaProjection 系统授权弹窗
 * 从悬浮球点击触发，授权后直接将结果发回 ScreenCaptureService
 */
class MediaProjectionConsentActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 1001
        const val ACTION_CONSENT_DENIED = "com.example.aiassistant.CONSENT_DENIED"
        /** 防止同时弹出多个授权页面 */
        @Volatile
        var isShowing = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isShowing) {
            finish()
            return
        }
        isShowing = true
        // 无 UI，直接请求授权
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // 授权成功，发给服务
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
                startForegroundService(serviceIntent)
                AppPreferences.setFloatEnabled(this, true)
            } else {
                Toast.makeText(this, "需要录屏权限才能截图", Toast.LENGTH_SHORT).show()
                // 用户拒绝授权，通知服务重置标记
                sendBroadcast(Intent(ACTION_CONSENT_DENIED))
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing = false
    }
}
