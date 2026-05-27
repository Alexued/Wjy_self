package com.example.aiassistant.questionbank

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R

class QuestionBankFragment : Fragment() {

    private lateinit var rvModules: RecyclerView
    private lateinit var tvTotalCount: TextView
    private val readyListener: () -> Unit = { loadModules() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_question_bank, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvModules = view.findViewById(R.id.rv_modules)
        tvTotalCount = view.findViewById(R.id.tv_total_count)

        rvModules.layoutManager = LinearLayoutManager(requireContext())

        loadModules()
    }

    override fun onResume() {
        super.onResume()
        QuestionBankManager.addOnReadyListener(readyListener)
        loadModules()
    }

    override fun onPause() {
        super.onPause()
        QuestionBankManager.removeOnReadyListener(readyListener)
    }

    private fun loadModules() {
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
}
