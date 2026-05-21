package com.example.aiassistant.questionbank

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WrongQuestionsActivity : AppCompatActivity() {

    private lateinit var rvWrongQuestions: RecyclerView
    private lateinit var btnBack: ImageView
    private lateinit var tabAll: TextView
    private lateinit var tabUnsummarized: TextView
    private lateinit var layoutEmpty: View

    private var currentFilterAll = true
    private var adapter: WrongQuestionsAdapter? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_questions)

        // 绑定视图
        rvWrongQuestions = findViewById(R.id.rv_wrong_questions)
        btnBack = findViewById(R.id.btn_back)
        tabAll = findViewById(R.id.tab_all)
        tabUnsummarized = findViewById(R.id.tab_unsummarized)
        layoutEmpty = findViewById(R.id.layout_empty)

        rvWrongQuestions.layoutManager = LinearLayoutManager(this)

        btnBack.setOnClickListener { finish() }

        tabAll.setOnClickListener {
            if (!currentFilterAll) {
                currentFilterAll = true
                updateTabStyles()
                loadWrongQuestions()
            }
        }

        tabUnsummarized.setOnClickListener {
            if (currentFilterAll) {
                currentFilterAll = false
                updateTabStyles()
                loadWrongQuestions()
            }
        }

        updateTabStyles()
        loadWrongQuestions()
    }

    override fun onResume() {
        super.onResume()
        loadWrongQuestions()
    }

    private fun updateTabStyles() {
        if (currentFilterAll) {
            tabAll.setTextColor(0xFFFFFFFF.toInt())
            tabAll.setBackgroundResource(R.drawable.bg_primary_chip)

            tabUnsummarized.setTextColor(getColor(R.color.text_secondary))
            tabUnsummarized.setBackgroundResource(R.drawable.bg_default_chip)
        } else {
            tabAll.setTextColor(getColor(R.color.text_secondary))
            tabAll.setBackgroundResource(R.drawable.bg_default_chip)

            tabUnsummarized.setTextColor(0xFFFFFFFF.toInt())
            tabUnsummarized.setBackgroundResource(R.drawable.bg_primary_chip)
        }
    }

    private fun loadWrongQuestions() {
        val allList = WrongQuestionManager.getWrongQuestions(this)
        val filteredList = if (currentFilterAll) {
            allList
        } else {
            allList.filter { !it.isSummarized }
        }

        if (filteredList.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvWrongQuestions.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            rvWrongQuestions.visibility = View.VISIBLE
        }

        adapter = WrongQuestionsAdapter(filteredList)
        rvWrongQuestions.adapter = adapter
    }

    // ── 列表适配器 ──
    inner class WrongQuestionsAdapter(
        private val list: List<WrongQuestion>
    ) : RecyclerView.Adapter<WrongQuestionsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(R.id.tv_date)
            val btnDelete: ImageView = view.findViewById(R.id.btn_delete)
            val tvQuestionText: TextView = view.findViewById(R.id.tv_question_text)
            val cardQuestionImage: View = view.findViewById(R.id.card_question_image)
            val ivQuestionImage: ImageView = view.findViewById(R.id.iv_question_image)
            val layoutSummaryPreview: View = view.findViewById(R.id.layout_summary_preview)
            val tvSummaryPreview: TextView = view.findViewById(R.id.tv_summary_preview)
            val cbSummarized: MaterialCheckBox = view.findViewById(R.id.cb_summarized)
            val btnEditSummary: View = view.findViewById(R.id.btn_edit_summary)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wrong_question, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvDate.text = sdf.format(Date(item.timestamp))
            holder.tvQuestionText.text = item.questionText

            // 图片显示与点击放大
            if (item.imagePath.isNotEmpty() && File(item.imagePath).exists()) {
                holder.cardQuestionImage.visibility = View.VISIBLE
                try {
                    val bmp = BitmapFactory.decodeFile(item.imagePath)
                    holder.ivQuestionImage.setImageBitmap(bmp)
                    holder.ivQuestionImage.setOnClickListener {
                        showImageZoomDialog(item.imagePath)
                    }
                } catch (e: Exception) {
                    holder.cardQuestionImage.visibility = View.GONE
                }
            } else {
                holder.cardQuestionImage.visibility = View.GONE
            }

            // 总结预览
            if (item.summary.isNotEmpty()) {
                holder.layoutSummaryPreview.visibility = View.VISIBLE
                holder.tvSummaryPreview.text = item.summary
            } else {
                holder.layoutSummaryPreview.visibility = View.GONE
            }

            // 是否总结复选框 (直接联动本地持久化)
            holder.cbSummarized.setOnCheckedChangeListener(null)
            holder.cbSummarized.isChecked = item.isSummarized
            holder.cbSummarized.setOnCheckedChangeListener { _, isChecked ->
                WrongQuestionManager.updateWrongQuestion(
                    this@WrongQuestionsActivity,
                    item.id,
                    isChecked,
                    item.summary
                )
                // 延迟一秒重新载入以给用户舒适的操作视觉过渡
                holder.itemView.postDelayed({
                    loadWrongQuestions()
                }, 400)
            }

            // 编辑总结
            holder.btnEditSummary.setOnClickListener {
                showEditSummaryDialog(item)
            }

            // 删除错题
            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@WrongQuestionsActivity)
                    .setTitle("确认删除")
                    .setMessage("确定要删除这道错题吗？该操作不可撤销。")
                    .setPositiveButton("删除") { _, _ ->
                        WrongQuestionManager.deleteWrongQuestion(this@WrongQuestionsActivity, item.id)
                        loadWrongQuestions()
                        Toast.makeText(this@WrongQuestionsActivity, "已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        override fun getItemCount() = list.size
    }

    // ── 错题图放大弹窗 ──
    private fun showImageZoomDialog(imagePath: String) {
        try {
            val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                .create()
            val imgView = ImageView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                val bmp = BitmapFactory.decodeFile(imagePath)
                setImageBitmap(bmp)
                setOnClickListener { dialog.dismiss() }
            }
            dialog.setView(imgView)
            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── 总结输入弹窗 ──
    private fun showEditSummaryDialog(item: WrongQuestion) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val et = EditText(this).apply {
            hint = "在这里写下这道错题的原因、考点或解析，温故而知新..."
            setText(item.summary)
            minLines = 4
            gravity = android.view.Gravity.TOP
            setBackgroundResource(R.drawable.bg_premium_pill_stroke)
            setPadding(16, 16, 16, 16)
            setTextColor(getColor(R.color.text_primary))
        }
        container.addView(et)

        AlertDialog.Builder(this)
            .setTitle("写总结笔记")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val note = et.text.toString().trim()
                WrongQuestionManager.updateWrongQuestion(
                    this,
                    item.id,
                    item.isSummarized,
                    note
                )
                loadWrongQuestions()
                Toast.makeText(this, "总结已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
