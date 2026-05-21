package com.example.aiassistant.dictionary

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R

class DictionaryActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var tvClipboardHint: TextView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var rvResults: RecyclerView

    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private val DEBOUNCE_MS = 150L

    /** 在 onCreate 中立即读取剪贴板（此时有窗口焦点），缓存供后续使用 */
    private var cachedClipText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary)

        // 立即读取剪贴板（onCreate 时有窗口焦点，Android 12+ 允许读取）
        cachedClipText = try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        } catch (_: SecurityException) { null }

        etSearch = findViewById(R.id.et_search)
        tvClipboardHint = findViewById(R.id.tv_clipboard_hint)
        layoutLoading = findViewById(R.id.layout_loading)
        layoutEmpty = findViewById(R.id.layout_empty)
        rvResults = findViewById(R.id.rv_results)

        rvResults.layoutManager = LinearLayoutManager(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btn_search).setOnClickListener { doSearch() }

        // 实时搜索：输入变化时延迟触发
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                searchRunnable = Runnable { performSearch(s?.toString()?.trim() ?: "") }
                handler.postDelayed(searchRunnable!!, DEBOUNCE_MS)
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        if (DictionaryManager.isLoaded) {
            applyClipboardAndSearch()
        } else {
            showLoading(true)
            DictionaryManager.addOnReadyListener(readyListener)
            DictionaryManager.init(this)
        }
    }

    private val readyListener: () -> Unit = {
        runOnUiThread {
            showLoading(false)
            applyClipboardAndSearch()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchRunnable?.let { handler.removeCallbacks(it) }
        DictionaryManager.removeOnReadyListener(readyListener)
    }

    private fun applyClipboardAndSearch() {
        val clipText = cachedClipText

        if (!clipText.isNullOrBlank()) {
            etSearch.setText(clipText)
            tvClipboardHint.text = "已从剪贴板读取: $clipText"
            tvClipboardHint.visibility = View.VISIBLE
            // setText 会触发 TextWatcher，自动搜索
        } else {
            tvClipboardHint.text = "剪贴板为空，请输入搜索内容"
            tvClipboardHint.visibility = View.VISIBLE
            etSearch.requestFocus()
            showKeyboard()
        }
    }

    private fun doSearch() {
        val query = etSearch.text?.toString()?.trim() ?: ""
        if (query.isBlank()) return
        hideKeyboard()
        tvClipboardHint.visibility = View.GONE
        performSearch(query)
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) {
            layoutEmpty.visibility = View.GONE
            rvResults.visibility = View.GONE
            return
        }
        showLoading(false)
        DictionaryManager.searchAsync(query) { result ->
            if (result.isEmpty) {
                layoutEmpty.visibility = View.VISIBLE
                rvResults.visibility = View.GONE
            } else {
                layoutEmpty.visibility = View.GONE
                rvResults.visibility = View.VISIBLE
                rvResults.adapter = DictionaryAdapter(result)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
        layoutEmpty.visibility = View.GONE
        rvResults.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showKeyboard() {
        etSearch.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }
}

// ── RecyclerView Adapter ──────────────────────────────────────────────

private const val TYPE_IDIOM = 0
private const val TYPE_WORD = 1
private const val TYPE_CI = 2
private const val TYPE_XIEHOUYU = 3

class DictionaryAdapter(result: DictionaryResult) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items: List<DictItem> = result.items

    override fun getItemViewType(position: Int) = when (items[position]) {
        is DictItem.IdiomItem -> TYPE_IDIOM
        is DictItem.WordItem -> TYPE_WORD
        is DictItem.CiItem -> TYPE_CI
        is DictItem.XiehouyuItem -> TYPE_XIEHOUYU
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_IDIOM -> IdiomVH(inflater.inflate(R.layout.item_dict_idiom, parent, false))
            TYPE_WORD -> WordVH(inflater.inflate(R.layout.item_dict_word, parent, false))
            TYPE_CI -> CiVH(inflater.inflate(R.layout.item_dict_ci, parent, false))
            TYPE_XIEHOUYU -> CiVH(inflater.inflate(R.layout.item_dict_ci, parent, false))
            else -> throw IllegalArgumentException()
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DictItem.IdiomItem -> (holder as IdiomVH).bind(item.data)
            is DictItem.WordItem -> (holder as WordVH).bind(item.data)
            is DictItem.CiItem -> (holder as CiVH).bindCi(item.data)
            is DictItem.XiehouyuItem -> (holder as CiVH).bindXiehouyu(item.data)
        }
    }

    class IdiomVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvWord: TextView = view.findViewById(R.id.tv_word)
        private val tvPinyin: TextView = view.findViewById(R.id.tv_pinyin)
        private val tvExplanation: TextView = view.findViewById(R.id.tv_explanation)
        private val tvDerivation: TextView = view.findViewById(R.id.tv_derivation)
        private val tvExample: TextView = view.findViewById(R.id.tv_example)

        fun bind(entry: IdiomEntry) {
            tvWord.text = entry.word
            tvPinyin.text = entry.pinyin
            tvExplanation.text = entry.explanation

            if (entry.derivation.isNotBlank() && entry.derivation != "无") {
                tvDerivation.visibility = View.VISIBLE
                tvDerivation.text = "出处：${entry.derivation}"
            } else {
                tvDerivation.visibility = View.GONE
            }

            if (entry.example.isNotBlank() && entry.example != "无") {
                tvExample.visibility = View.VISIBLE
                tvExample.text = "例：${entry.example}"
            } else {
                tvExample.visibility = View.GONE
            }
        }
    }

    class WordVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvWord: TextView = view.findViewById(R.id.tv_word)
        private val tvPinyin: TextView = view.findViewById(R.id.tv_pinyin)
        private val tvRadicals: TextView = view.findViewById(R.id.tv_radicals)
        private val tvStrokes: TextView = view.findViewById(R.id.tv_strokes)
        private val tvExplanation: TextView = view.findViewById(R.id.tv_explanation)

        fun bind(entry: WordEntry) {
            tvWord.text = entry.word
            tvPinyin.text = entry.pinyin
            tvRadicals.text = "部首：${entry.radicals}"
            tvStrokes.text = "${entry.strokes}画"

            val text = entry.explanation + if (entry.more.isNotBlank() && entry.more != "无") "\n${entry.more}" else ""
            tvExplanation.text = text
        }
    }

    class CiVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvWord: TextView = view.findViewById(R.id.tv_word)
        private val tvExplanation: TextView = view.findViewById(R.id.tv_explanation)

        fun bindCi(entry: CiEntry) {
            tvWord.text = entry.ci
            tvExplanation.text = entry.explanation
        }

        fun bindXiehouyu(entry: XiehouyuEntry) {
            tvWord.text = entry.riddle
            tvExplanation.text = entry.answer
        }
    }
}
