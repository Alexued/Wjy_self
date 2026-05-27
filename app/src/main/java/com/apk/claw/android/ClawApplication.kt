package com.apk.claw.android

import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.base.BaseApp
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.blankj.utilcode.util.NetworkUtils

/**
 * Application 入口
 */

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        XLog.setDEBUG(BuildConfig.DEBUG)
        registerNetworkCallback()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]
        KVUtils.init(this)
        ToolRegistry.getInstance().registerAllTools(ToolRegistry.DeviceType.MOBILE)
        XLog.e(TAG, "ClawApplication initialized, tools registered: ${ToolRegistry.getInstance().getAllTools().size}")

        // 网络日志输出到文件，跟随首页 Debug 模式开关。
        DefaultAgentService.FILE_LOGGING_ENABLED = KVUtils.isLocalTaskDebugEnabled()
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // 轻量初始化（主线程）
        appViewModelInstance.initCommon()
        if (!ForegroundService.isRunning()) {
            val started = ForegroundService.start(this)
            if (!started) {
                XLog.e(TAG, "ForegroundService start failed: notification permission not granted")
            }
        }

        Thread({
            if (KVUtils.hasLlmConfig()) {
                appViewModelInstance.initAgent()
                appViewModelInstance.afterInit()
            }
        }, "app-async-init").start()
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    /**
     * 监听网络恢复，自动重新初始化通道。
     * 解决开机自启动时无网络导致通道初始化失败的问题，以及运行中断网恢复后通道重连。
     */
    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        XLog.i(TAG, "网络恢复(${networkType?.name})，检查并重连断开的通道")
                        ChannelManager.reconnectIfNeeded()
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "网络断开")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }

}
