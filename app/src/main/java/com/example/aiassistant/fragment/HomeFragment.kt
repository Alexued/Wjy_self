package com.example.aiassistant.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.ModelManager
import com.example.aiassistant.QuestionType
import com.example.aiassistant.R
import com.example.aiassistant.StrategyManager
import com.example.aiassistant.TeacherConfig
import com.example.aiassistant.TeacherManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class HomeFragment : Fragment() {

    interface ServiceControlListener {
        fun onStartServiceRequested()
        fun onStopServiceRequested()
        fun isServiceRunning(): Boolean
    }

    private var listener: ServiceControlListener? = null

    private lateinit var switchFloatBall: SwitchMaterial
    private lateinit var tvFloatStatus: TextView
    private lateinit var tvActiveTeacher: TextView
    private lateinit var btnManageTeachers: MaterialButton
    private lateinit var layoutTypeChips: LinearLayout

    private lateinit var rgCaptureMode: RadioGroup
    private lateinit var layoutFixedInfo: LinearLayout
    private lateinit var tvFixedStatus: TextView
    private lateinit var btnClearFixed: MaterialButton

    private lateinit var switchDefaultFullscreen: SwitchMaterial
    private lateinit var switchSilentSearch: SwitchMaterial
    private lateinit var sbBallSize: SeekBar
    private lateinit var tvBallSizeVal: TextView

    private lateinit var rgMultiPassStrategy: RadioGroup
    private lateinit var layoutCustomR2Prompt: View
    private lateinit var etCustomR2Prompt: TextInputEditText
    private lateinit var layoutSelfCheckInstruction: View
    private lateinit var etSelfCheckInstruction: TextInputEditText
    private lateinit var btnEditStrategy: MaterialButton

    private var currentQuestionType = QuestionType.PIAN_DUAN_YUE_DU

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
    }

    override fun onResume() {
        super.onResume()
        updateFloatStatus(listener?.isServiceRunning() == true)
        updateTeacherDisplay()
    }

    private fun updateTeacherDisplay() {
        tvActiveTeacher.text = TeacherManager.activeTeacher.name
    }

    private fun bindViews(view: View) {
        switchFloatBall = view.findViewById(R.id.switch_float_ball)
        tvFloatStatus = view.findViewById(R.id.tv_float_status)
        tvActiveTeacher = view.findViewById(R.id.tv_active_teacher)
        btnManageTeachers = view.findViewById(R.id.btn_manage_teachers)
        layoutTypeChips = view.findViewById(R.id.layout_type_chips)

        rgCaptureMode = view.findViewById(R.id.rg_capture_mode)
        layoutFixedInfo = view.findViewById(R.id.layout_fixed_info)
        tvFixedStatus = view.findViewById(R.id.tv_fixed_status)
        btnClearFixed = view.findViewById(R.id.btn_clear_fixed)

        switchDefaultFullscreen = view.findViewById(R.id.switch_default_fullscreen)
        switchSilentSearch = view.findViewById(R.id.switch_silent_search)
        sbBallSize = view.findViewById(R.id.sb_ball_size)
        tvBallSizeVal = view.findViewById(R.id.tv_ball_size_val)

        rgMultiPassStrategy = view.findViewById(R.id.rg_multi_pass_strategy)
        layoutCustomR2Prompt = view.findViewById(R.id.layout_custom_r2_prompt)
        etCustomR2Prompt = view.findViewById(R.id.et_custom_r2_prompt)
        layoutSelfCheckInstruction = view.findViewById(R.id.layout_self_check_instruction)
        etSelfCheckInstruction = view.findViewById(R.id.et_self_check_instruction)
        btnEditStrategy = view.findViewById(R.id.btn_edit_strategy)
    }

    private fun loadConfig() {
        val ctx = requireContext()
        val running = listener?.isServiceRunning() == true
        switchFloatBall.isChecked = running
        updateFloatStatus(running)

        switchDefaultFullscreen.isChecked = AppPreferences.isDefaultFullscreen(ctx)
        switchSilentSearch.isChecked = AppPreferences.isSilentSearchEnabled(ctx)

        currentQuestionType = AppPreferences.getCurrentQuestionType(ctx)

        val mode = AppPreferences.getCaptureMode(ctx)
        rgCaptureMode.check(if (mode == AppPreferences.MODE_FIXED_AREA) R.id.rb_fixed_area else R.id.rb_custom_area)
        updateFixedUI(mode)

        val ballSize = AppPreferences.getFloatBallSize(ctx)
        sbBallSize.progress = ballSize
        tvBallSizeVal.text = "${ballSize}dp"

        val activeScheme = StrategyManager.activeScheme
        rgMultiPassStrategy.check(
            when (activeScheme?.id) {
                "builtin_single" -> R.id.rb_strategy_single
                "builtin_selfcheck" -> R.id.rb_strategy_self_check
                "builtin_customr2" -> R.id.rb_strategy_custom_r2
                else -> R.id.rb_strategy_standard
            }
        )
        etCustomR2Prompt.setText(activeScheme?.customR2Prompt ?: "")
        etSelfCheckInstruction.setText(TeacherManager.getSelfCheckInstruction())
    }

    private fun setupListeners() {
        val ctx = requireContext()

        btnManageTeachers.setOnClickListener { showTeacherDialog() }

        switchFloatBall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                listener?.onStartServiceRequested()
            } else {
                listener?.onStopServiceRequested()
                AppPreferences.setFloatEnabled(ctx, false)
                updateFloatStatus(false)
            }
        }

        rgCaptureMode.setOnCheckedChangeListener { _, id ->
            val mode = if (id == R.id.rb_fixed_area) AppPreferences.MODE_FIXED_AREA else AppPreferences.MODE_CUSTOM_AREA
            AppPreferences.setCaptureMode(ctx, mode)
            updateFixedUI(mode)
        }

        btnClearFixed.setOnClickListener {
            AppPreferences.clearFixedRegion(ctx)
            updateFixedUI(AppPreferences.MODE_FIXED_AREA)
            Toast.makeText(ctx, "已清除固定区域，下次截图时将重新选择", Toast.LENGTH_SHORT).show()
        }

        switchDefaultFullscreen.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setDefaultFullscreen(ctx, isChecked)
        }

        switchSilentSearch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setSilentSearchEnabled(ctx, isChecked)
        }

        sbBallSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress.coerceAtLeast(30)
                tvBallSizeVal.text = "${size}dp"
                AppPreferences.setFloatBallSize(ctx, size)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnEditStrategy.setOnClickListener { showStrategyEditDialog() }

        rgMultiPassStrategy.setOnCheckedChangeListener { _, id ->
            val schemeId = when (id) {
                R.id.rb_strategy_single -> "builtin_single"
                R.id.rb_strategy_self_check -> "builtin_selfcheck"
                R.id.rb_strategy_custom_r2 -> "builtin_customr2"
                else -> "builtin_standard"
            }
            StrategyManager.activate(ctx, schemeId)
            val scheme = StrategyManager.activeScheme
            // 单轮模式：强制重置 R2/R3 为不使用
            if (schemeId == "builtin_single" && scheme != null) {
                if (!scheme.isSingleRound()) {
                    val fixed = scheme.copy(r2ModelId = "none", r3ModelId = "none")
                    StrategyManager.update(ctx, fixed)
                    StrategyManager.activate(ctx, fixed.id)
                }
            }
            updateMultiPassInputs(StrategyManager.activeScheme?.id ?: "")
        }

        // 卡片弹出方式
        val btnCardMode = view?.findViewById<TextView>(R.id.btn_card_mode) ?: return
        val tvCardModeDesc = view?.findViewById<TextView>(R.id.tv_card_mode_desc) ?: return
        fun updateCardModeDisplay() {
            tvCardModeDesc.text = when (AppPreferences.getCardDisplayMode(ctx)) {
                AppPreferences.CARD_MODE_BOTTOM -> "底部弹出"
                AppPreferences.CARD_MODE_ATTACHED -> "附着悬浮球"
                else -> "自由悬浮"
            }
        }
        updateCardModeDisplay()
        btnCardMode.setOnClickListener {
            val modes = arrayOf("自由悬浮", "底部弹出", "附着悬浮球")
            val current = AppPreferences.getCardDisplayMode(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("卡片弹出方式")
                .setSingleChoiceItems(modes, current) { dialog, which ->
                    AppPreferences.setCardDisplayMode(ctx, which)
                    updateCardModeDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun buildTypeChips() {
        layoutTypeChips.removeAllViews()
        val d = requireContext().resources.displayMetrics.density
        val dp8 = (8 * d).toInt(); val dp4 = (4 * d).toInt()
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

    private fun updateFixedUI(mode: Int) {
        val ctx = requireContext()
        if (mode == AppPreferences.MODE_FIXED_AREA) {
            layoutFixedInfo.visibility = View.VISIBLE
            if (AppPreferences.isFixedRegionSet(ctx)) {
                val r = AppPreferences.getFixedRegion(ctx)
                tvFixedStatus.text = "已设定区域：${r.width()}×${r.height()} @ (${r.left}, ${r.top})"
                tvFixedStatus.setTextColor(ctx.getColor(R.color.primary))
            } else {
                tvFixedStatus.text = "尚未设定，下次点击悬浮球时将框选"
                tvFixedStatus.setTextColor(ctx.getColor(R.color.text_secondary))
            }
        } else {
            layoutFixedInfo.visibility = View.GONE
        }
    }

    private fun updateMultiPassInputs(schemeId: String) {
        layoutCustomR2Prompt.visibility = if (schemeId == "builtin_customr2") View.VISIBLE else View.GONE
        layoutSelfCheckInstruction.visibility = if (schemeId == "builtin_selfcheck") View.VISIBLE else View.GONE
    }

    private fun showStrategyEditDialog() {
        val ctx = requireContext()
        val scheme = StrategyManager.activeScheme ?: return
        val models = ModelManager.allModels
        if (models.isEmpty()) { Toast.makeText(ctx, "请先添加AI模型", Toast.LENGTH_SHORT).show(); return }

        val modelNames = models.map { it.name }.toTypedArray()
        val modelIds = models.map { it.id }.toTypedArray()

        fun pickModel(label: String, currentId: String, onResult: (String) -> Unit) {
            val checkedIdx = modelIds.indexOf(currentId).coerceAtLeast(0)
            AlertDialog.Builder(ctx)
                .setTitle("选择 $label 模型")
                .setSingleChoiceItems(modelNames, checkedIdx) { dialog, which ->
                    dialog.dismiss()
                    onResult(modelIds[which])
                }
                .setNeutralButton("不使用") { dialog, _ ->
                    dialog.dismiss()
                    onResult("none")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        fun pickThinking(label: String, current: Boolean?, onResult: (Boolean?) -> Unit) {
            AlertDialog.Builder(ctx)
                .setTitle("$label 思考模式")
                .setSingleChoiceItems(arrayOf("模型默认", "强制开启", "强制关闭"),
                    when (current) { null -> 0; true -> 1; false -> 2 }) { dialog, which ->
                        dialog.dismiss()
                        onResult(when (which) { 0 -> null; 1 -> true; else -> false })
                    }
                .setNegativeButton("取消", null)
                .show()
        }

        var r1Id = scheme.r1ModelId
        var r1Think = scheme.r1Thinking
        var r2Id = scheme.r2ModelId
        var r2Think = scheme.r2Thinking
        var r3Id = scheme.r3ModelId
        var r3Think = scheme.r3Thinking

        fun buildDialog() {
            val r1Name = models.find { it.id == r1Id }?.name ?: (if (r1Id == "none") "不使用" else "—")
            val r2Name = models.find { it.id == r2Id }?.name ?: (if (r2Id == "none") "不使用" else "—")
            val r3Name = models.find { it.id == r3Id }?.name ?: (if (r3Id == "none") "不使用" else "—")

            val thinkLabel = { b: Boolean? -> when(b) { null -> "默认"; true -> "开启"; false -> "关闭" } }

            val isSingle = r2Id == "none" && r3Id == "none"
            val items = if (isSingle) {
                arrayOf("R1（第一轮）：$r1Name  |  思考：${thinkLabel(r1Think)}")
            } else {
                arrayOf(
                    "R1（第一轮）：$r1Name  |  思考：${thinkLabel(r1Think)}",
                    "R2（第二轮）：$r2Name  |  思考：${thinkLabel(r2Think)}",
                    "R3（审核轮）：$r3Name  |  思考：${thinkLabel(r3Think)}"
                )
            }
            AlertDialog.Builder(ctx)
                .setTitle("编辑方案：${scheme.name}${if (isSingle) "（单轮）" else ""}")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> pickModel("R1", r1Id) { r1Id = it; buildDialog() }
                        1 -> if (!isSingle) pickModel("R2", r2Id) { r2Id = it; buildDialog() }
                        2 -> if (!isSingle) pickModel("R3", r3Id) { r3Id = it; buildDialog() }
                    }
                }
                .setPositiveButton("保存") { _, _ ->
                    val updated = scheme.copy(
                        r1ModelId = r1Id, r1Thinking = r1Think,
                        r2ModelId = r2Id, r2Thinking = r2Think,
                        r3ModelId = r3Id, r3Thinking = r3Think
                    )
                    StrategyManager.update(ctx, updated)
                    StrategyManager.activate(ctx, updated.id)
                    Toast.makeText(ctx, "方案已更新", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("编辑思考模式") { _, _ ->
                    if (isSingle) {
                        pickThinking("R1", r1Think) { r1Think = it; buildDialog() }
                    } else {
                        pickThinking("R1", r1Think) { r1Think = it
                            pickThinking("R2", r2Think) { r2Think = it
                                pickThinking("R3", r3Think) { r3Think = it; buildDialog() }
                            }
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        buildDialog()
    }

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

    override fun onPause() {
        super.onPause()
        val ctx = requireContext()
        val scheme = StrategyManager.activeScheme ?: return
        val customR2 = etCustomR2Prompt.text?.toString()?.trim() ?: ""
        if (customR2.isNotBlank() && scheme.id == "builtin_customr2") {
            val updated = scheme.copy(customR2Prompt = customR2)
            StrategyManager.update(ctx, updated)
        }
        val selfCheck = etSelfCheckInstruction.text?.toString()?.trim() ?: ""
        if (selfCheck.isNotBlank()) {
            AppPreferences.setSelfCheckInstruction(ctx, selfCheck)
        }
    }
}
