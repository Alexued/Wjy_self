package com.example.aiassistant

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 通知管理（扩展函数）
 */

internal fun ScreenCaptureService.createNotificationChannel() {
    val channel = android.app.NotificationChannel(
        ScreenCaptureService.CHANNEL_ID, ScreenCaptureService.CHANNEL_NAME,
        NotificationManager.IMPORTANCE_LOW
    ).apply { description = "AI伴学录屏服务通知渠道" }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

internal fun ScreenCaptureService.buildNotification(): Notification {
    val pendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, ScreenCaptureService.CHANNEL_ID)
        .setContentTitle("AI伴学运行中")
        .setContentText("点击悬浮球可截图并获取 AI 解析")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .build()
}

/** 更新通知为正常状态 */
internal fun ScreenCaptureService.updateNotification() {
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(ScreenCaptureService.NOTIFICATION_ID, buildNotification())
}

/** 更新通知为录屏失效状态，引导用户重新开启（点击通知自动进入授权流程） */
internal fun ScreenCaptureService.updateNotificationForFailure() {
    val pendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ScreenCaptureService.EXTRA_NEED_RESTART, true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(this, ScreenCaptureService.CHANNEL_ID)
        .setContentTitle("AI伴学 - 录屏已暂停")
        .setContentText("点击通知重新开启悬浮球录屏功能")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(ScreenCaptureService.NOTIFICATION_ID, notification)
}
