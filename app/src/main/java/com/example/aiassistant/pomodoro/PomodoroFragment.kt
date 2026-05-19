package com.example.aiassistant.pomodoro

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
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

        // 先恢复旋转前的计时器，再清理孤立会话（避免清理掉正在恢复的会话）
        restoreTimerIfNeeded()
        try { PomodoroManager.cleanOrphanedSessions() } catch (_: Exception) {}
        refreshStats()
    }

    override fun onDestroyView() {
        // 如果计时器正在运行，保存状态（供旋转后恢复）
        timer?.let { t ->
            if (t.isRunning() || t.state == TimerState.PAUSED) {
                PomodoroTimerHolder.save(requireContext(), t, currentSessionId, currentTaskTitle, currentTag, currentPlanTaskId)
            }
        }

        timer?.stop()
        timer = null

        whiteNoisePlayer?.release()
        whiteNoisePlayer = null

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
            }
            TimerState.PAUSED -> {
                t.resume()
                whiteNoisePlayer?.play()
            }
            else -> {}
        }
        updateUI()
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

    // ── 任务选择弹窗 ──

    private fun showTaskSelectDialog() {
        if (!isAdded) return
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_select_pomodoro_task, null)

        val etNewTask = dialogView.findViewById<TextView>(R.id.et_new_task)
        val btnAddTask = dialogView.findViewById<View>(R.id.btn_add_task)
        val rvTasks = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_tasks)
        val btnNoTask = dialogView.findViewById<View>(R.id.btn_no_task)
        val layoutTagFilter = dialogView.findViewById<android.view.ViewGroup>(R.id.layout_tag_filter)

        var selectedTag = ""
        val allTags = listOf("全部") + FocusSession.DEFAULT_TAGS
        val tagViews = mutableListOf<TextView>()
        for (tag in allTags) {
            val chip = TextView(requireContext()).apply {
                text = tag
                textSize = 13f
                setPadding(24, 8, 24, 8)
                setBackgroundResource(R.drawable.bg_noise_chip)
                setTextColor(resources.getColor(R.color.text_secondary, null))
            }
            chip.setOnClickListener {
                tagViews.forEach { tv ->
                    tv.setBackgroundResource(R.drawable.bg_noise_chip)
                    tv.setTextColor(resources.getColor(R.color.text_secondary, null))
                }
                chip.setBackgroundResource(R.drawable.bg_noise_chip_selected)
                chip.setTextColor(resources.getColor(R.color.primary, null))
                selectedTag = if (tag == "全部") "" else tag
            }
            tagViews.add(chip)
            layoutTagFilter.addView(chip)
        }
        tagViews[0].performClick()

        val tasks = loadTasksForSelection("")
        val adapter = TaskSelectAdapter(tasks) { taskTitle, tag ->
            currentTaskTitle = taskTitle
            currentTag = tag
            updateUI()
        }
        rvTasks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rvTasks.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnAddTask.setOnClickListener {
            val title = etNewTask.text.toString().trim()
            if (title.isNotEmpty()) {
                currentTaskTitle = title
                currentTag = selectedTag
                updateUI()
                dialog.dismiss()
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

    private fun loadTasksForSelection(tag: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            for (task in PlanManager.getTodayPending()) {
                result.add(task.title to "计划")
            }
        } catch (_: Exception) {}
        for (defaultTag in FocusSession.DEFAULT_TAGS) {
            if (tag.isEmpty() || tag == defaultTag) {
                result.add("[$defaultTag] 学习" to defaultTag)
            }
        }
        return result
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
        private val items: List<Pair<String, String>>,
        private val onSelect: (String, String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<TaskSelectAdapter.VH>() {

        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_task_title)
            val tvTag: TextView = view.findViewById(R.id.tv_task_tag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pomodoro_task, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (title, tag) = items[position]
            holder.tvTitle.text = title
            holder.tvTag.text = tag
            holder.itemView.setOnClickListener { onSelect(title, tag) }
        }

        override fun getItemCount() = items.size
    }
}
