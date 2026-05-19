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
import com.example.aiassistant.ModelManager
import com.example.aiassistant.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class AiModelFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private var adapter: ModelAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_ai_model, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv = view.findViewById(R.id.rv_model_list)
        rv.layoutManager = LinearLayoutManager(requireContext())
        view.findViewById<MaterialButton>(R.id.btn_add_model).setOnClickListener { showEditDialog(null) }
        view.findViewById<MaterialButton>(R.id.btn_prompt_manage).setOnClickListener {
            (requireActivity() as MainActivity).showFragment(
                (requireActivity() as MainActivity).getOrCreatePromptFragment()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModelList()
    }

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
