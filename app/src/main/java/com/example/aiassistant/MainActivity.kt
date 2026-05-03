package com.example.aiassistant

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // ── UI 控件 ─────────────────────────────────────────────────────────
    private lateinit var switchFloatBall: SwitchMaterial
    private lateinit var tvFloatStatus: TextView
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var etPrompt: TextInputEditText
    private lateinit var btnSave: MaterialButton

    // ── 截图模式 ────────────────────────────────────────────────────────
    private lateinit var rgCaptureMode: RadioGroup
    private lateinit var layoutFixedInfo: LinearLayout
    private lateinit var tvFixedStatus: TextView
    private lateinit var btnClearFixed: MaterialButton

    /** 悬浮窗权限 → 返回后继续录屏权限 */
    private val overlayPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能开启悬浮球", Toast.LENGTH_SHORT).show()
                switchFloatBall.isChecked = false
                updateFloatStatus(false)
            }
        }

    /** 录屏授权 → 成功后启动服务 */
    private val screenCaptureLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startScreenCaptureService(result.resultCode, result.data!!)
                updateFloatStatus(true)
            } else {
                Toast.makeText(this, "需要录屏权限才能使用截图功能", Toast.LENGTH_SHORT).show()
                switchFloatBall.isChecked = false
                updateFloatStatus(false)
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        bindViews()
        loadSavedConfig()
        setupListeners()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 初始化
    // ─────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        switchFloatBall = findViewById(R.id.switch_float_ball)
        tvFloatStatus   = findViewById(R.id.tv_float_status)
        etBaseUrl       = findViewById(R.id.et_base_url)
        etApiKey        = findViewById(R.id.et_api_key)
        etModel         = findViewById(R.id.et_model)
        etPrompt        = findViewById(R.id.et_prompt)
        btnSave         = findViewById(R.id.btn_save)

        // 截图模式
        rgCaptureMode   = findViewById(R.id.rg_capture_mode)
        layoutFixedInfo = findViewById(R.id.layout_fixed_info)
        tvFixedStatus   = findViewById(R.id.tv_fixed_status)
        btnClearFixed   = findViewById(R.id.btn_clear_fixed)
    }

    /** 从 SharedPreferences 加载已保存的配置 */
    private fun loadSavedConfig() {
        etBaseUrl.setText(AppPreferences.getApiBaseUrl(this))
        etApiKey.setText(AppPreferences.getApiKey(this))
        etModel.setText(AppPreferences.getApiModel(this))
        etPrompt.setText(AppPreferences.getPrompt(this))

        val floatEnabled = AppPreferences.isFloatEnabled(this)
        switchFloatBall.isChecked = floatEnabled
        updateFloatStatus(floatEnabled)

        // 截图模式
        val mode = AppPreferences.getCaptureMode(this)
        if (mode == AppPreferences.MODE_FIXED_AREA) {
            rgCaptureMode.check(R.id.rb_fixed_area)
        } else {
            rgCaptureMode.check(R.id.rb_custom_area)
        }
        updateFixedAreaUI(mode)
    }

    private fun setupListeners() {
        // ── 悬浮球开关 ──────────────────────────────────────────────────
        switchFloatBall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startPermissionFlow()
            } else {
                stopScreenCaptureService()
                AppPreferences.setFloatEnabled(this, false)
                updateFloatStatus(false)
            }
        }

        // ── 截图模式切换 ────────────────────────────────────────────────
        rgCaptureMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rb_fixed_area)
                AppPreferences.MODE_FIXED_AREA else AppPreferences.MODE_CUSTOM_AREA
            AppPreferences.setCaptureMode(this, mode)
            updateFixedAreaUI(mode)
        }

        // ── 清除固定区域（重新选择） ────────────────────────────────────
        btnClearFixed.setOnClickListener {
            AppPreferences.clearFixedRegion(this)
            updateFixedAreaUI(AppPreferences.MODE_FIXED_AREA)
            Toast.makeText(this, "已清除固定区域，下次截图时将重新选择", Toast.LENGTH_SHORT).show()
        }

        // ── 保存按钮 ────────────────────────────────────────────────────
        btnSave.setOnClickListener {
            saveConfig()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 截图模式 UI
    // ─────────────────────────────────────────────────────────────────────

    private fun updateFixedAreaUI(mode: Int) {
        if (mode == AppPreferences.MODE_FIXED_AREA) {
            layoutFixedInfo.visibility = View.VISIBLE
            if (AppPreferences.isFixedRegionSet(this)) {
                val r = AppPreferences.getFixedRegion(this)
                tvFixedStatus.text = "已设定区域：${r.width()}×${r.height()} @ (${r.left}, ${r.top})"
                tvFixedStatus.setTextColor(getColor(R.color.primary))
            } else {
                tvFixedStatus.text = "尚未设定，下次点击悬浮球时将框选"
                tvFixedStatus.setTextColor(getColor(R.color.text_secondary))
            }
        } else {
            layoutFixedInfo.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 配置保存
    // ─────────────────────────────────────────────────────────────────────

    private fun saveConfig() {
        val baseUrl = etBaseUrl.text?.toString()?.trim() ?: ""
        val apiKey  = etApiKey.text?.toString()?.trim() ?: ""
        val model   = etModel.text?.toString()?.trim() ?: ""
        val prompt  = etPrompt.text?.toString()?.trim() ?: ""

        AppPreferences.setApiBaseUrl(this, baseUrl)
        AppPreferences.setApiKey(this, apiKey)
        AppPreferences.setApiModel(this, model)
        AppPreferences.setPrompt(this, prompt.ifBlank {
            "请仔细分析这张截图的内容，给出详细的解析和学习建议。"
        })

        Toast.makeText(this, "配置已保存 ✓", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 权限流程
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 悬浮球开启流程：
     * 1. 检查悬浮窗权限 → 2. 请求录屏权限 → 3. 启动服务
     */
    private fun startPermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 服务控制
    // ─────────────────────────────────────────────────────────────────────

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        startForegroundService(serviceIntent)
        AppPreferences.setFloatEnabled(this, true)
        Toast.makeText(this, "悬浮球已开启，切换到其他应用即可使用", Toast.LENGTH_LONG).show()
    }

    private fun stopScreenCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI 辅助
    // ─────────────────────────────────────────────────────────────────────

    private fun updateFloatStatus(enabled: Boolean) {
        tvFloatStatus.text = if (enabled) "✅ 运行中——切换到其他应用查看悬浮球" else "未启动"
        tvFloatStatus.setTextColor(
            if (enabled) getColor(R.color.primary) else getColor(R.color.text_secondary)
        )
    }
}