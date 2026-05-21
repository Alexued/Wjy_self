package com.example.aiassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.aiassistant.fragment.AiModelFragment
import com.example.aiassistant.fragment.HomeFragment
import com.example.aiassistant.fragment.OcrModelFragment
import com.example.aiassistant.fragment.PromptManageFragment
import com.example.aiassistant.knowledge.KnowledgeCardFragment
import com.example.aiassistant.knowledge.KnowledgeCardManager
import com.example.aiassistant.plan.PlanFragment
import com.example.aiassistant.plan.PlanManager
import com.example.aiassistant.pomodoro.PomodoroFragment
import com.example.aiassistant.pomodoro.PomodoroManager
import com.example.aiassistant.dictionary.DictionaryManager
import com.example.aiassistant.questionbank.QuestionBankManager
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity(), HomeFragment.ServiceControlListener {

    internal lateinit var mediaProjectionManager: MediaProjectionManager

    private var homeFragment: HomeFragment? = null
    private var aiModelFragment: AiModelFragment? = null
    private var ocrModelFragment: OcrModelFragment? = null
    private var promptFragment: PromptManageFragment? = null
    private var knowledgeFragment: KnowledgeCardFragment? = null
    private var planFragment: PlanFragment? = null
    private var pomodoroFragment: PomodoroFragment? = null
    private var activeFragment: Fragment? = null

    /** 悬浮窗权限 → 返回后继续录屏权限 */
    internal val overlayPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能开启悬浮球", Toast.LENGTH_SHORT).show()
                homeFragment?.resetFloatSwitch()
            }
        }

    /** 录屏授权 → 成功后启动服务 */
    internal val screenCaptureLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startScreenCaptureService(result.resultCode, result.data!!)
                homeFragment?.updateFloatServiceStatus(true)
            } else {
                Toast.makeText(this, "需要录屏权限才能使用截图功能", Toast.LENGTH_SHORT).show()
                homeFragment?.resetFloatSwitch()
            }
        }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_new)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 用户主动打开应用时，清除 OCR 崩溃标记（允许重试本地 OCR）
        getSharedPreferences("ai_assistant_prefs", MODE_PRIVATE)
            .edit().putBoolean("ocr_last_call_crashed", false).apply()

        // 初始化老师系统
        TeacherManager.init(this)
        // 初始化 AI 模型
        ModelManager.init(this)
        // 初始化题库（后台加载）
        QuestionBankManager.init(this)
        // 初始化词典（后台加载）
        DictionaryManager.init(this)
        // 初始化知识卡片
        KnowledgeCardManager.init(this)
        // 初始化计划表
        PlanManager.init(this)
        // 初始化番茄钟
        PomodoroManager.init(this)

        setupBottomNav()

        // 注册 moveToBack 广播（AppBlockerService 在 overlay 被 Activity 盖住时发送）
        val moveBackFilter = IntentFilter("com.example.aiassistant.MOVE_TO_BACK")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(moveToBackReceiver, moveBackFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(moveToBackReceiver, moveBackFilter)
        }

        // 处理启动 Intent（通知点击 / 悬浮球跳转）
        if (intent != null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(ScreenCaptureService.EXTRA_NEED_RESTART, false) == true) {
            startPermissionFlow()
        }
        // 处理从悬浮球跳转过来的重新授权请求（锁屏后 MediaProjection 失效）
        if (intent.getBooleanExtra("request_media_projection", false)) {
            // 服务仍在运行（SPECIAL_USE 类型），只需重新获取 MediaProjection
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission()
            } else {
                startPermissionFlow()
            }
        }
        // 处理快捷磁贴点击自动开启服务
        if (intent.getBooleanExtra("start_float_service_auto", false)) {
            if (Settings.canDrawOverlays(this)) {
                if (!isServiceRunning()) {
                    startPermissionFlow()
                }
            } else {
                startPermissionFlow()
            }
        }
    }

    // ── 底部导航 ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showFragment(getOrCreateHomeFragment()); true }
                R.id.nav_knowledge -> { showFragment(getOrCreateKnowledgeFragment()); true }
                R.id.nav_plan -> { showFragment(getOrCreatePlanFragment()); true }
                R.id.nav_pomodoro -> { showFragment(getOrCreatePomodoroFragment()); true }
                R.id.nav_ai_model -> { showFragment(getOrCreateAiModelFragment()); true }
                else -> false
            }
        }
        // 默认选中主页
        bottomNav.selectedItemId = R.id.nav_home
    }

    internal fun showFragment(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()

        // 隐藏所有已添加的 fragment
        for (f in supportFragmentManager.fragments) {
            if (f != fragment) {
                transaction.hide(f)
            }
        }

        if (fragment.isAdded) {
            transaction.show(fragment)
        } else {
            transaction.add(R.id.fragment_container, fragment, fragmentTag(fragment))
        }
        activeFragment = fragment
        transaction.commit()
    }

    private fun fragmentTag(f: Fragment): String = when (f) {
        is HomeFragment -> "home"
        is AiModelFragment -> "ai_model"
        is OcrModelFragment -> "ocr_model"
        is PromptManageFragment -> "prompt"
        is KnowledgeCardFragment -> "knowledge"
        is PlanFragment -> "plan"
        is PomodoroFragment -> "pomodoro"
        else -> f.javaClass.simpleName
    }

    private fun getOrCreateHomeFragment(): Fragment {
        if (homeFragment == null) {
            homeFragment = supportFragmentManager.findFragmentByTag("home") as? HomeFragment ?: HomeFragment()
        }
        return homeFragment!!
    }

    private fun getOrCreateAiModelFragment(): Fragment {
        if (aiModelFragment == null) {
            aiModelFragment = supportFragmentManager.findFragmentByTag("ai_model") as? AiModelFragment ?: AiModelFragment()
        }
        return aiModelFragment!!
    }

    private fun getOrCreateOcrModelFragment(): Fragment {
        if (ocrModelFragment == null) {
            ocrModelFragment = supportFragmentManager.findFragmentByTag("ocr_model") as? OcrModelFragment ?: OcrModelFragment()
        }
        return ocrModelFragment!!
    }

    internal fun getOrCreatePromptFragment(): Fragment {
        if (promptFragment == null) {
            promptFragment = supportFragmentManager.findFragmentByTag("prompt") as? PromptManageFragment ?: PromptManageFragment()
        }
        return promptFragment!!
    }

    private fun getOrCreateKnowledgeFragment(): Fragment {
        if (knowledgeFragment == null) {
            knowledgeFragment = supportFragmentManager.findFragmentByTag("knowledge") as? KnowledgeCardFragment ?: KnowledgeCardFragment()
        }
        return knowledgeFragment!!
    }

    private fun getOrCreatePlanFragment(): Fragment {
        if (planFragment == null) {
            planFragment = supportFragmentManager.findFragmentByTag("plan") as? PlanFragment ?: PlanFragment()
        }
        return planFragment!!
    }

    private fun getOrCreatePomodoroFragment(): Fragment {
        if (pomodoroFragment == null) {
            pomodoroFragment = supportFragmentManager.findFragmentByTag("pomodoro") as? PomodoroFragment ?: PomodoroFragment()
        }
        return pomodoroFragment!!
    }

    // ── ServiceControlListener 实现 ───────────────────────────────────

    override fun onStartServiceRequested() {
        startPermissionFlow()
    }

    override fun onStopServiceRequested() {
        stopScreenCaptureService()
    }

    override fun isServiceRunning(): Boolean =
        AppPreferences.isFloatEnabled(this) &&
        com.example.aiassistant.ScreenCaptureService::class.java.let { cls ->
            val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            manager.getRunningServices(Integer.MAX_VALUE)
                ?.any { it.service.className == cls.name } == true
        }

    // ── 权限流程 ──────────────────────────────────────────────────────

    private fun startPermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        screenCaptureLauncher.launch(
            mediaProjectionManager.createScreenCaptureIntent()
        )
    }

    // ── 服务控制 ──────────────────────────────────────────────────────

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        startForegroundService(serviceIntent)
        AppPreferences.setFloatEnabled(this, true)
        Toast.makeText(this, "悬浮球已开启，切换到其他应用即可使用", Toast.LENGTH_LONG).show()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, FloatBallTileService::class.java)
            )
        }
    }

    private fun stopScreenCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        AppPreferences.setFloatEnabled(this, false)
        Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, FloatBallTileService::class.java)
            )
        }
    }

    // ── 应用拦截：overlay 被 Activity 盖住时，将 Activity 移到后台 ──
    private val moveToBackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(moveToBackReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
