package com.apk.claw.android.ui.home

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.ui.guide.GuideActivity
import com.apk.claw.android.ui.settings.SettingsActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.apk.claw.android.widget.PermissionCardView
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : BaseActivity() {

    private lateinit var cardAccessibility: PermissionCardView
    private lateinit var cardNotification: PermissionCardView
    private lateinit var cardSystemWindow: PermissionCardView
    private lateinit var cardBattery: PermissionCardView
    private lateinit var cardStorage: PermissionCardView
    private lateinit var btnCancelTask: KButton
    private lateinit var etTaskInput: EditText
    private lateinit var tvCurrentModel: TextView
    private lateinit var llTaskHistory: LinearLayout
    private lateinit var tvClearHistory: TextView
    private lateinit var layoutTaskPage: LinearLayout
    private lateinit var layoutPermissionPage: LinearLayout
    private lateinit var btnTaskPage: KButton
    private lateinit var btnPermissionPage: KButton
    private lateinit var switchDebugMode: SwitchCompat

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val detailTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val checkRunnable = object : Runnable {
        override fun run() {
            updateAllPermissionStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        Toast.makeText(
            this,
            if (allGranted) R.string.home_storage_enabled else R.string.home_enable_storage,
            Toast.LENGTH_SHORT
        ).show()
        updateStorageStatus()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startNotificationService()
        } else {
            Toast.makeText(this, R.string.home_need_notification_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        initViews()
        showGuideIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateAllPermissionStatus()
        refreshLocalTaskUi()
        startStatusCheck()
    }

    override fun onPause() {
        super.onPause()
        stopStatusCheck()
    }

    override fun onDestroy() {
        appViewModel.setOnLocalTaskHistoryChanged(null)
        super.onDestroy()
    }

    private fun showGuideIfNeeded() {
        if (!KVUtils.isGuideShown()) {
            startActivity(Intent(this, GuideActivity::class.java))
        }
    }

    private fun initViews() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitleCentered(false)
            setTitle(getString(R.string.app_name))
            setActionIcon(R.drawable.ic_settings) {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            }
        }

        etTaskInput = findViewById(R.id.etTaskInput)
        tvCurrentModel = findViewById(R.id.tvCurrentModel)
        llTaskHistory = findViewById(R.id.llTaskHistory)
        tvClearHistory = findViewById(R.id.tvClearHistory)
        layoutTaskPage = findViewById(R.id.layoutTaskPage)
        layoutPermissionPage = findViewById(R.id.layoutPermissionPage)
        btnTaskPage = findViewById(R.id.btnTaskPage)
        btnPermissionPage = findViewById(R.id.btnPermissionPage)
        switchDebugMode = findViewById(R.id.switchDebugMode)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardNotification = findViewById(R.id.cardNotification)
        cardSystemWindow = findViewById(R.id.cardSystemWindow)
        cardBattery = findViewById(R.id.cardBattery)
        cardStorage = findViewById(R.id.cardStorage)
        btnCancelTask = findViewById(R.id.btnCancelTask)

        btnTaskPage.setOnClickListener { showPage(HomePage.TASKS) }
        btnPermissionPage.setOnClickListener { showPage(HomePage.PERMISSIONS) }
        switchDebugMode.isChecked = KVUtils.isLocalTaskDebugEnabled()
        switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            KVUtils.setLocalTaskDebugEnabled(isChecked)
            appViewModel.setLocalTaskDebugEnabled(isChecked)
        }
        findViewById<KButton>(R.id.btnStartTask).setOnClickListener { startLocalTaskFromInput() }
        findViewById<KButton>(R.id.btnNewTask).setOnClickListener {
            etTaskInput.text.clear()
            etTaskInput.requestFocus()
        }
        tvClearHistory.setOnClickListener {
            appViewModel.clearLocalTaskHistory()
            refreshLocalTaskUi()
        }

        appViewModel.setOnLocalTaskHistoryChanged {
            runOnUiThread {
                refreshLocalTaskUi()
                updateCancelTaskVisibility()
            }
        }

        btnCancelTask.setOnClickListener {
            if (appViewModel.isTaskRunning()) {
                appViewModel.cancelCurrentTask()
                Toast.makeText(this, R.string.home_cancel_task_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.home_no_task_running, Toast.LENGTH_SHORT).show()
            }
            updateCancelTaskVisibility()
            refreshLocalTaskUi()
        }

        cardAccessibility.setOnClickListener { requestAccessibilityPermission() }
        cardNotification.setOnClickListener { requestNotificationPermission() }
        cardSystemWindow.setOnClickListener { requestSystemWindowPermission() }
        cardBattery.setOnClickListener { requestBatteryPermission() }
        cardStorage.setOnClickListener { requestStoragePermission() }
        showPage(HomePage.TASKS)
    }

    private fun showPage(page: HomePage) {
        val showTasks = page == HomePage.TASKS
        layoutTaskPage.visibility = if (showTasks) View.VISIBLE else View.GONE
        layoutPermissionPage.visibility = if (showTasks) View.GONE else View.VISIBLE
        styleTab(btnTaskPage, showTasks)
        styleTab(btnPermissionPage, !showTasks)
    }

    private fun styleTab(button: KButton, selected: Boolean) {
        if (selected) {
            button.setBgColor(getColor(R.color.colorBrandPrimary))
            button.setTextColor(getColor(R.color.colorBrandOnPrimary))
            button.setBorderColor(getColor(R.color.colorBrandPrimary))
        } else {
            button.setBgColor(getColor(R.color.colorContainerBrighten))
            button.setTextColor(getColor(R.color.colorTextSecondary))
            button.setBorderColor(getColor(R.color.colorBorderBase))
        }
    }

    private fun startLocalTaskFromInput() {
        val error = appViewModel.startLocalTask(etTaskInput.text.toString())
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.home_task_started, Toast.LENGTH_SHORT).show()
        etTaskInput.text.clear()
        refreshLocalTaskUi()
        updateCancelTaskVisibility()
    }

    private fun refreshLocalTaskUi() {
        tvCurrentModel.text = getString(R.string.home_current_model, KVUtils.getLlmModelName())
        val history = appViewModel.getLocalTaskHistory()
        llTaskHistory.removeAllViews()
        tvClearHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE

        if (history.isEmpty()) {
            llTaskHistory.addView(createEmptyHistoryView())
            return
        }

        history.forEach { record ->
            llTaskHistory.addView(createTaskHistoryCard(record))
        }
    }

    private fun createEmptyHistoryView(): View {
        return TextView(this).apply {
            text = getString(R.string.home_task_history_empty)
            setTextColor(getColor(R.color.colorTextTertiary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, pt(18), 0, pt(18))
        }
    }

    private fun createTaskHistoryCard(record: KVUtils.LocalTaskRecord): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = pt(10) }
            radius = pt(10).toFloat()
            cardElevation = pt(1).toFloat()
            setCardBackgroundColor(getColor(R.color.colorContainerBrighten))
            isClickable = true
            isFocusable = true
            setOnClickListener { showTaskDetail(record) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pt(14), pt(12), pt(14), pt(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = statusText(record.status)
            setTextColor(statusColor(record.status))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = timeFormatter.format(Date(record.updatedAt))
            setTextColor(getColor(R.color.colorTextTertiary))
            textSize = 11f
        })
        content.addView(header)

        content.addView(TextView(this).apply {
            text = record.task
            setTextColor(getColor(R.color.colorTextPrimary))
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, pt(8), 0, 0)
        })

        if (record.result.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = record.result
                setTextColor(getColor(R.color.colorTextSecondary))
                textSize = 12f
                maxLines = 5
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, pt(8), 0, 0)
            })
        }

        content.addView(TextView(this).apply {
            text = record.modelName
            setTextColor(getColor(R.color.colorTextTertiary))
            textSize = 11f
            setPadding(0, pt(8), 0, 0)
        })

        card.addView(content)
        return card
    }

    private fun showTaskDetail(record: KVUtils.LocalTaskRecord) {
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pt(4), pt(4), pt(4), pt(4))
        }

        detail.addView(createDetailRow(getString(R.string.home_task_detail_status), statusText(record.status), statusColor(record.status)))
        detail.addView(createDetailRow(getString(R.string.home_task_detail_model), record.modelName))
        detail.addView(createDetailRow(getString(R.string.home_task_detail_created), detailTimeFormatter.format(Date(record.createdAt))))
        detail.addView(createDetailRow(getString(R.string.home_task_detail_updated), detailTimeFormatter.format(Date(record.updatedAt))))
        detail.addView(createDetailBlock(getString(R.string.home_task_detail_task), record.task))
        detail.addView(createDetailBlock(getString(R.string.home_task_detail_output), record.result.ifBlank { getString(R.string.home_task_detail_output_empty) }))
        detail.addView(createDetailBlock(getString(R.string.home_task_detail_thinking), record.thinking.orEmpty().ifBlank { getString(R.string.home_task_detail_thinking_empty) }))
        detail.addView(createDetailBlock(getString(R.string.home_task_detail_debug_log), record.debugLog.orEmpty().ifBlank { getString(R.string.home_task_detail_debug_log_empty) }))

        val scrollView = ScrollView(this).apply {
            addView(detail)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                pt(420)
            )
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_task_detail_title))
            .setView(scrollView)
            .setNeutralButton(R.string.home_export_debug_log) { _, _ -> exportTaskLog(record) }
            .setPositiveButton(R.string.common_close, null)
            .show()
    }

    private fun exportTaskLog(record: KVUtils.LocalTaskRecord) {
        try {
            val logDir = File(cacheDir, "task_logs").apply { mkdirs() }
            val file = File(logDir, "${record.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")
            file.writeText(buildTaskLog(record), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.home_export_debug_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.home_export_debug_log)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.home_export_debug_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildTaskLog(record: KVUtils.LocalTaskRecord): String {
        return buildString {
            appendLine("ApkClaw Task Debug Log")
            appendLine("ID: ${record.id}")
            appendLine("Status: ${record.status}")
            appendLine("Model: ${record.modelName}")
            appendLine("Debug enabled: ${record.debugEnabled}")
            appendLine("Created: ${detailTimeFormatter.format(Date(record.createdAt))}")
            appendLine("Updated: ${detailTimeFormatter.format(Date(record.updatedAt))}")
            appendLine()
            appendLine("========== TASK ==========")
            appendLine(record.task)
            appendLine()
            appendLine("========== RESULT ==========")
            appendLine(record.result.ifBlank { getString(R.string.home_task_detail_output_empty) })
            appendLine()
            appendLine("========== MODEL THINKING ==========")
            appendLine(record.thinking.orEmpty().ifBlank { getString(R.string.home_task_detail_thinking_empty) })
            appendLine()
            appendLine("========== DEBUG LOG ==========")
            appendLine(record.debugLog.orEmpty().ifBlank { getString(R.string.home_task_detail_debug_log_empty) })
        }
    }

    private fun createDetailRow(label: String, value: String, valueColor: Int = getColor(R.color.colorTextSecondary)): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pt(6), 0, pt(6))
            addView(TextView(this@HomeActivity).apply {
                text = label
                setTextColor(getColor(R.color.colorTextTertiary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(pt(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@HomeActivity).apply {
                text = value
                setTextColor(valueColor)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun createDetailBlock(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pt(12), 0, 0)
            addView(TextView(this@HomeActivity).apply {
                text = label
                setTextColor(getColor(R.color.colorTextTertiary))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@HomeActivity).apply {
                text = value
                setTextColor(getColor(R.color.colorTextPrimary))
                textSize = 13f
                setTextIsSelectable(true)
                setPadding(0, pt(6), 0, 0)
            })
        }
    }

    private fun statusText(status: String): String {
        return when (status) {
            KVUtils.LocalTaskStatus.RUNNING -> getString(R.string.home_task_status_running)
            KVUtils.LocalTaskStatus.COMPLETED -> getString(R.string.home_task_status_completed)
            KVUtils.LocalTaskStatus.FAILED -> getString(R.string.home_task_status_failed)
            KVUtils.LocalTaskStatus.CANCELLED -> getString(R.string.home_task_status_cancelled)
            else -> status
        }
    }

    private fun statusColor(status: String): Int {
        return when (status) {
            KVUtils.LocalTaskStatus.RUNNING -> getColor(R.color.colorInfoPrimary)
            KVUtils.LocalTaskStatus.COMPLETED -> getColor(R.color.colorSuccessPrimary)
            KVUtils.LocalTaskStatus.FAILED -> getColor(R.color.colorErrorPrimary)
            KVUtils.LocalTaskStatus.CANCELLED -> getColor(R.color.colorWarningPrimary)
            else -> getColor(R.color.colorTextSecondary)
        }
    }

    private fun updateAllPermissionStatus() {
        updateAccessibilityStatus()
        updateNotificationStatus()
        updateSystemWindowStatus()
        updateBatteryStatus()
        updateStorageStatus()
        updateCancelTaskVisibility()
    }

    private fun updateCancelTaskVisibility() {
        btnCancelTask.visibility = if (appViewModel.isTaskRunning()) View.VISIBLE else View.GONE
    }

    private fun updateAccessibilityStatus() {
        cardAccessibility.setPermissionEnabled(ClawAccessibilityService.isRunning())
    }

    private fun updateNotificationStatus() {
        cardNotification.setPermissionEnabled(ForegroundService.isRunning())
    }

    private fun updateSystemWindowStatus() {
        val enabled = Settings.canDrawOverlays(this)
        cardSystemWindow.setPermissionEnabled(enabled)
        if (enabled) {
            appViewModel.showFloatingCircle()
        }
    }

    private fun updateBatteryStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        cardBattery.setPermissionEnabled(powerManager.isIgnoringBatteryOptimizations(packageName))
    }

    private fun updateStorageStatus() {
        cardStorage.setPermissionEnabled(isStoragePermissionGranted())
    }

    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAccessibilityPermission() {
        if (!ClawAccessibilityService.isRunning()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.home_accessibility_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startNotificationService()
    }

    private fun startNotificationService() {
        val started = ForegroundService.start(this)
        if (started) {
            cardNotification.setPermissionEnabled(true)
            Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.home_need_notification_permission, Toast.LENGTH_SHORT).show()
            updateNotificationStatus()
        }
    }

    private fun requestSystemWindowPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStoragePermission() {
        if (isStoragePermissionGranted()) {
            Toast.makeText(this, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
            Toast.makeText(this, R.string.home_enable_storage, Toast.LENGTH_LONG).show()
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun requestBatteryPermission() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStatusCheck() {
        stopStatusCheck()
        handler.postDelayed(checkRunnable, 1000)
    }

    private fun stopStatusCheck() {
        handler.removeCallbacks(checkRunnable)
    }

    private fun pt(value: Int): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_PT,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private enum class HomePage {
        TASKS,
        PERMISSIONS
    }
}
