package com.example.aiassistant

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class FloatBallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val running = isServiceRunning()
        android.util.Log.d("FloatBallTile", "onClick: Clicked QS Tile! isServiceRunning = $running")
        if (running) {
            // 正在运行，点击关闭
            stopFloatService()
        } else {
            // 未运行，点击开启
            if (!Settings.canDrawOverlays(this)) {
                android.util.Log.w("FloatBallTile", "onClick: Missing SYSTEM_ALERT_WINDOW permission, directing user to settings.")
                // 没有悬浮窗权限，引导去授权
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                Toast.makeText(this, "需要授予悬浮窗权限才能开启陪陪刷", Toast.LENGTH_LONG).show()
            } else {
                android.util.Log.d("FloatBallTile", "onClick: Has overlay permission, launching MediaProjectionConsentActivity transparently...")
                // 已有悬浮窗权限，跳转到透明中转 Activity 自动原地触发授权与开启流程
                val intent = Intent(this, MediaProjectionConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this, 1, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val cls = ScreenCaptureService::class.java
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE)
            ?.any { it.service.className == cls.name } == true
    }

    private fun stopFloatService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        AppPreferences.setFloatEnabled(this, false)
        updateTileState()
        Toast.makeText(this, "陪陪刷已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val running = isServiceRunning()
        if (running) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "陪陪刷: 开启"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "已运行"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "陪陪刷: 关闭"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "未开启"
            }
        }
        tile.updateTile()
    }
}
