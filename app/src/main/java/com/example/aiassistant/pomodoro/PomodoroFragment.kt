package com.example.aiassistant.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.R
import com.example.aiassistant.plan.PlanManager

data class PomoTask(var title: String, var minutes: Int, var tag: String = "专注")

class PomodoroFragment : Fragment(), PomodoroTimer.TimerListener {

    private var timer: PomodoroTimer? = null
    private var vibrator: Vibrator? = null

    // Views
    private var tvStateLabel: TextView? = null
    private var tvTimer: TextView? = null
    private var tvPhase: TextView? = null
    private var progressView: PomodoroProgressView? = null
    private var tvCurrentTask: TextView? = null
    private var layoutCurrentTask: View? = null
    private var tvTodayProgress: TextView? = null
    private var btnSelectTask: View? = null
    private var btnStart: TextView? = null
    private var layoutPauseSkip: View? = null
    private var btnPause: TextView? = null
    private var btnSkip: TextView? = null
    private var statsFocusTime: TextView? = null
    private var statsTomatoCount: TextView? = null
    private var statsCompletion: TextView? = null
    private var chipRain: TextView? = null
    private var chipCafe: TextView? = null
    private var chipForest: TextView? = null
    private var seekbarVolume: SeekBar? = null

    // State
    private var currentTaskTitle: String = ""
    private var currentTag: String = ""
    private var currentPlanTaskId: Long = -1
    private var currentSessionId: Long = -1
    private var whiteNoisePlayer: WhiteNoisePlayer? = null

    // 应用拦截：接收来自拦截服务的"结束专注"广播
    private val stopFocusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            timer?.let { t ->
                if (t.isRunning() || t.state == TimerState.PAUSED) {
                    // 停止计时器并记录
                    if (currentSessionId > 0) {
                        val elapsedMinutes = ((t.getElapsedMillis()) / 60000).toInt().coerceAtLeast(1)
                        PomodoroManager.cancelSession(currentSessionId, elapsedMinutes)
                        currentSessionId = -1
                    }
                    t.stop()
                    whiteNoisePlayer?.pause()
                    stopAppBlocker()
                    updateUI()
                    refreshStats()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_pomodoro, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vibrator = requireContext().getSystemService(Vibrator::class.java)

        initViews(view)
        setupClickListeners(view)
        setupWhiteNoise()

        timer = PomodoroTimer(this)
        loadConfig()

        // 注册"结束专注"广播
        val filter = IntentFilter("com.example.aiassistant.STOP_FOCUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(stopFocusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(stopFocusReceiver, filter)
        }

        // 先恢复旋转前的计时器，再清理孤立会话（避免清理掉正在恢复的会话）
        restoreTimerIfNeeded()
        try { PomodoroManager.cleanOrphanedSessions() } catch (_: Exception) {}
        refreshStats()

        // 第一次进入番茄钟引导
        if (AppPreferences.isPomodoroFirstEntry(requireContext())) {
            showFirstEntryGuide()
        }
    }

    private fun showFirstEntryGuide() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("✨ 静心专注指引")
            .setMessage("欢迎使用番茄工作法！\n\n右上角的「齿轮」图标是番茄钟的设置中心。您可以在那里配置番茄时长、开启静心白噪音、以及开启「应用白名单拦截」，助您心无旁骛、高效学习。")
            .setPositiveButton("我知道了") { dialog, _ ->
                AppPreferences.setPomodoroFirstEntry(ctx, false)
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroyView() {
        // 如果计时器正在运行，保存状态（供旋转后恢复）
        val timerWasActive = timer?.let { t ->
            t.isRunning() || t.state == TimerState.PAUSED
        } ?: false

        timer?.let { t ->
            if (timerWasActive) {
                PomodoroTimerHolder.save(requireContext(), t, currentSessionId, currentTaskTitle, currentTag, currentPlanTaskId)
            }
        }

        timer?.stop()
        timer = null

        whiteNoisePlayer?.release()
        whiteNoisePlayer = null

        // 注销广播接收器
        try { requireContext().unregisterReceiver(stopFocusReceiver) } catch (_: Exception) {}

        // ⚠️ 关键：onDestroyView 不能停止 AppBlockerService！
        // 用户切到其他 App 时 Fragment 的 View 会被销毁，此时服务必须继续运行来拦截
        // 只有在计时器不再专注（IDLE/非运行）时才停服务
        if (!timerWasActive) {
            stopAppBlocker()
        }

        try { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}

        tvStateLabel = null; tvTimer = null; tvPhase = null; progressView = null
        tvCurrentTask = null; layoutCurrentTask = null; tvTodayProgress = null
        btnSelectTask = null; btnStart = null; layoutPauseSkip = null
        btnPause = null; btnSkip = null
        statsFocusTime = null; statsTomatoCount = null; statsCompletion = null
        chipRain = null; chipCafe = null; chipForest = null; seekbarVolume = null
        vibrator = null

        super.onDestroyView()
    }

    // ── 初始化 ──

    private fun initViews(view: View) {
        tvStateLabel = view.findViewById(R.id.tv_state_label)
        tvTimer = view.findViewById(R.id.tv_timer)
        tvPhase = view.findViewById(R.id.tv_phase)
        progressView = view.findViewById(R.id.progress_view)
        tvCurrentTask = view.findViewById(R.id.tv_current_task)
        layoutCurrentTask = view.findViewById(R.id.layout_current_task)
        tvTodayProgress = view.findViewById(R.id.tv_today_progress)
        btnSelectTask = view.findViewById(R.id.btn_select_task)
        btnStart = view.findViewById(R.id.btn_start)
        layoutPauseSkip = view.findViewById(R.id.layout_pause_skip)
        btnPause = view.findViewById(R.id.btn_pause)
        btnSkip = view.findViewById(R.id.btn_skip)
        statsFocusTime = view.findViewById(R.id.stats_focus_time)
        statsTomatoCount = view.findViewById(R.id.stats_tomato_count)
        statsCompletion = view.findViewById(R.id.stats_completion)
        chipRain = view.findViewById(R.id.chip_rain)
        chipCafe = view.findViewById(R.id.chip_cafe)
        chipForest = view.findViewById(R.id.chip_forest)
        seekbarVolume = view.findViewById(R.id.seekbar_volume)
    }

    private fun setupClickListeners(view: View) {
        btnStart?.setOnClickListener { onStartClicked() }
        btnPause?.setOnClickListener { onPauseClicked() }
        btnSkip?.setOnClickListener { onSkipClicked() }
        btnSelectTask?.setOnClickListener { showTaskSelectDialog() }
        view.findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            startActivity(Intent(requireContext(), PomodoroSettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_view_stats)?.setOnClickListener {
            startActivity(Intent(requireContext(), PomodoroStatsActivity::class.java))
        }
    }

    private fun loadConfig() {
        val ctx = requireContext()
        val config = PomodoroConfig(
            focusMinutes = AppPreferences.getPomodoroFocusMin(ctx),
            shortBreakMinutes = AppPreferences.getPomodoroShortBreak(ctx),
            longBreakMinutes = AppPreferences.getPomodoroLongBreak(ctx),
            longBreakInterval = AppPreferences.getPomodoroLongInterval(ctx),
            autoStartBreak = AppPreferences.isPomodoroAutoBreak(ctx),
            autoStartFocus = AppPreferences.isPomodoroAutoFocus(ctx),
            breakEndReminder = AppPreferences.isPomodoroBreakRemind(ctx),
            keepScreenOn = AppPreferences.isPomodoroKeepScreen(ctx),
            dailyTarget = AppPreferences.getPomodoroDailyTarget(ctx),
            vibrationEnabled = AppPreferences.isPomodoroVibration(ctx),
            whiteNoiseVolume = AppPreferences.getPomodoroNoiseVolume(ctx) / 100f,
            selectedWhiteNoise = AppPreferences.getPomodoroNoiseType(ctx)
        )
        timer?.configure(config)
        seekbarVolume?.progress = (config.whiteNoiseVolume * 100).toInt()
        updateTimerDisplay(config.focusMinutes * 60L * 1000, config.focusMinutes * 60L * 1000)
    }

    private fun restoreTimerIfNeeded() {
        val ctx = context ?: return
        if (!PomodoroTimerHolder.hasSavedState(ctx)) return

        val freshConfig = timer?.config
        val info = PomodoroTimerHolder.restore(ctx, timer ?: return) ?: return
        currentSessionId = info.sessionId
        currentTaskTitle = info.taskTitle
        currentTag = info.tag
        currentPlanTaskId = info.planTaskId

        // restore() 用旧配置覆盖了 config，重新应用新配置
        // 注意：正在运行的 totalMillis 不受影响（它在 restoreRunning 中已设定）
        // 新配置只影响下一次 startFocus/startBreak
        if (freshConfig != null) timer?.configure(freshConfig)

        updateUI()
        updateTimerDisplay(timer?.getRemainingMillis() ?: 0, timer?.getTotalMillis() ?: 0)
    }

    // ── 计时器控制 ──

    private fun onStartClicked() {
        val t = timer ?: return
        
        // ── 核心安全权限校验 ──
        if (AppPreferences.isAppBlockerEnabled(requireContext())) {
            if (!checkAppBlockerPermissions()) {
                return // 拦截：必须拥有权限才能开始专注
            }
        }

        when (t.state) {
            TimerState.IDLE -> {
                val sessionId = PomodoroManager.startSession(
                    taskTitle = currentTaskTitle.ifEmpty { "专注学习" },
                    tag = currentTag,
                    targetMinutes = t.config.focusMinutes,
                    planTaskId = currentPlanTaskId
                )
                if (sessionId == -1L) {
                    Toast.makeText(requireContext(), "创建会话失败", Toast.LENGTH_SHORT).show()
                    return
                }
                currentSessionId = sessionId
                t.startFocus()
                whiteNoisePlayer?.play()
                // 保存当前任务名，供 AppBlockerService 读取对应白名单
                val taskName = currentTaskTitle.ifEmpty { "专注学习" }
                AppPreferences.setAppBlockerCurrentTask(requireContext(), taskName)
                startAppBlocker()
            }
            TimerState.PAUSED -> {
                t.resume()
                whiteNoisePlayer?.play()
                if (timer?.state == TimerState.FOCUS || pausedFromFocus()) {
                    startAppBlocker()
                }
            }
            else -> {}
        }
        updateUI()
    }

    private fun pausedFromFocus(): Boolean {
        return timer?.pausedFromStateOrdinal() == TimerState.FOCUS.ordinal
    }

    private fun onPauseClicked() {
        timer?.pause()
        whiteNoisePlayer?.pause()
        updateUI()
    }

    private fun onSkipClicked() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("跳过当前阶段？")
            .setMessage("确定要跳过当前计时吗？")
            .setPositiveButton("跳过") { _, _ ->
                timer?.skip()
                whiteNoisePlayer?.pause()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── TimerListener ──

    override fun onTick(remainingMillis: Long, totalMillis: Long) {
        if (!isAdded) return
        updateTimerDisplay(remainingMillis, totalMillis)
    }

    override fun onPhaseComplete(state: TimerState, isSkipped: Boolean) {
        if (!isAdded) return

        if (state == TimerState.FOCUS && currentSessionId > 0) {
            val elapsedMinutes = ((timer?.getElapsedMillis() ?: 0) / 60000).toInt().coerceAtLeast(1)
            if (isSkipped) {
                PomodoroManager.cancelSession(currentSessionId, elapsedMinutes)
            } else {
                PomodoroManager.completeSession(currentSessionId, elapsedMinutes)
            }
            currentSessionId = -1
        }

        // 专注阶段结束，停止应用拦截
        if (state == TimerState.FOCUS) {
            stopAppBlocker()
        }

        if (!isSkipped) {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(500)
                }
            }
            val msg = when (state) {
                TimerState.FOCUS -> "专注完成！休息一下吧"
                TimerState.SHORT_BREAK -> "休息结束，继续加油！"
                TimerState.LONG_BREAK -> "长休息结束！"
                else -> ""
            }
            if (msg.isNotEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
        refreshStats()
    }

    override fun onStateChanged(newState: TimerState) {
        if (!isAdded) return
        updateUI()
    }

    // ── UI 更新 ──

    private fun updateTimerDisplay(remainingMs: Long, totalMs: Long) {
        val totalSeconds = (remainingMs / 1000).toInt()
        tvTimer?.text = String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)

        val progress = if (totalMs > 0) remainingMs.toFloat() / totalMs else 0f
        progressView?.setProgress(progress)

        val color = when (timer?.state) {
            TimerState.FOCUS -> Color.parseColor("#3B82F6")
            TimerState.SHORT_BREAK -> Color.parseColor("#10B981")
            TimerState.LONG_BREAK -> Color.parseColor("#8B5CF6")
            else -> Color.parseColor("#3B82F6")
        }
        progressView?.setProgressColor(color)
    }

    private fun updateUI() {
        val t = timer ?: return
        when (t.state) {
            TimerState.IDLE -> {
                tvStateLabel?.text = "准备开始"
                tvPhase?.text = "专注"
                btnStart?.text = "开始专注"
                btnStart?.visibility = View.VISIBLE
                layoutPauseSkip?.visibility = View.GONE
                btnSelectTask?.visibility = View.VISIBLE
                try { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
            }
            TimerState.FOCUS -> {
                tvStateLabel?.text = "专注中"
                tvPhase?.text = "专注"
                btnStart?.visibility = View.GONE
                layoutPauseSkip?.visibility = View.VISIBLE
                btnPause?.text = "暂停"
                btnSelectTask?.visibility = View.GONE
                if (t.config.keepScreenOn) {
                    try { activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
                }
            }
            TimerState.SHORT_BREAK -> {
                tvStateLabel?.text = "短休息"
                tvPhase?.text = "休息"
                btnStart?.visibility = View.GONE
                layoutPauseSkip?.visibility = View.VISIBLE
                btnPause?.text = "暂停"
                btnSelectTask?.visibility = View.GONE
            }
            TimerState.LONG_BREAK -> {
                tvStateLabel?.text = "长休息"
                tvPhase?.text = "休息"
                btnStart?.visibility = View.GONE
                layoutPauseSkip?.visibility = View.VISIBLE
                btnPause?.text = "暂停"
                btnSelectTask?.visibility = View.GONE
            }
            TimerState.PAUSED -> {
                tvStateLabel?.text = "已暂停"
                btnStart?.text = "继续"
                btnStart?.visibility = View.VISIBLE
                layoutPauseSkip?.visibility = View.GONE
                btnSelectTask?.visibility = View.GONE
            }
        }

        if (currentTaskTitle.isNotEmpty()) {
            layoutCurrentTask?.visibility = View.VISIBLE
            tvCurrentTask?.text = currentTaskTitle
        } else {
            layoutCurrentTask?.visibility = View.GONE
        }
    }

    private fun refreshStats() {
        if (!isAdded) return
        val stats = PomodoroManager.getTodayStats()
        val focusH = stats.totalFocusMinutes / 60
        val focusM = stats.totalFocusMinutes % 60
        statsFocusTime?.text = if (focusH > 0) "${focusH}h${focusM}m" else "${focusM}m"
        statsTomatoCount?.text = "${stats.completedCount}"
        val dailyTarget = timer?.config?.dailyTarget ?: 8
        val pct = if (dailyTarget > 0) (stats.completedCount * 100 / dailyTarget) else 0
        statsCompletion?.text = "${pct.coerceAtMost(100)}%"
        tvTodayProgress?.text = "今日 ${stats.completedCount}/$dailyTarget 🍅"
    }

    // ── 任务选择与自定义 ──

    private fun getUserTasks(context: Context): List<PomoTask> {
        val raw = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("pomodoro_user_tasks", null)
        if (raw.isNullOrEmpty()) {
            return listOf(
                PomoTask("背英语单词", 25, "英语"),
                PomoTask("做数学卷子", 45, "数学"),
                PomoTask("静坐与正念", 15, "冥想")
            )
        }
        return raw.split(";;;").mapNotNull {
            val parts = it.split("|")
            if (parts.size >= 3) {
                PomoTask(parts[0], parts[1].toIntOrNull() ?: 25, parts[2])
            } else null
        }
    }

    private fun saveUserTasks(context: Context, tasks: List<PomoTask>) {
        val raw = tasks.joinToString(";;;") { "${it.title}|${it.minutes}|${it.tag}" }
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("pomodoro_user_tasks", raw).apply()
    }

    private fun showTaskSelectDialog() {
        if (!isAdded) return
        val context = requireContext()
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_select_pomodoro_task, null)

        val etNewTask = dialogView.findViewById<android.widget.EditText>(R.id.et_new_task)
        val etNewTaskMinutes = dialogView.findViewById<android.widget.EditText>(R.id.et_new_task_minutes)
        val btnAddTask = dialogView.findViewById<View>(R.id.btn_add_task)
        val rvTasks = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_tasks)
        val btnNoTask = dialogView.findViewById<View>(R.id.btn_no_task)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        val tasks = getUserTasks(context).toMutableList()
        var adapter: TaskSelectAdapter? = null

        val onSelect: (PomoTask) -> Unit = { task ->
            currentTaskTitle = task.title
            currentTag = task.tag
            currentPlanTaskId = -1

            // 自动加载该任务的专注时长
            val freshConfig = timer?.config?.copy(focusMinutes = task.minutes)
            if (freshConfig != null) {
                timer?.configure(freshConfig)
                updateTimerDisplay(task.minutes * 60L * 1000, task.minutes * 60L * 1000)
            }

            updateUI()
            dialog.dismiss()
        }

        val onEdit: (Int, PomoTask) -> Unit = { index, task ->
            val editDialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_edit_pomo_task, null)
            val etEditName = editDialogView.findViewById<android.widget.EditText>(R.id.et_edit_task_name)
            val etEditMinutes = editDialogView.findViewById<android.widget.EditText>(R.id.et_edit_task_minutes)
            etEditName.setText(task.title)
            etEditMinutes.setText(task.minutes.toString())

            AlertDialog.Builder(context)
                .setTitle("编辑任务")
                .setView(editDialogView)
                .setPositiveButton("保存") { editDialog, _ ->
                    val name = etEditName.text.toString().trim()
                    val min = etEditMinutes.text.toString().trim().toIntOrNull() ?: 25
                    if (name.isNotEmpty()) {
                        task.title = name
                        task.minutes = min
                        saveUserTasks(context, tasks)
                        adapter?.notifyItemChanged(index)
                    }
                    editDialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val onDelete: (Int, PomoTask) -> Unit = { index, task ->
            AlertDialog.Builder(context)
                .setTitle("删除任务")
                .setMessage("确定要删除任务「${task.title}」吗？")
                .setPositiveButton("删除") { confirmDialog, _ ->
                    tasks.removeAt(index)
                    saveUserTasks(context, tasks)
                    adapter?.notifyItemRemoved(index)
                    adapter?.notifyItemRangeChanged(index, tasks.size)
                    confirmDialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        adapter = TaskSelectAdapter(
            items = tasks,
            onSelect = onSelect,
            onEdit = onEdit,
            onDelete = onDelete
        )
        rvTasks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        rvTasks.adapter = adapter

        btnAddTask.setOnClickListener {
            val title = etNewTask.text.toString().trim()
            val minutesStr = etNewTaskMinutes.text.toString().trim()
            val minutes = minutesStr.toIntOrNull() ?: 25
            if (title.isNotEmpty()) {
                val newTask = PomoTask(title, minutes, "专注")
                tasks.add(newTask)
                saveUserTasks(context, tasks)
                adapter?.notifyItemInserted(tasks.size - 1)
                etNewTask.text.clear()
                etNewTaskMinutes.text.clear()
                rvTasks.scrollToPosition(tasks.size - 1)
            } else {
                Toast.makeText(context, "请输入任务名称", Toast.LENGTH_SHORT).show()
            }
        }

        btnNoTask.setOnClickListener {
            currentTaskTitle = ""
            currentTag = ""
            currentPlanTaskId = -1
            updateUI()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── 应用拦截 ──

    private fun startAppBlocker() {
        if (AppPreferences.isAppBlockerEnabled(requireContext())) {
            AppBlockerService.start(requireContext())
        }
    }

    private fun stopAppBlocker() {
        try { AppBlockerService.stop(requireContext()) } catch (_: Exception) {}
    }

    /**
     * 检查并请求应用拦截所需的权限
     * @return true 如果全部权限已授权；false 如果正在引导用户授权中
     */
    private fun checkAppBlockerPermissions(): Boolean {
        val ctx = requireContext()
        
        // 1. 检查悬浮窗权限
        if (!Settings.canDrawOverlays(ctx)) {
            AlertDialog.Builder(ctx)
                .setTitle("需要开启悬浮窗权限")
                .setMessage("应用拦截需要悬浮窗权限才能显示拦截遮罩。\n\n请在接下来的设置页面中找到「显示在其他应用上层」并开启授权。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    ))
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }

        // 2. 检查查看使用情况权限
        if (!hasUsageStatsPermission()) {
            AlertDialog.Builder(ctx)
                .setTitle("需要查看使用情况权限")
                .setMessage("应用拦截需要「查看使用情况」权限才能读取前台应用包名。\n\n请在接下来的系统设置页面中找到「AI伴学」并开启授权。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }

        // 3. 通用「后台弹出界面/后台启动」权限检测（完美兼容小米、VIVO、OPPO、魅族等国产定制系统的底层的 OP_BACKGROUND_START_ACTIVITY）
        if (!isBackgroundStartAllowed(ctx)) {
            AlertDialog.Builder(ctx)
                .setTitle("需要开启后台弹出界面权限")
                .setMessage("检测到您的手机系统限制了应用在后台弹出窗口。为了使番茄钟能在您使用违规应用时立即弹出拦截画面，请允许应用的「后台弹出界面」或「后台启动界面」权限。")
                .setPositiveButton("去开启") { _, _ ->
                    // 1. 小米设备优先尝试精准跳转安全中心
                    if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                        try {
                            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                                setClassName("com.miui.securitycenter", "com.miui.permalink.MainActivity")
                                putExtra("extra_pkgname", ctx.packageName)
                            }
                            startActivity(intent)
                            return@setPositiveButton
                        } catch (_: Exception) {}
                    }

                    // 2. 其他国产设备或标准系统，兜底引导至应用管理详情页，方便用户在"权限管理"中点击开启
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${ctx.packageName}")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "未找到对应设置，请在手机系统「设置」->「应用管理」中找到 AI伴学 并开启该权限", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }

        // 4. 检查忽略电池优化权限，防止后台进入冷冻（cgroup freeze）状态
        if (!isIgnoringBatteryOptimizations(ctx)) {
            AlertDialog.Builder(ctx)
                .setTitle("需要允许后台运行")
                .setMessage("为了防止手机系统（如智能省电）在后台强行冷冻或关闭拦截功能，请在接下来的设置中为「AI伴学」选择「无限制」或「允许后台高能耗运行」。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${ctx.packageName}")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }

        return true
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val ctx = context ?: return false
        val appOps = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            ctx.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isBackgroundStartAllowed(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val method = ops.javaClass.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            // OP_BACKGROUND_START_ACTIVITY (后台弹出界面) 在主流国产定制系统底层（MIUI/OriginOS/ColorOS/Flyme等）统一为 10021
            val result = method.invoke(ops, 10021, android.os.Process.myUid(), context.packageName) as Int
            result == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            true // 发生反射或系统无此 AppOps 限制时默认放行（例如原生系统或三星默认就允许后台弹出界面），防误拦截
        }
    }


    // ── 白噪音 ──

    private fun setupWhiteNoise() {
        whiteNoisePlayer = WhiteNoisePlayer(requireContext())
        val noiseType = AppPreferences.getPomodoroNoiseType(requireContext())
        updateNoiseChips(noiseType)

        chipRain?.setOnClickListener { selectNoise("rain") }
        chipCafe?.setOnClickListener { selectNoise("cafe") }
        chipForest?.setOnClickListener { selectNoise("forest") }

        seekbarVolume?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                whiteNoisePlayer?.setVolume(progress / 100f)
                if (fromUser) AppPreferences.setPomodoroNoiseVolume(requireContext(), progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        if (noiseType.isNotEmpty()) whiteNoisePlayer?.setNoiseType(noiseType)
    }

    private fun selectNoise(type: String) {
        val current = AppPreferences.getPomodoroNoiseType(requireContext())
        if (current == type) {
            AppPreferences.setPomodoroNoiseType(requireContext(), "")
            whiteNoisePlayer?.stop()
            updateNoiseChips("")
        } else {
            AppPreferences.setPomodoroNoiseType(requireContext(), type)
            whiteNoisePlayer?.setNoiseType(type)
            if (timer?.isRunning() == true) whiteNoisePlayer?.play()
            updateNoiseChips(type)
        }
    }

    private fun updateNoiseChips(selected: String) {
        chipRain?.setBackgroundResource(if (selected == "rain") R.drawable.bg_noise_chip_selected else R.drawable.bg_noise_chip)
        chipCafe?.setBackgroundResource(if (selected == "cafe") R.drawable.bg_noise_chip_selected else R.drawable.bg_noise_chip)
        chipForest?.setBackgroundResource(if (selected == "forest") R.drawable.bg_noise_chip_selected else R.drawable.bg_noise_chip)
        chipRain?.setTextColor(resources.getColor(if (selected == "rain") R.color.primary else R.color.text_secondary, null))
        chipCafe?.setTextColor(resources.getColor(if (selected == "cafe") R.color.primary else R.color.text_secondary, null))
        chipForest?.setTextColor(resources.getColor(if (selected == "forest") R.color.primary else R.color.text_secondary, null))
    }

    // ── Adapter ──

    private class TaskSelectAdapter(
        private val items: List<PomoTask>,
        private val onSelect: (PomoTask) -> Unit,
        private val onEdit: (Int, PomoTask) -> Unit,
        private val onDelete: (Int, PomoTask) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<TaskSelectAdapter.VH>() {

        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_task_title)
            val tvDuration: TextView = view.findViewById(R.id.tv_task_duration)
            val btnWhitelist: View = view.findViewById(R.id.btn_task_whitelist)
            val btnEdit: View = view.findViewById(R.id.btn_task_edit)
            val btnDelete: View = view.findViewById(R.id.btn_task_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pomodoro_task, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val task = items[position]
            holder.tvTitle.text = task.title
            holder.tvDuration.text = "时长: ${task.minutes}分钟"
            holder.itemView.setOnClickListener { onSelect(task) }
            
            // 白名单
            if (AppPreferences.isAppBlockerEnabled(holder.btnWhitelist.context)) {
                holder.btnWhitelist.visibility = View.VISIBLE
                val count = AppPreferences.getTaskWhitelist(holder.btnWhitelist.context, task.title).size
                (holder.btnWhitelist as? TextView)?.text = if (count > 0) "白名单(${count})" else "白名单"
                holder.btnWhitelist.setOnClickListener {
                    AppBlockerSettingsActivity.start(holder.btnWhitelist.context, task.title)
                }
            } else {
                holder.btnWhitelist.visibility = View.GONE
            }

            // 编辑
            holder.btnEdit.setOnClickListener { onEdit(position, task) }

            // 删除
            holder.btnDelete.setOnClickListener { onDelete(position, task) }
        }

        override fun getItemCount() = items.size
    }
}
