package com.example.aiassistant

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * 权限流程管理（扩展函数）
 */

/**
 * 悬浮球开启流程：
 * 1. 检查悬浮窗权限 → 2. 请求录屏权限 → 3. 启动服务
 */
internal fun MainActivity.startPermissionFlow() {
    if (!Settings.canDrawOverlays(this)) {
        requestOverlayPermission()
    } else {
        requestScreenCapturePermission()
    }
}

internal fun MainActivity.requestOverlayPermission() {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )
    overlayPermissionLauncher.launch(intent)
}

internal fun MainActivity.requestScreenCapturePermission() {
    val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
    screenCaptureLauncher.launch(captureIntent)
}
