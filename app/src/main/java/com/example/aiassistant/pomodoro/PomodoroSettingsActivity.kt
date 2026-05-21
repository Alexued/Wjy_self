package com.example.aiassistant.pomodoro

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.R
import com.google.android.material.switchmaterial.SwitchMaterial

class PomodoroSettingsActivity : AppCompatActivity() {

    private lateinit var rvTaskWhitelists: RecyclerView
    private var taskAdapter: TaskWhitelistAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pomodoro_settings)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val etFocusMin = findViewById<EditText>(R.id.et_focus_min)
        val etShortBreak = findViewById<EditText>(R.id.et_short_break)
        val etLongBreak = findViewById<EditText>(R.id.et_long_break)
        val etLongInterval = findViewById<EditText>(R.id.et_long_interval)
        val etDailyTarget = findViewById<EditText>(R.id.et_daily_target)

        val switchAutoBreak = findViewById<SwitchMaterial>(R.id.switch_auto_break)
        val switchAutoFocus = findViewById<SwitchMaterial>(R.id.switch_auto_focus)
        val switchVibration = findViewById<SwitchMaterial>(R.id.switch_vibration)
        val switchKeepScreen = findViewById<SwitchMaterial>(R.id.switch_keep_screen)

        val switchAppBlocker = findViewById<SwitchMaterial>(R.id.switch_app_blocker)
        val tvAppBlockerStatus = findViewById<TextView>(R.id.tv_app_blocker_status)
        rvTaskWhitelists = findViewById(R.id.rv_task_whitelists)

        // 加载当前设置
        etFocusMin.setText(AppPreferences.getPomodoroFocusMin(this).toString())
        etShortBreak.setText(AppPreferences.getPomodoroShortBreak(this).toString())
        etLongBreak.setText(AppPreferences.getPomodoroLongBreak(this).toString())
        etLongInterval.setText(AppPreferences.getPomodoroLongInterval(this).toString())
        etDailyTarget.setText(AppPreferences.getPomodoroDailyTarget(this).toString())

        switchAutoBreak.isChecked = AppPreferences.isPomodoroAutoBreak(this)
        switchAutoFocus.isChecked = AppPreferences.isPomodoroAutoFocus(this)
        switchVibration.isChecked = AppPreferences.isPomodoroVibration(this)
        switchKeepScreen.isChecked = AppPreferences.isPomodoroKeepScreen(this)

        // 应用拦截
        switchAppBlocker.isChecked = AppPreferences.isAppBlockerEnabled(this)
        updateBlockerStatus(tvAppBlockerStatus)

        switchAppBlocker.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAppBlockerEnabled(this, isChecked)
            updateBlockerStatus(tvAppBlockerStatus)
            updateTaskList()

            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "使用应用拦截需要授予悬浮窗权限", Toast.LENGTH_LONG).show()
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                } else if (!isUsageStatsAllowed()) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("需要开启应用使用情况权限")
                        .setMessage("应用拦截功能需要「查看其他应用使用情况」权限以实时检测违规应用。\n\n请在接下来的系统设置页面中找到「AI伴学」并开启授权。")
                        .setPositiveButton("去开启") { _, _ ->
                            try {
                                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (_: Exception) {
                                Toast.makeText(this, "未找到对应设置，请在手机系统「设置」中手动开启应用使用情况权限", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("取消") { _, _ ->
                            switchAppBlocker.isChecked = false
                            AppPreferences.setAppBlockerEnabled(this, false)
                            updateBlockerStatus(tvAppBlockerStatus)
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        }

        // 任务白名单列表
        rvTaskWhitelists.layoutManager = LinearLayoutManager(this)
        updateTaskList()

        // 保存
        findViewById<View>(R.id.btn_save).setOnClickListener {
            val focusMin = etFocusMin.text.toString().toIntOrNull() ?: 25
            val shortBreak = etShortBreak.text.toString().toIntOrNull() ?: 5
            val longBreak = etLongBreak.text.toString().toIntOrNull() ?: 15
            val longInterval = etLongInterval.text.toString().toIntOrNull() ?: 4
            val dailyTarget = etDailyTarget.text.toString().toIntOrNull() ?: 8

            if (focusMin < 1 || focusMin > 180 ||
                shortBreak < 1 || shortBreak > 60 ||
                longBreak < 1 || longBreak > 120 ||
                longInterval < 1 || longInterval > 10 ||
                dailyTarget < 1 || dailyTarget > 30) {
                Toast.makeText(this, "数值超出范围（专注1-180，休息1-60/120，间隔1-10，目标1-30）", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            AppPreferences.setPomodoroFocusMin(this, focusMin)
            AppPreferences.setPomodoroShortBreak(this, shortBreak)
            AppPreferences.setPomodoroLongBreak(this, longBreak)
            AppPreferences.setPomodoroLongInterval(this, longInterval)
            AppPreferences.setPomodoroDailyTarget(this, dailyTarget)

            AppPreferences.setPomodoroAutoBreak(this, switchAutoBreak.isChecked)
            AppPreferences.setPomodoroAutoFocus(this, switchAutoFocus.isChecked)
            AppPreferences.setPomodoroVibration(this, switchVibration.isChecked)
            AppPreferences.setPomodoroKeepScreen(this, switchKeepScreen.isChecked)

            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun isUsageStatsAllowed(): Boolean {
        val appOps = getSystemService(android.app.AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    override fun onResume() {
        super.onResume()
        updateBlockerStatus(findViewById(R.id.tv_app_blocker_status))
        updateTaskList()
    }

    private fun updateBlockerStatus(tv: TextView) {
        if (!AppPreferences.isAppBlockerEnabled(this)) {
            tv.text = "专注期间拦截无关应用，提升学习效率"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            tv.text = "⚠ 悬浮窗权限未开启，点击开关重新引导开启"
            return
        }
        if (!isUsageStatsAllowed()) {
            tv.text = "⚠ 应用使用情况权限未开启，点击开关重新引导开启"
            return
        }
        val tasks = AppPreferences.getAllConfiguredTasks(this)
        tv.text = if (tasks.isEmpty()) {
            "已启用，尚未为任何任务配置白名单（默认禁止所有第三方应用）"
        } else {
            "已启用，已配置 ${tasks.size} 个任务的白名单"
        }
    }

    private fun updateTaskList() {
        if (!AppPreferences.isAppBlockerEnabled(this)) {
            rvTaskWhitelists.visibility = View.GONE
            return
        }
        rvTaskWhitelists.visibility = View.VISIBLE
        val tasks = AppPreferences.getAllConfiguredTasks(this)
        taskAdapter = TaskWhitelistAdapter(tasks)
        rvTaskWhitelists.adapter = taskAdapter
    }

    @Suppress("DEPRECATION")
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(android.app.AppOpsManager::class.java)
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private inner class TaskWhitelistAdapter(
        private val items: List<Pair<String, Int>>
    ) : RecyclerView.Adapter<TaskWhitelistAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_task_name)
            val tvCount: TextView = view.findViewById(R.id.tv_whitelist_count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_whitelist, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (taskTitle, count) = items[position]
            holder.tvName.text = taskTitle
            holder.tvCount.text = "${count}个应用"
            holder.itemView.setOnClickListener {
                AppBlockerSettingsActivity.start(this@PomodoroSettingsActivity, taskTitle)
            }
        }

        override fun getItemCount() = items.size
    }
}
