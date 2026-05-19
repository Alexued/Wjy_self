package com.example.aiassistant.questionbank

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.aiassistant.R
import com.google.android.material.slider.RangeSlider

class PracticeSettingsDialog : DialogFragment() {

    private var moduleId: String = ""
    private var moduleName: String = ""
    private var onSettingsConfirmed: ((questionCount: Int, rateMin: Int, rateMax: Int) -> Unit)? = null

    companion object {
        fun newInstance(
            moduleId: String,
            moduleName: String,
            onSettingsConfirmed: (questionCount: Int, rateMin: Int, rateMax: Int) -> Unit
        ): PracticeSettingsDialog {
            return PracticeSettingsDialog().apply {
                this.moduleId = moduleId
                this.moduleName = moduleName
                this.onSettingsConfirmed = onSettingsConfirmed
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_practice_settings, null)

        val chipGroupCount = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_count)
        val sliderRateRange = view.findViewById<RangeSlider>(R.id.slider_rate_range)
        val tvRateRange = view.findViewById<TextView>(R.id.tv_rate_range)
        val tvRateDesc = view.findViewById<TextView>(R.id.tv_rate_desc)
        val tvQuestionCountHint = view.findViewById<TextView>(R.id.tv_question_count_hint)

        // 快捷选择按钮
        view.findViewById<View>(R.id.btn_rate_all).setOnClickListener {
            sliderRateRange.values = listOf(0f, 100f)
        }
        view.findViewById<View>(R.id.btn_rate_hard).setOnClickListener {
            sliderRateRange.values = listOf(0f, 40f)
        }
        view.findViewById<View>(R.id.btn_rate_medium).setOnClickListener {
            sliderRateRange.values = listOf(40f, 70f)
        }
        view.findViewById<View>(R.id.btn_rate_easy).setOnClickListener {
            sliderRateRange.values = listOf(70f, 100f)
        }

        // 滑块变化监听
        sliderRateRange.addOnChangeListener { _, _, _ ->
            val values = sliderRateRange.values
            val min = values[0].toInt()
            val max = values[1].toInt()
            tvRateRange.text = "$min% - $max%"
            tvRateDesc.text = when {
                min == 0 && max == 100 -> "全部题目"
                max <= 40 -> "难题（正确率低）"
                min >= 70 -> "简单题（正确率高）"
                else -> "中等难度题目"
            }
            updateQuestionCount(view, min, max)
        }

        // 更新题目数量提示
        updateQuestionCount(view, 0, 100)

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton("开始练习", null)
            .setNegativeButton("取消", null)
            .create()
    }

    private fun updateQuestionCount(view: View, rateMin: Int, rateMax: Int) {
        val tvQuestionCountHint = view.findViewById<TextView>(R.id.tv_question_count_hint)
        if (!QuestionBankManager.isLoaded()) {
            tvQuestionCountHint.text = "题库加载中..."
            return
        }
        val count = QuestionBankManager.getQuestionCountByRateRange(moduleId, rateMin, rateMax)
        tvQuestionCountHint.text = "符合条件的题目: $count 题"
    }

    private fun getSelectedQuestionCount(view: View): Int {
        val chipGroupCount = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_count)
        return when (chipGroupCount.checkedChipId) {
            R.id.chip_10 -> 10
            R.id.chip_20 -> 20
            R.id.chip_30 -> 30
            R.id.chip_50 -> 50
            else -> 15
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? AlertDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val questionCount = getSelectedQuestionCount(view)
            val sliderRateRange = view.findViewById<RangeSlider>(R.id.slider_rate_range)
            val values = sliderRateRange.values
            val rateMin = values[0].toInt()
            val rateMax = values[1].toInt()
            onSettingsConfirmed?.invoke(questionCount, rateMin, rateMax)
            dismiss()
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            dismiss()
        }
    }
}
