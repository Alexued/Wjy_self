package com.example.aiassistant.knowledge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import com.google.android.material.floatingactionbutton.FloatingActionButton

class KnowledgeCardListActivity : AppCompatActivity() {

    private lateinit var categoryId: String
    private lateinit var categoryName: String
    private lateinit var rvCards: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var pbLoadMore: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var btnBatchDelete: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnExport: TextView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var adapter: CardAdapter

    private var isSelectMode = false
    private val selectedIds = mutableSetOf<Long>()

    // 分页状态
    private var currentPage = 0
    private var hasMore = true
    private var isLoading = false
    private val pageSize = 20

    // 网格/列表切换
    private var isGridMode = true
    private var spanCount = 2

    // 搜索
    private var searchKeyword = ""
    private var searchDebounce: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_knowledge_card_list)

        categoryId = intent.getStringExtra("category_id") ?: return finish()
        categoryName = intent.getStringExtra("category_name") ?: ""

        findViewById<TextView>(R.id.tv_title).text = categoryName
        findViewById<TextView>(R.id.btn_back).setOnClickListener {
            if (isSelectMode) exitSelectMode() else finish()
        }

        btnBatchDelete = findViewById(R.id.btn_batch_delete)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnExport = findViewById(R.id.btn_export)
        rvCards = findViewById(R.id.rv_cards)
        tvEmpty = findViewById(R.id.tv_empty)
        tvCount = findViewById(R.id.tv_count)
        pbLoadMore = findViewById(R.id.pb_load_more)
        fabAdd = findViewById(R.id.fab_add)
        etSearch = findViewById(R.id.et_search)

        // 适配器
        adapter = CardAdapter(
            onClick = { card ->
                if (isSelectMode) toggleSelection(card.id) else openCardDetail(card)
            },
            onLongClick = { card ->
                if (!isSelectMode) enterSelectMode()
                toggleSelection(card.id)
            }
        )

        setupRecyclerView()
        setupSearch()
        setupGridToggle()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        resetAndLoad()
    }

    override fun onBackPressed() {
        if (isSelectMode) exitSelectMode() else super.onBackPressed()
    }

    // ── RecyclerView 设置 ─────────────────────────────────────────────

    private fun setupRecyclerView() {
        applyLayoutManager()
        rvCards.adapter = adapter

        // 滚动加载更多
        rvCards.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisible = when (layoutManager) {
                    is GridLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    is LinearLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    else -> return
                }
                if (!isLoading && hasMore && lastVisible >= totalItemCount - 5) {
                    loadMore()
                }
            }
        })
    }

    private fun applyLayoutManager() {
        if (isGridMode) {
            rvCards.layoutManager = GridLayoutManager(this, spanCount)
        } else {
            rvCards.layoutManager = LinearLayoutManager(this)
        }
    }

    // ── 搜索 ──────────────────────────────────────────────────────────

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounce?.let { etSearch.removeCallbacks(it) }
                searchDebounce = Runnable {
                    searchKeyword = s?.toString()?.trim() ?: ""
                    resetAndLoad()
                }
                etSearch.postDelayed(searchDebounce, 300)
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchKeyword = etSearch.text.toString().trim()
                resetAndLoad()
                true
            } else false
        }
    }

    // ── 网格切换 ──────────────────────────────────────────────────────

    private fun setupGridToggle() {
        val btnToggle = findViewById<TextView>(R.id.btn_grid_toggle)
        btnToggle.setOnClickListener {
            isGridMode = !isGridMode
            btnToggle.text = if (isGridMode) "▦" else "☰"
            adapter.setGridMode(isGridMode)
            applyLayoutManager()
        }
    }

    // ── 按钮 ──────────────────────────────────────────────────────────

    private fun setupButtons() {
        fabAdd.setOnClickListener {
            val intent = Intent(this, KnowledgeCardEditActivity::class.java)
            intent.putExtra("category_id", categoryId)
            intent.putExtra("category_name", categoryName)
            startActivity(intent)
        }

        btnExport.setOnClickListener { exportCards() }
        btnBatchDelete.setOnClickListener { confirmBatchDelete() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
    }

    // ── 数据加载 ──────────────────────────────────────────────────────

    private fun resetAndLoad() {
        currentPage = 0
        hasMore = true
        adapter.clearData()
        loadPage()
    }

    private fun loadMore() {
        if (isLoading || !hasMore) return
        currentPage++
        loadPage()
    }

    private fun loadPage() {
        isLoading = true
        pbLoadMore.visibility = View.VISIBLE

        val result = if (searchKeyword.isNotEmpty()) {
            KnowledgeCardManager.searchCards(categoryId, searchKeyword, currentPage, pageSize)
        } else {
            KnowledgeCardManager.getCardsPaged(categoryId, currentPage, pageSize)
        }

        val cards = result.first
        hasMore = result.second

        if (currentPage == 0) {
            adapter.setData(cards)
        } else {
            adapter.appendData(cards)
        }

        isLoading = false
        pbLoadMore.visibility = View.GONE

        // 更新统计
        val totalCount = KnowledgeCardManager.getCardCount(categoryId)
        tvCount.text = if (searchKeyword.isNotEmpty()) {
            "搜索「$searchKeyword」 ${adapter.itemCount}条结果"
        } else {
            "共${totalCount}条"
        }

        // 空状态
        val isEmpty = adapter.itemCount == 0
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvCards.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    // ── 选中模式 ──────────────────────────────────────────────────────

    private fun enterSelectMode() {
        isSelectMode = true
        selectedIds.clear()
        btnBatchDelete.visibility = View.VISIBLE
        btnSelectAll.visibility = View.VISIBLE
        btnExport.visibility = View.GONE
        fabAdd.hide()
        adapter.setSelectMode(true)
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedIds.clear()
        btnBatchDelete.visibility = View.GONE
        btnSelectAll.visibility = View.GONE
        btnExport.visibility = View.VISIBLE
        fabAdd.show()
        adapter.setSelectMode(false)
    }

    private fun toggleSelection(cardId: Long) {
        if (selectedIds.contains(cardId)) selectedIds.remove(cardId) else selectedIds.add(cardId)
        adapter.setSelectedIds(selectedIds)
        btnBatchDelete.text = if (selectedIds.isEmpty()) "删除" else "删除(${selectedIds.size})"
    }

    private fun toggleSelectAll() {
        val all = adapter.getAllIds()
        if (selectedIds.size == all.size) selectedIds.clear() else { selectedIds.clear(); selectedIds.addAll(all) }
        adapter.setSelectedIds(selectedIds)
        btnBatchDelete.text = if (selectedIds.isEmpty()) "删除" else "删除(${selectedIds.size})"
    }

    private fun confirmBatchDelete() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的卡片", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setMessage("确定删除选中的 ${selectedIds.size} 张卡片？")
            .setPositiveButton("删除") { _, _ ->
                KnowledgeCardManager.deleteCards(selectedIds)
                Toast.makeText(this, "已删除 ${selectedIds.size} 张卡片", Toast.LENGTH_SHORT).show()
                exitSelectMode()
                resetAndLoad()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openCardDetail(card: KnowledgeCard) {
        val intent = Intent(this, KnowledgeCardEditActivity::class.java)
        intent.putExtra("card_id", card.id)
        intent.putExtra("category_id", categoryId)
        intent.putExtra("category_name", categoryName)
        startActivity(intent)
    }

    private fun exportCards() {
        val json = KnowledgeCardManager.exportToJson(categoryId)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("knowledge_cards", json))
        Toast.makeText(this, "已复制到剪贴板（JSON格式）", Toast.LENGTH_LONG).show()
    }

    // ── Adapter ──────────────────────────────────────────────────────

    inner class CardAdapter(
        private val onClick: (KnowledgeCard) -> Unit,
        private val onLongClick: (KnowledgeCard) -> Unit
    ) : RecyclerView.Adapter<CardAdapter.VH>() {

        private val items = mutableListOf<KnowledgeCard>()
        private var selectMode = false
        private var selected = setOf<Long>()
        private var gridMode = true

        fun setData(data: List<KnowledgeCard>) {
            items.clear()
            items.addAll(data)
            notifyDataSetChanged()
        }

        fun appendData(data: List<KnowledgeCard>) {
            val start = items.size
            items.addAll(data)
            notifyItemRangeInserted(start, data.size)
        }

        fun clearData() {
            items.clear()
            notifyDataSetChanged()
        }

        fun setSelectMode(enabled: Boolean) {
            selectMode = enabled
            notifyDataSetChanged()
        }

        fun setSelectedIds(ids: Set<Long>) {
            selected = ids
            notifyDataSetChanged()
        }

        fun setGridMode(grid: Boolean) {
            gridMode = grid
            notifyDataSetChanged()
        }

        fun getAllIds(): Set<Long> = items.map { it.id }.toSet()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_knowledge_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvContent.text = item.content

            // 网格模式下内容截断更短
            holder.tvContent.maxLines = if (gridMode) 3 else 6

            // 选中模式
            if (selectMode) {
                holder.cbSelect.visibility = View.VISIBLE
                holder.cbSelect.setOnCheckedChangeListener(null)
                holder.cbSelect.isChecked = selected.contains(item.id)
                holder.cbSelect.setOnCheckedChangeListener { _, _ -> onClick(item) }
            } else {
                holder.cbSelect.visibility = View.GONE
            }

            // tags
            try {
                val arr = org.json.JSONArray(item.tags)
                if (arr.length() > 0) {
                    val tags = (0 until arr.length()).map { arr.getString(it) }
                    holder.tvTags.text = tags.joinToString(" ")
                    holder.tvTags.visibility = View.VISIBLE
                } else {
                    holder.tvTags.visibility = View.GONE
                }
            } catch (_: Exception) {
                holder.tvTags.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item); true }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(R.id.cb_select)
            val tvTitle: TextView = view.findViewById(R.id.tv_title)
            val tvContent: TextView = view.findViewById(R.id.tv_content)
            val tvTags: TextView = view.findViewById(R.id.tv_tags)
        }
    }
}
