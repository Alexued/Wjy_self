package com.example.aiassistant.questionbank

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.R
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WrongQuestionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "wrong_question_id"
    }

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var currentId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_question_detail)

        currentId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btn_delete).setOnClickListener { confirmDelete() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_edit_summary)
            .setOnClickListener { showEditSummaryDialog() }

        loadDetail()
    }

    private fun loadDetail() {
        val items = WrongQuestionManager.getWrongQuestions(this)
        val item = items.find { it.id == currentId } ?: run {
            Toast.makeText(this, "错题不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 来源标记
        val tvBadge = findViewById<TextView>(R.id.tv_source_badge)
        if (item.isFromBank) {
            tvBadge.text = "来自题库"
            tvBadge.setTextColor(getColor(R.color.primary))
        } else {
            tvBadge.text = "OCR识别"
            tvBadge.setTextColor(getColor(R.color.text_secondary))
        }

        // 题干
        val tvStem = findViewById<TextView>(R.id.tv_stem)
        tvStem.text = if (item.isFromBank) item.bankStem else item.questionText

        // 截图
        val cardImage = findViewById<View>(R.id.card_image)
        val ivImage = findViewById<ImageView>(R.id.iv_image)
        if (item.imagePath.isNotEmpty() && File(item.imagePath).exists()) {
            cardImage.visibility = View.VISIBLE
            try {
                val bmp = BitmapFactory.decodeFile(item.imagePath)
                ivImage.setImageBitmap(bmp)
                ivImage.setOnClickListener { showImageZoomDialog(item.imagePath) }
            } catch (_: Exception) {
                cardImage.visibility = View.GONE
            }
        } else {
            cardImage.visibility = View.GONE
        }

        // 选项（题库题）
        val layoutOptions = findViewById<LinearLayout>(R.id.layout_options)
        if (item.isFromBank && item.bankOptions.isNotEmpty()) {
            layoutOptions.visibility = View.VISIBLE
            layoutOptions.removeAllViews()
            for ((i, opt) in item.bankOptions.withIndex()) {
                val label = "${'A' + i}. $opt"
                val tv = TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(0, if (i == 0) 0 else dpToPx(6), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                // 高亮正确选项
                if (item.bankAnswer.isNotEmpty() && item.bankAnswer.first() == ('A' + i)) {
                    tv.setTextColor(Color.parseColor("#2E7D32"))
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                }
                layoutOptions.addView(tv)
            }
        } else {
            layoutOptions.visibility = View.GONE
        }

        // 答案
        val cardAnswer = findViewById<View>(R.id.card_answer)
        val tvAnswer = findViewById<TextView>(R.id.tv_answer)
        if (item.isFromBank && item.bankAnswer.isNotEmpty()) {
            cardAnswer.visibility = View.VISIBLE
            tvAnswer.text = item.bankAnswer
        } else {
            cardAnswer.visibility = View.GONE
        }

        // 解析
        val cardAnalysis = findViewById<View>(R.id.card_analysis)
        val tvAnalysis = findViewById<TextView>(R.id.tv_analysis)
        if (item.isFromBank && item.bankAnalysis.isNotEmpty()) {
            cardAnalysis.visibility = View.VISIBLE
            tvAnalysis.text = item.bankAnalysis
        } else {
            cardAnalysis.visibility = View.GONE
        }

        // 总结笔记
        updateSummaryDisplay(item)

        // 已总结复选框
        val cb = findViewById<MaterialCheckBox>(R.id.cb_summarized)
        cb.setOnCheckedChangeListener(null)
        cb.isChecked = item.isSummarized
        cb.setOnCheckedChangeListener { _, isChecked ->
            WrongQuestionManager.updateWrongQuestion(this, item.id, isChecked, item.summary)
        }
    }

    private fun updateSummaryDisplay(item: WrongQuestion) {
        val tvSummary = findViewById<TextView>(R.id.tv_summary)
        if (item.summary.isNotEmpty()) {
            tvSummary.text = item.summary
            tvSummary.setTextColor(getColor(R.color.text_primary))
        } else {
            tvSummary.text = "暂无笔记，点击下方按钮添加"
            tvSummary.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun showEditSummaryDialog() {
        val items = WrongQuestionManager.getWrongQuestions(this)
        val item = items.find { it.id == currentId } ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12))
        }
        val et = EditText(this).apply {
            hint = "写下错题原因、考点或解析..."
            setText(item.summary)
            minLines = 4
            gravity = Gravity.TOP
            setBackgroundResource(R.drawable.bg_premium_pill_stroke)
            setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
            setTextColor(getColor(R.color.text_primary))
        }
        container.addView(et)

        AlertDialog.Builder(this)
            .setTitle("写总结笔记")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val note = et.text.toString().trim()
                WrongQuestionManager.updateWrongQuestion(this, item.id, item.isSummarized, note)
                loadDetail()
                Toast.makeText(this, "总结已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除这道错题吗？该操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                WrongQuestionManager.deleteWrongQuestion(this, currentId)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImageZoomDialog(imagePath: String) {
        try {
            val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).create()
            val imgView = ImageView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(imagePath))
                setOnClickListener { dialog.dismiss() }
            }
            dialog.setView(imgView)
            dialog.show()
        } catch (_: Exception) {}
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
