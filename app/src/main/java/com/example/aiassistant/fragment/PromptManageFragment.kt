package com.example.aiassistant.fragment

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.TeacherManager
import com.example.aiassistant.QuestionType
import com.example.aiassistant.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class PromptManageFragment : Fragment() {

    private lateinit var rvList: RecyclerView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_prompt_manage, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvList = view.findViewById(R.id.rv_prompt_list)
        rvList.layoutManager = LinearLayoutManager(requireContext())
        rvList.adapter = PromptAdapter(QuestionType.entries.toList())
    }

    override fun onResume() {
        super.onResume()
        // 老师切换后刷新 prompt 显示
        rvList.adapter?.notifyDataSetChanged()
    }
}

private class PromptAdapter(
    private val types: List<QuestionType>
) : RecyclerView.Adapter<PromptAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_type_name)
        val tvVisionBadge: TextView = view.findViewById(R.id.tv_vision_badge)
        val etPrompt: TextInputEditText = view.findViewById(R.id.et_type_prompt)
        val btnReset: MaterialButton = view.findViewById(R.id.btn_reset_prompt)
        var currentType: QuestionType? = null
        var ignoreTextChange = false
        var currentWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_question_type_prompt, parent, false)
        return VH(view)
    }

    override fun getItemCount() = types.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val type = types[position]
        val ctx = holder.itemView.context
        holder.currentType = type
        holder.tvName.text = type.displayName
        holder.tvVisionBadge.visibility = if (type.usesVision) View.VISIBLE else View.GONE

        // 移除旧监听器，避免重复
        holder.currentWatcher?.let { holder.etPrompt.removeTextChangedListener(it) }

        holder.ignoreTextChange = true
        holder.etPrompt.setText(TeacherManager.getPrompt(ctx, type))
        holder.ignoreTextChange = false

        val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var saveRunnable: Runnable? = null
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (holder.ignoreTextChange) return
                val t = holder.currentType ?: return
                val text = s?.toString()?.trim() ?: ""
                val teacher = TeacherManager.activeTeacher
                if (text.isNotEmpty()) {
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    saveRunnable = Runnable { TeacherManager.setOverlay(ctx, teacher.id, t, text) }
                    saveHandler.postDelayed(saveRunnable!!, 300)
                }
            }
        }
        holder.currentWatcher = watcher
        holder.etPrompt.addTextChangedListener(watcher)

        holder.btnReset.setOnClickListener {
            val t = holder.currentType ?: return@setOnClickListener
            val teacher = TeacherManager.activeTeacher
            TeacherManager.removeOverlay(ctx, teacher.id, t)
            holder.ignoreTextChange = true
            holder.etPrompt.setText(teacher.getPrompt(t))
            holder.ignoreTextChange = false
            Toast.makeText(ctx, "${t.displayName} 已恢复默认", Toast.LENGTH_SHORT).show()
        }
    }
}
