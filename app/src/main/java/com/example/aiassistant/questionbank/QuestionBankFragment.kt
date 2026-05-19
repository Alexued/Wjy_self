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
            return
        }

        QuestionBankManager.getModulesAsync { modules ->
            val totalQuestions = modules.sumOf { module ->
                module.questionCount + module.children.sumOf { it.questionCount }
            }
            activity?.runOnUiThread {
                tvTotalCount.text = "共 ${totalQuestions} 题"
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
            holder.tvQuestionCount.text = "${module.questionCount + module.children.sumOf { it.questionCount }} 题"

            // 设置展开/收起状态
            val isExpanded = holder.layoutChildren.visibility == View.VISIBLE
            holder.ivExpand.rotation = if (isExpanded) 180f else 0f

            // 点击展开/收起
            holder.itemView.setOnClickListener {
                val expanded = holder.layoutChildren.visibility == View.VISIBLE
                if (expanded) {
                    holder.layoutChildren.visibility = View.GONE
                    holder.ivExpand.animate().rotation(0f).setDuration(200).start()
                } else {
                    holder.layoutChildren.visibility = View.VISIBLE
                    holder.ivExpand.animate().rotation(180f).setDuration(200).start()
                    // 添加子模块视图
                    addChildViews(holder.layoutChildren, module.children)
                }
            }
        }

        override fun getItemCount() = modules.size

        private fun addChildViews(container: LinearLayout, children: List<QuestionModule>) {
            container.removeAllViews()
            for (child in children) {
                val childView = LayoutInflater.from(container.context)
                    .inflate(R.layout.item_module_child, container, false)

                childView.findViewById<TextView>(R.id.tv_child_name).text = child.name
                childView.findViewById<TextView>(R.id.tv_child_count).text = "${child.questionCount} 题"

                childView.setOnClickListener {
                    onChildClick(child)
                }

                container.addView(childView)
            }
        }
    }
}
