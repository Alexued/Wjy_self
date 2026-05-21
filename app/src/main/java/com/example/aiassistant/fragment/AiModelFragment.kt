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
import com.example.aiassistant.StrategyManager
import com.example.aiassistant.TeacherManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class AiModelFragment : Fragment() {

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
    private lateinit var sbBallSize: SeekBar
    private lateinit var tvBallSizeVal: TextView

    // 多轮推理策略
    private lateinit var rgMultiPassStrategy: RadioGroup
    private lateinit var layoutCustomR2Prompt: View
    private lateinit var etCustomR2Prompt: TextInputEditText
    private lateinit var layoutSelfCheckInstruction: View
    private lateinit var etSelfCheckInstruction: TextInputEditText
    private lateinit var btnEditStrategy: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_ai_model, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // AI 模型管理
        rv = view.findViewById(R.id.rv_model_list)
        rv.layoutManager = LinearLayoutManager(requireContext())
        view.findViewById<MaterialButton>(R.id.btn_add_model).setOnClickListener { showEditDialog(null) }
        view.findViewById<MaterialButton>(R.id.btn_prompt_manage).setOnClickListener {
            (requireActivity() as MainActivity).showFragment(
                (requireActivity() as MainActivity).getOrCreatePromptFragment()
            )
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
        adapter = ModelAdapter(ModelManager.allModels.toList(),
            onEdit = { showEditDialog(it) },
            onDelete = { showDeleteConfirm(it) })
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

        if (config != null) {
            etName.setText(config.name)
            etUrl.setText(config.baseUrl)
            etKey.setText(config.apiKey)
            etModel.setText(config.model)
            swThink.isChecked = config.thinkingDefault
        }

        val dialog = AlertDialog.Builder(ctx, R.style.TransparentDialog)
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString()?.trim() ?: return@setPositiveButton
                val url = etUrl.text?.toString()?.trim() ?: ""
                val key = etKey.text?.toString()?.trim() ?: ""
                val model = etModel.text?.toString()?.trim() ?: ""
                if (name.isBlank()) return@setPositiveButton
                val newConfig = AiModelConfig(
                    id = config?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name, baseUrl = url, apiKey = key, model = model,
                    thinkingDefault = swThink.isChecked
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
        sbBallSize = view.findViewById(R.id.sb_ball_size)
        tvBallSizeVal = view.findViewById(R.id.tv_ball_size_val)

        rgMultiPassStrategy = view.findViewById(R.id.rg_multi_pass_strategy)
        layoutCustomR2Prompt = view.findViewById(R.id.layout_custom_r2_prompt)
        etCustomR2Prompt = view.findViewById(R.id.et_custom_r2_prompt)
        layoutSelfCheckInstruction = view.findViewById(R.id.layout_self_check_instruction)
        etSelfCheckInstruction = view.findViewById(R.id.et_self_check_instruction)
        btnEditStrategy = view.findViewById(R.id.btn_edit_strategy)
    }

    private fun loadSettingsConfig() {
        val ctx = requireContext()

        switchDefaultFullscreen.isChecked = AppPreferences.isDefaultFullscreen(ctx)
        switchSilentSearch.isChecked = AppPreferences.isSilentSearchEnabled(ctx)

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

    // ── 策略编辑弹窗 ──────────────────────────────────────────────────

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
}

private class ModelAdapter(
    private val items: List<AiModelConfig>,
    private val onEdit: (AiModelConfig) -> Unit,
    private val onDelete: (AiModelConfig) -> Unit
) : RecyclerView.Adapter<ModelAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
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
        h.tvName.text = m.name
        h.tvDetail.text = "${m.model}  |  ${m.baseUrl}"
        h.tvThinking.visibility = if (m.thinkingDefault) View.VISIBLE else View.GONE
        h.btnEdit.setOnClickListener { onEdit(m) }
        h.btnDelete.setOnClickListener { onDelete(m) }
    }
}
