package com.example.aiassistant.knowledge

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R

class KnowledgeCardFragment : Fragment() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var btnImport: TextView
    private lateinit var adapter: CategoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_knowledge, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvCategories = view.findViewById(R.id.rv_categories)
        btnImport = view.findViewById(R.id.btn_import)

        adapter = CategoryAdapter { category ->
            val intent = Intent(requireContext(), KnowledgeCardListActivity::class.java)
            intent.putExtra("category_id", category.id)
            intent.putExtra("category_name", category.name)
            startActivity(intent)
        }

        rvCategories.layoutManager = GridLayoutManager(requireContext(), 2)
        rvCategories.adapter = adapter

        btnImport.setOnClickListener {
            showImportDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
    }

    private fun loadCategories() {
        val categories = KnowledgeCardManager.getVisibleCategories()
        val counts = categories.associate { it.id to KnowledgeCardManager.getCategoryCount(it.id) }
        adapter.setData(categories, counts)
    }

    private fun showImportDialog() {
        val categories = KnowledgeCardManager.getAllCategories()
        val names = categories.map { "${it.icon} ${it.name}" }.toTypedArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("选择导入分类")
            .setItems(names) { _, which ->
                val category = categories[which]
                showImportInputDialog(category)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImportInputDialog(category: KnowledgeCategory) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "每行一条：标题,内容,标签（可选）"
            setPadding(48, 32, 48, 32)
            minLines = 5
            gravity = android.view.Gravity.TOP
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("导入到 ${category.icon} ${category.name}")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    val count = KnowledgeCardManager.importFromCsv(category.id, text)
                    android.widget.Toast.makeText(requireContext(), "成功导入 $count 条", android.widget.Toast.LENGTH_SHORT).show()
                    loadCategories()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── Adapter ──────────────────────────────────────────────────────

    inner class CategoryAdapter(
        private val onClick: (KnowledgeCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {

        private var items = listOf<KnowledgeCategory>()
        private var counts = mapOf<String, Int>()

        fun setData(data: List<KnowledgeCategory>, countMap: Map<String, Int> = emptyMap()) {
            items = data
            counts = countMap
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_knowledge_category, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvIcon.text = item.icon
            holder.tvName.text = item.name
            holder.tvCount.text = "${counts[item.id] ?: 0}条"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvIcon: TextView = view.findViewById(R.id.tv_icon)
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvCount: TextView = view.findViewById(R.id.tv_count)
        }
    }
}
