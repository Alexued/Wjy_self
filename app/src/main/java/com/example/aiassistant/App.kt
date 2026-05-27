package com.example.aiassistant

import android.app.Application

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 在最早时机禁用 OpenMP 亲和性，防止 PaddleOCR 在 Android 16 上崩溃
        try {
            android.system.Os.setenv("KMP_AFFINITY", "none", true)
            android.system.Os.setenv("OMP_NUM_THREADS", "1", true)
            android.system.Os.setenv("OMP_PROC_BIND", "false", true)
            android.system.Os.setenv("GOMP_CPU_AFFINITY", "0", true)
            android.system.Os.setenv("OPENCV_FOR_THREADS_NUM", "1", true)
            android.util.Log.i("App", "OpenMP env vars set: KMP_AFFINITY=${android.system.Os.getenv("KMP_AFFINITY")}, OMP_NUM_THREADS=${android.system.Os.getenv("OMP_NUM_THREADS")}")
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to set OpenMP env vars: ${e.message}")
        }

        // 应用启动即初始化题库，首页进入“静心研习”时可立即刷新显示
        try {
            com.example.aiassistant.questionbank.QuestionBankManager.init(this)
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to init QuestionBankManager: ${e.message}")
        }

        // 初始化 Skills 工具注册
        try {
            com.example.aiassistant.skills.BuiltInTools.registerAll()
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to register BuiltInTools: ${e.message}")
        }
    }
}
