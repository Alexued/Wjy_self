package com.example.aiassistant.fragment

import com.example.aiassistant.MainActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.AiModelConfig
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.ModelManager
import com.example.aiassistant.R
import com.example.aiassistant.TeacherManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class AiModelFragment : Fragment() {

    // 数据备份多选状态缓存：[系统设置, 错题本, 知识卡片, 本地题库]
    private var exportOptions = booleanArrayOf(true, true, true, true)

    private val exportDataLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            performExport(uri)
        }
    }

    private val importDataLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            performImport(uri)
        }
    }

    private val importBankLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showBankImportDialog(uri)
        }
    }

    private lateinit var rv: RecyclerView
    private var adapter: ModelAdapter? = null

    // 截图模式
    private lateinit var rgCaptureMode: RadioGroup
    private lateinit var layoutFixedInfo: LinearLayout
    private lateinit var tvFixedStatus: TextView
    private lateinit var btnClearFixed: MaterialButton

    // 显示设置
    private lateinit var switchDefaultFullscreen: SwitchMaterial
    private lateinit var switchSilentSearch: SwitchMaterial
    private lateinit var switchToolCalling: SwitchMaterial
    private lateinit var sbBallSize: SeekBar
    private lateinit var tvBallSizeVal: TextView
    private lateinit var btnEditCustomQuotes: MaterialButton
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_ai_model, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // AI 模型管理
        rv = view.findViewById(R.id.rv_model_list)
        rv.layoutManager = LinearLayoutManager(requireContext())
        view.findViewById<MaterialButton>(R.id.btn_add_model).setOnClickListener { showEditDialog(null) }
        
        view.findViewById<MaterialButton>(R.id.btn_teacher_manage).setOnClickListener {
            showTeacherDialog()
        }

        bindSettingsViews(view)
        loadSettingsConfig()
        setupSettingsListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshModelList()
    }

    // ── AI 模型管理 ──────────────────────────────────────────────────

    private fun refreshModelList() {
        val ctx = requireContext()
        var activeModelId = AppPreferences.getActiveModelId(ctx)
        
        // 如果当前没有设置过首选模型，且模型列表不为空，自动以第一个模型作为默认启用模型
        val allModels = ModelManager.allModels
        if (activeModelId.isEmpty() && allModels.isNotEmpty()) {
            activeModelId = allModels.first().id
            AppPreferences.setActiveModelId(ctx, activeModelId)
        }
        
        adapter = ModelAdapter(
            items = allModels.toList(),
            activeModelId = activeModelId,
            onSelect = { selectedModel ->
                AppPreferences.setActiveModelId(ctx, selectedModel.id)
                Toast.makeText(ctx, "已启用大模型「${selectedModel.name}」作为分析主模型！", Toast.LENGTH_SHORT).show()
                refreshModelList()
            },
            onEdit = { showEditDialog(it) },
            onDelete = { showDeleteConfirm(it) }
        )
        rv.adapter = adapter
    }

    private fun showEditDialog(config: AiModelConfig?) {
        val ctx = requireContext()
        val isNew = config == null
        val inflater = LayoutInflater.from(ctx)
        val form = inflater.inflate(R.layout.dialog_model_edit, null) as LinearLayout
        form.findViewById<TextView>(R.id.tv_dialog_title).text = if (isNew) "添加模型" else "编辑模型"
        val etName = form.findViewById<TextInputEditText>(R.id.et_model_name)
        val etUrl = form.findViewById<TextInputEditText>(R.id.et_model_url)
        val etKey = form.findViewById<TextInputEditText>(R.id.et_model_key)
        val etModel = form.findViewById<TextInputEditText>(R.id.et_model_id)
        val swThink = form.findViewById<SwitchMaterial>(R.id.sw_model_thinking)
        val swVision = form.findViewById<SwitchMaterial>(R.id.sw_model_vision)
        val spApiType = form.findViewById<Spinner>(R.id.sp_model_api_type)
        
        // 获取分段选择器控件与包含容器
        val layoutBudgetGroup = form.findViewById<View>(R.id.layout_thinking_budget_group)
        val toggleBudget = form.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggle_thinking_budget)
        
        // 联动：只有默认开启思考模式时，才展示思考强度选择器
        swThink.setOnCheckedChangeListener { _, isChecked ->
            layoutBudgetGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val tilUrl = form.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_model_url)
        val tilModel = form.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_model_id)

        val apiTypes = arrayOf("OpenAI", "Anthropic", "Gemini")
        val apiAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, apiTypes)
        apiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spApiType.adapter = apiAdapter

        spApiType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { // OpenAI
                        tilUrl?.helperText = "将自动补全/v1，示例: https://api.openai.com"
                        tilModel?.helperText = "示例: gpt-4o 或 deepseek-reasoner"
                        if (isNew && etUrl.text.isNullOrBlank()) etUrl.setText("https://api.openai.com")
                        if (isNew && etModel.text.isNullOrBlank()) etModel.setText("gpt-4o")
                    }
                    1 -> { // Anthropic
                        tilUrl?.helperText = "示例: https://api.anthropic.com"
                        tilModel?.helperText = "示例: claude-3-5-sonnet-20241022"
                        if (isNew && etUrl.text.isNullOrBlank()) etUrl.setText("https://api.anthropic.com")
                        if (isNew && etModel.text.isNullOrBlank()) etModel.setText("claude-3-5-sonnet-20241022")
                    }
                    2 -> { // Gemini
                        tilUrl?.helperText = "官方直连填 https://generativelanguage.googleapis.com"
                        tilModel?.helperText = "示例: gemini-2.5-flash 或 gemini-1.5-pro"
                        if (isNew && etUrl.text.isNullOrBlank()) etUrl.setText("https://generativelanguage.googleapis.com")
                        if (isNew && etModel.text.isNullOrBlank()) etModel.setText("gemini-2.5-flash")
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        if (config != null) {
            etName.setText(config.name)
            etUrl.setText(config.baseUrl)
            etKey.setText(config.apiKey)
            etModel.setText(config.model)
            swThink.isChecked = config.thinkingDefault
            swVision.isChecked = config.isVision
            
            // 根据 Token 数量映射选中分段按钮
            val checkButtonId = when {
                config.thinkingBudget >= 4096 -> R.id.btn_budget_expert
                config.thinkingBudget >= 2048 -> R.id.btn_budget_deep
                config.thinkingBudget >= 1024 -> R.id.btn_budget_light
                else -> R.id.btn_budget_speed
            }
            toggleBudget.check(checkButtonId)

            val index = when (config.apiType.lowercase()) {
                "anthropic" -> 1
                "gemini" -> 2
                else -> 0
            }
            spApiType.setSelection(index)
        } else {
            // 默认选中“终极”档位 (4096)
            toggleBudget.check(R.id.btn_budget_expert)
        }

        // 初始化联动状态显示
        layoutBudgetGroup.visibility = if (swThink.isChecked) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(ctx, R.style.TransparentDialog)
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString()?.trim() ?: return@setPositiveButton
                val url = etUrl.text?.toString()?.trim() ?: ""
                val key = etKey.text?.toString()?.trim() ?: ""
                val model = etModel.text?.toString()?.trim() ?: ""
                if (name.isBlank()) return@setPositiveButton

                val apiType = when (spApiType.selectedItemPosition) {
                    1 -> "anthropic"
                    2 -> "gemini"
                    else -> "openai"
                }
                
                // 将分段按钮选择映射回底层的 Token 数量
                val budget = when (toggleBudget.checkedButtonId) {
                    R.id.btn_budget_speed -> 0
                    R.id.btn_budget_light -> 1024
                    R.id.btn_budget_deep -> 2048
                    R.id.btn_budget_expert -> 4096
                    else -> 4096
                }

                val newConfig = AiModelConfig(
                    id = config?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name, baseUrl = url, apiKey = key, model = model,
                    thinkingDefault = swThink.isChecked,
                    isVision = swVision.isChecked,
                    apiType = apiType,
                    thinkingBudget = budget
                )
                if (isNew) ModelManager.add(ctx, newConfig)
                else ModelManager.update(ctx, newConfig)
                refreshModelList()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setDimAmount(0f)
        dialog.show()
    }

    private fun showDeleteConfirm(config: AiModelConfig) {
        if (ModelManager.allModels.size <= 1) {
            Toast.makeText(requireContext(), "至少保留一个模型", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除模型")
            .setMessage("确定删除「${config.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                ModelManager.delete(requireContext(), config.id)
                refreshModelList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 设置项绑定 ──────────────────────────────────────────────────

    private fun bindSettingsViews(view: View) {
        rgCaptureMode = view.findViewById(R.id.rg_capture_mode)
        layoutFixedInfo = view.findViewById(R.id.layout_fixed_info)
        tvFixedStatus = view.findViewById(R.id.tv_fixed_status)
        btnClearFixed = view.findViewById(R.id.btn_clear_fixed)

        switchDefaultFullscreen = view.findViewById(R.id.switch_default_fullscreen)
        switchSilentSearch = view.findViewById(R.id.switch_silent_search)
        switchToolCalling = view.findViewById(R.id.switch_tool_calling)
        sbBallSize = view.findViewById(R.id.sb_ball_size)
        tvBallSizeVal = view.findViewById(R.id.tv_ball_size_val)
        btnEditCustomQuotes = view.findViewById(R.id.btn_edit_custom_quotes)
    }

    private fun loadSettingsConfig() {
        val ctx = requireContext()

        switchDefaultFullscreen.isChecked = AppPreferences.isDefaultFullscreen(ctx)
        switchSilentSearch.isChecked = AppPreferences.isSilentSearchEnabled(ctx)
        switchToolCalling.isChecked = AppPreferences.isToolCallingEnabled(ctx)

        val mode = AppPreferences.getCaptureMode(ctx)
        rgCaptureMode.check(if (mode == AppPreferences.MODE_FIXED_AREA) R.id.rb_fixed_area else R.id.rb_custom_area)
        updateFixedUI(mode)

        val ballSize = AppPreferences.getFloatBallSize(ctx)
        sbBallSize.progress = ballSize
        tvBallSizeVal.text = "${ballSize}dp"
    }

    private fun setupSettingsListeners() {
        val ctx = requireContext()

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

        switchToolCalling.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setToolCallingEnabled(ctx, isChecked)
            Toast.makeText(ctx, if (isChecked) "智能解题技能已启用" else "智能解题技能已禁用", Toast.LENGTH_SHORT).show()
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
            AlertDialog.Builder(ctx)
                .setTitle("卡片弹出方式")
                .setSingleChoiceItems(modes, current) { dialog, which ->
                    AppPreferences.setCardDisplayMode(ctx, which)
                    updateCardModeDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── 悬浮球长按行为 ──
        val btnLongPress = view?.findViewById<TextView>(R.id.btn_long_press) ?: return
        val tvLongPressDesc = view?.findViewById<TextView>(R.id.tv_long_press_desc) ?: return
        fun updateLongPressDisplay() {
            tvLongPressDesc.text = if (AppPreferences.getLongPressAction(ctx) == AppPreferences.LONG_PRESS_CLOSE) "直接关闭" else "打开菜单"
        }
        updateLongPressDisplay()
        btnLongPress.setOnClickListener {
            val options = arrayOf("打开菜单", "直接关闭")
            val current = if (AppPreferences.getLongPressAction(ctx) == AppPreferences.LONG_PRESS_CLOSE) 1 else 0
            AlertDialog.Builder(ctx)
                .setTitle("长按悬浮球行为")
                .setSingleChoiceItems(options, current) { dialog, which ->
                    AppPreferences.setLongPressAction(ctx, if (which == 1) AppPreferences.LONG_PRESS_CLOSE else AppPreferences.LONG_PRESS_MENU)
                    updateLongPressDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── 菜单项配置 ──
        val btnMenuItems = view?.findViewById<TextView>(R.id.btn_menu_items) ?: return
        val tvMenuItemsDesc = view?.findViewById<TextView>(R.id.tv_menu_items_desc) ?: return
        fun updateMenuItemsDisplay() {
            val items = AppPreferences.getBallMenuItems(ctx)
            val names = items.mapNotNull { id -> AppPreferences.ALL_MENU_ITEMS.find { it.first == id }?.second }
            tvMenuItemsDesc.text = names.joinToString(", ")
        }
        updateMenuItemsDisplay()
        btnMenuItems.setOnClickListener {
            val allItems = AppPreferences.ALL_MENU_ITEMS
            val currentItems = AppPreferences.getBallMenuItems(ctx)
            val checked = allItems.map { currentItems.contains(it.first) }.toBooleanArray()
            AlertDialog.Builder(ctx)
                .setTitle("选择菜单项")
                .setMultiChoiceItems(allItems.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("确定") { _, _ ->
                    val selected = allItems.mapIndexedNotNull { index, pair -> if (checked[index]) pair.first else null }
                    if (selected.isEmpty()) {
                        Toast.makeText(ctx, "至少选择一项", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    AppPreferences.setBallMenuItems(ctx, selected)
                    updateMenuItemsDisplay()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        btnEditCustomQuotes.setOnClickListener { showQuotesEditDialog() }

        // 数据备份与管理按钮绑定
        val btnExport = view?.findViewById<android.view.View>(R.id.btn_export_data)
        val btnImport = view?.findViewById<android.view.View>(R.id.btn_import_data)
        val btnImportBank = view?.findViewById<android.view.View>(R.id.btn_import_question_bank)

        btnExport?.setOnClickListener { showExportDialog() }
        btnImport?.setOnClickListener { triggerImport() }
        btnImportBank?.setOnClickListener { triggerImportBank() }
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



    private fun showTeacherDialog() {
        val ctx = requireContext()
        val teachers = TeacherManager.allTeachers
        val names = teachers.map { "${it.name}${if (it.id == TeacherManager.activeTeacher.id) " ✓" else ""}" }.toTypedArray()
        val ids = teachers.map { it.id }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("选择老师")
            .setItems(names) { _, which ->
                val selectedId = ids[which]
                if (selectedId != TeacherManager.activeTeacher.id) {
                    TeacherManager.switchTeacher(ctx, selectedId)
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
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("导入新老师")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val json = input.text?.toString()?.trim() ?: ""
                if (json.isBlank()) return@setPositiveButton
                TeacherManager.importTeacher(ctx, json)
                    .onSuccess {
                        TeacherManager.switchTeacher(ctx, it.id)
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
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showQuotesEditDialog() {
        val ctx = requireContext()
        val form = LayoutInflater.from(ctx).inflate(R.layout.dialog_quotes_edit, null)
        val rvList = form.findViewById<RecyclerView>(R.id.rv_quotes_list)
        val etText = form.findViewById<EditText>(R.id.et_quote_text)
        val etAuthor = form.findViewById<EditText>(R.id.et_quote_author)
        val btnAdd = form.findViewById<Button>(R.id.btn_add_quote)
        val btnReset = form.findViewById<Button>(R.id.btn_reset_quotes)

        val currentCustom = AppPreferences.getCustomQuotes(ctx)
        val workingList = if (currentCustom.isNotEmpty()) {
            currentCustom.toMutableList()
        } else {
            mutableListOf(
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
        }

        rvList.layoutManager = LinearLayoutManager(ctx)
        lateinit var quotesAdapter: QuotesAdapter
        quotesAdapter = QuotesAdapter(workingList) { index ->
            if (index in workingList.indices) {
                workingList.removeAt(index)
                quotesAdapter.notifyItemRemoved(index)
                quotesAdapter.notifyItemRangeChanged(index, workingList.size - index)
            }
        }
        rvList.adapter = quotesAdapter

        btnAdd.setOnClickListener {
            val text = etText.text?.toString()?.trim() ?: ""
            var author = etAuthor.text?.toString()?.trim() ?: ""
            if (text.isBlank()) {
                Toast.makeText(ctx, "名言正文不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (author.isNotEmpty() && !author.startsWith("——") && !author.startsWith("—")) {
                author = "—— $author"
            }
            workingList.add(Pair(text, author))
            quotesAdapter.notifyItemInserted(workingList.size - 1)
            rvList.scrollToPosition(workingList.size - 1)
            etText.setText("")
            etAuthor.setText("")
            Toast.makeText(ctx, "已加入临时库", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("确认恢复")
                .setMessage("是否确认恢复为系统内置的禅意名言？这会清空您的自定义设置。")
                .setPositiveButton("确认") { _, _ ->
                    workingList.clear()
                    workingList.addAll(
                        listOf(
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
                    )
                    quotesAdapter.notifyDataSetChanged()
                    Toast.makeText(ctx, "已恢复，点击「保存」生效", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val dialog = AlertDialog.Builder(ctx, R.style.TransparentDialog)
            .setView(form)
            .setPositiveButton("保存库内容") { _, _ ->
                AppPreferences.saveCustomQuotes(ctx, workingList)
                Toast.makeText(ctx, "名言库保存成功！主页刷新后生效。", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("放弃更改", null)
            .create()
        dialog.window?.setDimAmount(0.5f)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showExportDialog() {
        val ctx = context ?: return
        val items = arrayOf("系统设置 (Preferences)", "错题本记录 (Wrong Questions)", "知识卡片 (Knowledge Cards)", "本地题库 (Question Bank)")
        
        AlertDialog.Builder(ctx)
            .setTitle("选择要导出的数据")
            .setMultiChoiceItems(items, exportOptions) { _, which, isChecked ->
                exportOptions[which] = isChecked
            }
            .setPositiveButton("确定并保存") { _, _ ->
                var selectedAny = false
                for (opt in exportOptions) {
                    if (opt) {
                        selectedAny = true
                        break
                    }
                }
                if (!selectedAny) {
                    Toast.makeText(ctx, "至少选择一项进行导出", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                exportDataLauncher.launch("ai_assistant_backup.json")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun triggerImport() {
        importDataLauncher.launch(arrayOf("application/json", "text/*"))
    }

    private fun triggerImportBank() {
        importBankLauncher.launch(arrayOf("application/json", "text/*"))
    }

    private fun getWrongQuestionsJson(context: android.content.Context): String {
        return context.getSharedPreferences("wrong_questions_prefs", android.content.Context.MODE_PRIVATE)
            .getString("wrong_questions_list", "[]") ?: "[]"
    }

    private fun saveWrongQuestionsJson(context: android.content.Context, jsonStr: String) {
        context.getSharedPreferences("wrong_questions_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("wrong_questions_list", jsonStr)
            .apply()
    }

    private fun performExport(uri: android.net.Uri) {
        val ctx = context ?: return
        val dialog = android.app.ProgressDialog(ctx).apply {
            setMessage("正在打包导出备份，请稍候...")
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val root = org.json.JSONObject()
                root.put("backup_type", "ai_assistant_multi_backup")
                root.put("version", 1)
                root.put("timestamp", System.currentTimeMillis())

                if (exportOptions[0]) {
                    val prefStr = AppPreferences.exportPreferencesJson(ctx)
                    if (prefStr.isNotEmpty()) {
                        root.put("preferences", org.json.JSONObject(prefStr))
                    }
                }

                if (exportOptions[1]) {
                    val wqStr = getWrongQuestionsJson(ctx)
                    if (wqStr.isNotEmpty()) {
                        root.put("wrong_questions", org.json.JSONArray(wqStr))
                    }
                }

                if (exportOptions[2]) {
                    val cardsStr = com.example.aiassistant.knowledge.KnowledgeCardDb(ctx).exportAllCardsJson()
                    if (cardsStr.isNotEmpty()) {
                        root.put("knowledge_cards", org.json.JSONObject(cardsStr))
                    }
                }

                if (exportOptions[3]) {
                    val bankStr = com.example.aiassistant.questionbank.QuestionBankDb(ctx).exportQuestionsJson()
                    if (bankStr.isNotEmpty()) {
                        root.put("question_bank", org.json.JSONObject(bankStr))
                    }
                }

                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(root.toString(2).toByteArray(Charsets.UTF_8))
                }

                activity?.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(ctx, "🎉 数据备份导出成功！", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun performImport(uri: android.net.Uri) {
        val ctx = context ?: return
        val dialog = android.app.ProgressDialog(ctx).apply {
            setMessage("正在还原备份，请稍候...")
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val jsonStr = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    ins.bufferedReader().use { it.readText() }
                } ?: ""

                if (jsonStr.isBlank()) {
                    throw Exception("备份文件为空")
                }

                val root = org.json.JSONObject(jsonStr)
                val type = root.optString("backup_type")

                var prefRestored = false
                var wqCount = 0
                var cardCount = 0
                var qbCount = 0

                if (type == "ai_assistant_multi_backup") {
                    if (root.has("preferences")) {
                        val prefObj = root.getJSONObject("preferences")
                        AppPreferences.importPreferencesJson(ctx, prefObj.toString())
                        prefRestored = true
                    }
                    if (root.has("wrong_questions")) {
                        val wqArr = root.getJSONArray("wrong_questions")
                        saveWrongQuestionsJson(ctx, wqArr.toString())
                        wqCount = wqArr.length()
                    }
                    if (root.has("knowledge_cards")) {
                        val cardsObj = root.getJSONObject("knowledge_cards")
                        val db = com.example.aiassistant.knowledge.KnowledgeCardDb(ctx)
                        cardCount = db.importCardsFromJson(cardsObj.toString())
                    }
                    if (root.has("question_bank")) {
                        val qbObj = root.getJSONObject("question_bank")
                        val db = com.example.aiassistant.questionbank.QuestionBankDb(ctx)
                        qbCount = db.importQuestionsFromJson(qbObj.toString())
                    }
                } else {
                    when (type) {
                        "preferences" -> {
                            AppPreferences.importPreferencesJson(ctx, jsonStr)
                            prefRestored = true
                        }
                        "knowledge_cards" -> {
                            val db = com.example.aiassistant.knowledge.KnowledgeCardDb(ctx)
                            cardCount = db.importCardsFromJson(jsonStr)
                        }
                        "question_bank" -> {
                            val db = com.example.aiassistant.questionbank.QuestionBankDb(ctx)
                            qbCount = db.importQuestionsFromJson(jsonStr)
                        }
                        else -> {
                            if (jsonStr.trim().startsWith("[")) {
                                val arr = org.json.JSONArray(jsonStr)
                                saveWrongQuestionsJson(ctx, arr.toString())
                                wqCount = arr.length()
                            } else {
                                throw Exception("未识别的备份文件格式")
                            }
                        }
                    }
                }

                activity?.runOnUiThread {
                    dialog.dismiss()
                    val sb = StringBuilder("🎉 备份数据已成功恢复：\n")
                    if (prefRestored) sb.append("• 系统配置已覆盖应用\n")
                    if (wqCount > 0) sb.append("• 错题本恢复 $wqCount 道错题\n")
                    if (cardCount > 0) sb.append("• 知识卡片新增 $cardCount 张卡片\n")
                    if (qbCount > 0) sb.append("• 题库恢复 $qbCount 道题\n")
                    if (!prefRestored && wqCount == 0 && cardCount == 0 && qbCount == 0) {
                        sb.append("• 无新增增量数据（已排重合并）")
                    }

                    AlertDialog.Builder(ctx)
                        .setTitle("恢复成功")
                        .setMessage(sb.toString())
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(ctx, "恢复失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showBankImportDialog(uri: android.net.Uri) {
        val ctx = context ?: return
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvTitle = TextView(ctx).apply {
            text = "自定义题库导入配置"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * resources.displayMetrics.density).toInt()
            }
        }
        layout.addView(tvTitle)

        val etParent = EditText(ctx).apply {
            hint = "目标一级大分类 (例如: 判断推理)"
            setText("自定义大分类")
            textSize = 14f
            setBackgroundResource(R.drawable.bg_default_chip)
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        layout.addView(etParent)

        val etChild = EditText(ctx).apply {
            hint = "目标二级子分类 (例如: 类比推理)"
            setText("自定义子分类")
            textSize = 14f
            setBackgroundResource(R.drawable.bg_default_chip)
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(etChild)

        AlertDialog.Builder(ctx, R.style.TransparentDialog)
            .setView(layout)
            .setPositiveButton("开始导入") { _, _ ->
                val parentName = etParent.text.toString().trim()
                val childName = etChild.text.toString().trim()
                if (parentName.isEmpty() || childName.isEmpty()) {
                    Toast.makeText(ctx, "分类名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performBankImport(uri, parentName, childName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performBankImport(uri: android.net.Uri, parentModule: String, childModule: String) {
        val ctx = context ?: return
        val dialog = android.app.ProgressDialog(ctx).apply {
            setMessage("正在解析并导入自定义题库，请稍候...")
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val jsonStr = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    ins.bufferedReader().use { it.readText() }
                } ?: ""

                if (jsonStr.isBlank()) {
                    throw Exception("选择的题库文件为空")
                }

                val db = com.example.aiassistant.questionbank.QuestionBankDb(ctx)
                val count = db.importQuestionsFromJson(jsonStr, parentModule, childModule)

                activity?.runOnUiThread {
                    dialog.dismiss()
                    if (count >= 0) {
                        com.example.aiassistant.questionbank.QuestionBankManager.init(ctx)

                        AlertDialog.Builder(ctx)
                            .setTitle("导入成功")
                            .setMessage("🎉 自定义题库成功入库！\n\n大分类：$parentModule\n子分类：$childModule\n入库题数：$count 道题目")
                            .setPositiveButton("确定", null)
                            .show()
                    } else {
                        val err = when (count) {
                            -1 -> "该文件是整库备份，请使用【恢复备份】功能导入"
                            -2 -> "分类参数错误"
                            else -> "题库 JSON 语法格式不匹配"
                        }
                        Toast.makeText(ctx, "导入失败: $err", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(ctx, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}

private class QuotesAdapter(
    private val items: MutableList<Pair<String, String>>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<QuotesAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tv_item_quote_text)
        val tvAuthor: TextView = view.findViewById(R.id.tv_item_quote_author)
        val btnDelete: View = view.findViewById(R.id.btn_delete_quote)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_quote_edit, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.tvText.text = item.first
        h.tvAuthor.text = item.second.ifBlank { "无名氏" }
        h.btnDelete.setOnClickListener { onDelete(h.bindingAdapterPosition) }
    }
}

private class ModelAdapter(
    private val items: List<AiModelConfig>,
    private val activeModelId: String,
    private val onSelect: (AiModelConfig) -> Unit,
    private val onEdit: (AiModelConfig) -> Unit,
    private val onDelete: (AiModelConfig) -> Unit
) : RecyclerView.Adapter<ModelAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cardRoot: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.card_model_root)
        val tvName: TextView = view.findViewById(R.id.tv_model_name)
        val tvDetail: TextView = view.findViewById(R.id.tv_model_detail)
        val tvThinking: TextView = view.findViewById(R.id.tv_model_thinking)
        val btnEdit: View = view.findViewById(R.id.btn_model_edit)
        val btnDelete: View = view.findViewById(R.id.btn_model_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_card, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        val context = h.itemView.context
        val density = context.resources.displayMetrics.density
        
        val isActive = m.id == activeModelId
        if (isActive) {
            h.tvName.text = "✓  ${m.name}"
            h.tvName.setTextColor(context.getColor(R.color.primary))
            h.cardRoot.setStrokeColor(android.content.res.ColorStateList.valueOf(context.getColor(R.color.primary)))
            h.cardRoot.strokeWidth = (2.5f * density).toInt()
            // 浅绿色微光底色 (#EBF2EE)，透明度约 8%
            h.cardRoot.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(0x155C8271.toInt()))
        } else {
            h.tvName.text = m.name
            h.tvName.setTextColor(context.getColor(R.color.text_primary))
            h.cardRoot.setStrokeColor(android.content.res.ColorStateList.valueOf(context.getColor(R.color.border_light)))
            h.cardRoot.strokeWidth = (1.0f * density).toInt()
            h.cardRoot.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(context.getColor(R.color.background_card_glass)))
        }

        h.tvDetail.text = "${m.model}  |  ${m.baseUrl.ifBlank { "Google AI Studio 官方" }}"
        
        val tags = mutableListOf<String>()
        if (m.apiType != "openai") tags.add(m.apiType.uppercase())
        if (m.thinkingDefault) tags.add("思考")
        if (m.isVision) tags.add("识图")
        h.tvThinking.text = tags.joinToString(" • ")
        h.tvThinking.visibility = if (tags.isNotEmpty()) View.VISIBLE else View.GONE
        
        // 整个卡片区域（除编辑和删除外）点击触发设为首选大模型！
        h.cardRoot.setOnClickListener { onSelect(m) }
        h.btnEdit.setOnClickListener { onEdit(m) }
        h.btnDelete.setOnClickListener { onDelete(m) }
    }
}
