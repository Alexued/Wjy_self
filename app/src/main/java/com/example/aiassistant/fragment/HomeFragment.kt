package com.example.aiassistant.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.DebugLogExporter
import com.example.aiassistant.QuestionType
import com.example.aiassistant.R
import com.example.aiassistant.TeacherManager
import com.example.aiassistant.questionbank.PracticeActivity
import com.example.aiassistant.questionbank.PracticeSettingsDialog
import com.example.aiassistant.questionbank.QuestionBankManager
import com.example.aiassistant.questionbank.QuestionModule
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class HomeFragment : Fragment() {

    interface ServiceControlListener {
        fun onStartServiceRequested()
        fun onStopServiceRequested()
        fun isServiceRunning(): Boolean
    }

    private var listener: ServiceControlListener? = null

    private lateinit var switchFloatBall: SwitchMaterial
    private lateinit var switchKeepScreenOn: SwitchMaterial
    private lateinit var tvFloatStatus: TextView
    private lateinit var tvActiveTeacher: TextView
    private lateinit var btnManageTeachers: MaterialButton
    private lateinit var btnExportDebugLog: MaterialButton
    private lateinit var layoutTypeChips: LinearLayout

    private lateinit var tvTotalCount: TextView
    private lateinit var rvModules: RecyclerView

    private lateinit var tvWrongStats: TextView
    private lateinit var btnWrongQuestions: View
    private lateinit var cardWrongQuestions: com.google.android.material.card.MaterialCardView
    private lateinit var btnActionAi: TextView
    private lateinit var btnActionRecord: TextView

    private var layoutBrandHeader: View? = null
    private var tvSubtitle: TextView? = null
    private var tvTitle: TextView? = null

    private val zenQuotes = listOf(
        Pair("博学之，审问之，慎思之，明辨之，笃行之。", "——《礼记》"),
        Pair("积土成山，风雨兴焉；积水成渊，蛟龙生焉。", "—— 荀子"),
        Pair("不积跬步，无以至千里；不积小流，无以成江海。", "—— 荀子"),
        Pair("操千曲而后晓声，观千剑而后识器。", "—— 刘勰"),
        Pair("天下难事，必作于易；天下大事，必作于细。", "—— 老子"),
        Pair("知之者不如好之者，好之者不如乐之者。", "—— 孔子"),
        Pair("纸上得来终觉浅，绝知此事要躬行。", "—— 陆游"),
        Pair("静以修身，俭以养德。非淡泊无以明志，非宁静无以致远。", "—— 诸葛亮"),
        Pair("路漫漫其修远兮，吾将上下而求索。", "—— 屈原"),
        Pair("水滴石穿，非力使然，恒也。", "—— 罗曼·罗兰")
    )

    private var currentQuestionType = QuestionType.PIAN_DUAN_YUE_DU
    private val bankReadyListener: () -> Unit = { loadModules() }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is ServiceControlListener) listener = context
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        loadConfig()
        setupListeners()
        buildTypeChips()
        loadModules()
        updateHeaderGreeting()
        showRandomQuote(false)

        // 开启 staggered 卡片入场动画，营造高级交互体验
        val container = (view as? ViewGroup)?.getChildAt(0) as? ViewGroup
        container?.let { animateEntrance(it) }
    }

    override fun onResume() {
        super.onResume()
        updateHeaderGreeting()
        updateFloatStatus(listener?.isServiceRunning() == true)
        updateTeacherDisplay()
        QuestionBankManager.addOnReadyListener(bankReadyListener)
        loadModules()
        updateWrongStats()

        val action = AppPreferences.getFloatClickAction(requireContext())
        updateSegmentedButtons(action)
    }

    override fun onPause() {
        super.onPause()
        QuestionBankManager.removeOnReadyListener(bankReadyListener)
    }

    private fun updateTeacherDisplay() {
        tvActiveTeacher.text = TeacherManager.activeTeacher.name
    }

    private fun updateWrongStats() {
        val ctx = context ?: return
        val allQuestions = com.example.aiassistant.questionbank.WrongQuestionManager.getWrongQuestions(ctx)
        val totalCount = allQuestions.size
        val unsummarizedCount = allQuestions.count { !it.isSummarized }
        tvWrongStats.text = "共 ${totalCount} 道错题 · ${unsummarizedCount} 道未总结"
    }

    private fun bindViews(view: View) {
        val act = activity
        if (act != null) {
            layoutBrandHeader = act.findViewById(R.id.layout_brand_header)
            tvSubtitle = act.findViewById(R.id.tv_subtitle)
            tvTitle = act.findViewById(R.id.tv_title)
        }

        switchFloatBall = view.findViewById(R.id.switch_float_ball)
        switchKeepScreenOn = view.findViewById(R.id.switch_keep_screen_on)
        tvFloatStatus = view.findViewById(R.id.tv_float_status)
        tvActiveTeacher = view.findViewById(R.id.tv_active_teacher)
        btnManageTeachers = view.findViewById(R.id.btn_manage_teachers)
        btnExportDebugLog = view.findViewById(R.id.btn_export_debug_log)
        layoutTypeChips = view.findViewById(R.id.layout_type_chips)
        tvTotalCount = view.findViewById(R.id.tv_total_count)
        rvModules = view.findViewById(R.id.rv_modules)
        rvModules.layoutManager = LinearLayoutManager(requireContext())

        tvWrongStats = view.findViewById(R.id.tv_wrong_stats)
        btnWrongQuestions = view.findViewById(R.id.btn_wrong_questions)
        cardWrongQuestions = view.findViewById(R.id.card_wrong_questions)
        btnActionAi = view.findViewById(R.id.btn_action_ai)
        btnActionRecord = view.findViewById(R.id.btn_action_record)
    }

    private fun loadConfig() {
        val ctx = requireContext()
        val running = listener?.isServiceRunning() == true
        switchFloatBall.isChecked = running
        updateFloatStatus(running)

        currentQuestionType = AppPreferences.getCurrentQuestionType(ctx)
        updateWrongStats()

        val action = AppPreferences.getFloatClickAction(ctx)
        updateSegmentedButtons(action)

        switchKeepScreenOn.isChecked = AppPreferences.isKeepScreenOnEnabled(ctx)
    }

    private fun setupListeners() {
        val ctx = requireContext()

        layoutBrandHeader?.setOnClickListener {
            showRandomQuote(true)
        }

        btnManageTeachers.setOnClickListener { showTeacherDialog() }
        btnExportDebugLog.setOnClickListener { DebugLogExporter.export(requireContext()) }

        switchFloatBall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                listener?.onStartServiceRequested()
            } else {
                listener?.onStopServiceRequested()
                AppPreferences.setFloatEnabled(ctx, false)
                updateFloatStatus(false)
            }
        }

        btnWrongQuestions.setOnClickListener {
            val intent = Intent(requireContext(), com.example.aiassistant.questionbank.WrongQuestionsActivity::class.java)
            startActivity(intent)
        }

        btnActionAi.setOnClickListener {
            AppPreferences.setFloatClickAction(ctx, AppPreferences.CLICK_ACTION_AI_ANALYZE)
            updateSegmentedButtons(AppPreferences.CLICK_ACTION_AI_ANALYZE)
        }

        btnActionRecord.setOnClickListener {
            AppPreferences.setFloatClickAction(ctx, AppPreferences.CLICK_ACTION_RECORD_WRONG)
            updateSegmentedButtons(AppPreferences.CLICK_ACTION_RECORD_WRONG)
        }

        switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setKeepScreenOnEnabled(ctx, isChecked)
        }
    }

    private fun buildTypeChips() {
        layoutTypeChips.removeAllViews()
        val d = requireContext().resources.displayMetrics.density
        val dp8 = (8 * d).toInt()
        val dp12 = (12 * d).toInt()

        for (type in QuestionType.entries) {
            val chip = TextView(requireContext()).apply {
                text = type.displayName
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(dp12, dp8, dp12, dp8)
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, dp8, 0)
                    ?: run {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, dp8, 0) }
                    }
                updateChipStyle(this, type == currentQuestionType)
                setOnClickListener {
                    currentQuestionType = type
                    AppPreferences.setCurrentQuestionType(requireContext(), type)
                    refreshChipStyles()
                }
            }
            layoutTypeChips.addView(chip)
        }
    }

    private fun refreshChipStyles() {
        val types = QuestionType.entries
        for (i in 0 until layoutTypeChips.childCount) {
            val chip = layoutTypeChips.getChildAt(i) as? TextView ?: continue
            updateChipStyle(chip, i < types.size && types[i] == currentQuestionType)
        }
    }

    private fun updateChipStyle(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setTextColor(0xFFFFFFFF.toInt())
            chip.setBackgroundResource(R.drawable.bg_primary_chip)
        } else {
            chip.setTextColor(0xFF6B7280.toInt())
            chip.setBackgroundResource(R.drawable.bg_default_chip)
        }
    }

    // ── 题库模块列表 ──────────────────────────────────────────────────

    private fun loadModules() {
        if (!isAdded) return
        if (!QuestionBankManager.isLoaded()) {
            tvTotalCount.text = "题库加载中..."
            QuestionBankManager.addOnReadyListener { loadModules() }
            return
        }

        QuestionBankManager.getModulesAsync { modules ->
            val totalQuestions = modules.sumOf { module ->
                module.questionCount + module.children.sumOf { it.questionCount }
            }
            val completedQuestions = modules.sumOf { module ->
                module.completedCount + module.children.sumOf { it.completedCount }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                tvTotalCount.text = "已做 $completedQuestions / 共 $totalQuestions 题"
                rvModules.adapter = ModuleAdapter(modules) { module ->
                    showPracticeSettings(module)
                }
            }
        }
    }

    private fun showPracticeSettings(module: QuestionModule) {
        val dialog = PracticeSettingsDialog.newInstance(module.id, module.name) { questionCount, rateMin, rateMax ->
            val intent = Intent(requireContext(), PracticeActivity::class.java)
            intent.putExtra("module_id", module.id)
            intent.putExtra("module_name", module.name)
            intent.putExtra("question_count", questionCount)
            intent.putExtra("rate_min", rateMin)
            intent.putExtra("rate_max", rateMax)
            startActivity(intent)
        }
        dialog.show(childFragmentManager, "practice_settings")
    }

    inner class ModuleAdapter(
        private val modules: List<QuestionModule>,
        private val onChildClick: (QuestionModule) -> Unit
    ) : RecyclerView.Adapter<ModuleAdapter.ViewHolder>() {

        // 维护已展开项的位置集合，确保 RecyclerView 在滑动复用时状态绝不丢失或错乱
        private val expandedPositions = mutableSetOf<Int>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvModuleName: TextView = view.findViewById(R.id.tv_module_name)
            val tvQuestionCount: TextView = view.findViewById(R.id.tv_question_count)
            val ivExpand: ImageView = view.findViewById(R.id.iv_expand)
            val layoutChildren: LinearLayout = view.findViewById(R.id.layout_children)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_module_parent, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val module = modules[position]
            holder.tvModuleName.text = module.name
            val totalCount = module.questionCount + module.children.sumOf { it.questionCount }
            val completedCount = module.completedCount + module.children.sumOf { it.completedCount }
            holder.tvQuestionCount.text = "已做 $completedCount / 共 $totalCount 题"

            // 根据数据状态同步 UI
            val isExpanded = expandedPositions.contains(position)
            holder.layoutChildren.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.ivExpand.rotation = if (isExpanded) 180f else 0f

            if (isExpanded) {
                addChildViews(holder.layoutChildren, module.children)
            } else {
                holder.layoutChildren.removeAllViews()
            }

            holder.itemView.setOnClickListener {
                // 点击父模块名称/卡片：直接按当前父模块下所有子模块随机取题刷题
                onChildClick(module)
            }
            holder.ivExpand.setOnClickListener {
                val currentlyExpanded = expandedPositions.contains(position)
                if (currentlyExpanded) expandedPositions.remove(position) else expandedPositions.add(position)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = modules.size

        private fun addChildViews(container: LinearLayout, children: List<QuestionModule>) {
            container.removeAllViews()
            for (child in children) {
                val childView = LayoutInflater.from(container.context)
                    .inflate(R.layout.item_module_child, container, false)

                childView.findViewById<TextView>(R.id.tv_child_name).text = child.name
                childView.findViewById<TextView>(R.id.tv_child_count).text = "已做 ${child.completedCount} / 共 ${child.questionCount} 题"

                childView.setOnClickListener {
                    onChildClick(child)
                }

                container.addView(childView)
            }
        }
    }

    // ── 悬浮球状态 ──────────────────────────────────────────────────

    fun updateFloatServiceStatus(enabled: Boolean) {
        if (!isAdded || view == null) return
        try {
            switchFloatBall.isChecked = enabled
            updateFloatStatus(enabled)
        } catch (_: Exception) {}
    }

    fun resetFloatSwitch() {
        if (!isAdded || view == null) return
        try {
            switchFloatBall.isChecked = false
            updateFloatStatus(false)
        } catch (_: Exception) {}
    }

    private fun updateFloatStatus(enabled: Boolean) {
        if (!isAdded) return
        try {
            tvFloatStatus.text = if (enabled) "✅ 运行中——切换到其他应用查看悬浮球" else "未启动"
            tvFloatStatus.setTextColor(
                if (enabled) requireContext().getColor(R.color.primary) else requireContext().getColor(R.color.text_secondary)
            )
        } catch (_: Exception) {}
    }

    // ── 老师管理 ──────────────────────────────────────────────────

    private fun showTeacherDialog() {
        val ctx = requireContext()
        val teachers = TeacherManager.allTeachers
        val names = teachers.map { "${it.name}${if (it.id == TeacherManager.activeTeacher.id) " ✓" else ""}" }.toTypedArray()
        val ids = teachers.map { it.id }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("选择老师")
            .setItems(names) { _, which ->
                val selectedId = ids[which]
                if (selectedId != TeacherManager.activeTeacher.id) {
                    TeacherManager.switchTeacher(ctx, selectedId)
                    updateTeacherDisplay()
                    Toast.makeText(ctx, "已切换到：${TeacherManager.activeTeacher.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("导入老师") { _, _ -> showImportDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImportDialog() {
        val ctx = requireContext()
        val input = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            hint = "粘贴老师 JSON 配置"
            minLines = 6
            gravity = android.view.Gravity.TOP
            setTextSize(12f)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("导入新老师")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val json = input.text?.toString()?.trim() ?: ""
                if (json.isBlank()) return@setPositiveButton
                TeacherManager.importTeacher(ctx, json)
                    .onSuccess {
                        TeacherManager.switchTeacher(ctx, it.id)
                        updateTeacherDisplay()
                        Toast.makeText(ctx, "导入成功：${it.name}", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { e ->
                        Toast.makeText(ctx, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun getActiveQuotes(): List<Pair<String, String>> {
        val ctx = context ?: return zenQuotes
        val custom = AppPreferences.getCustomQuotes(ctx)
        return if (custom.isNotEmpty()) custom else zenQuotes
    }

    private fun showRandomQuote(animate: Boolean) {
        val quotes = getActiveQuotes()
        if (quotes.isEmpty()) return
        val (quoteText, quoteAuthor) = quotes.random()
        val combined = "「 $quoteText 」 $quoteAuthor"
        val subtitle = tvSubtitle ?: return
        if (animate) {
            subtitle.animate().alpha(0f).setDuration(220).withEndAction {
                subtitle.text = combined
                subtitle.animate().alpha(1f).setDuration(220).start()
            }.start()
        } else {
            subtitle.text = combined
        }
    }

    private fun updateHeaderGreeting() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "清晨静学"
            in 12..17 -> "午后静学"
            in 18..22 -> "黄昏静学"
            else -> "夜深静学"
        }
        tvTitle?.text = greeting
    }

    private fun animateEntrance(container: ViewGroup) {
        val count = container.childCount
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = 50f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay(i * 80L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun updateSegmentedButtons(action: Int) {
        val ctx = context ?: return
        if (action == AppPreferences.CLICK_ACTION_RECORD_WRONG) {
            btnActionRecord.setBackgroundResource(R.drawable.bg_segmented_active_terracotta)
            btnActionRecord.setTextColor(resources.getColor(R.color.white, null))
            btnActionAi.setBackgroundResource(R.drawable.bg_segmented_inactive)
            btnActionAi.setTextColor(resources.getColor(R.color.text_secondary, null))

            cardWrongQuestions.setCardBackgroundColor(resources.getColor(R.color.incorrect_red_bg, null))
            cardWrongQuestions.setStrokeColor(android.content.res.ColorStateList.valueOf(resources.getColor(R.color.incorrect_red_border, null)))
        } else {
            btnActionAi.setBackgroundResource(R.drawable.bg_segmented_active_matcha)
            btnActionAi.setTextColor(resources.getColor(R.color.white, null))
            btnActionRecord.setBackgroundResource(R.drawable.bg_segmented_inactive)
            btnActionRecord.setTextColor(resources.getColor(R.color.text_secondary, null))

            cardWrongQuestions.setCardBackgroundColor(resources.getColor(R.color.correct_green_bg, null))
            cardWrongQuestions.setStrokeColor(android.content.res.ColorStateList.valueOf(resources.getColor(R.color.correct_green_border, null)))
        }
    }
}
