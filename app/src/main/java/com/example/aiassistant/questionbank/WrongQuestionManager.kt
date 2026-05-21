package com.example.aiassistant.questionbank

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class WrongQuestion(
    val id: String,
    val questionText: String,
    val imagePath: String,
    val timestamp: Long,
    var isSummarized: Boolean,
    var summary: String = "",
    // 题库匹配数据（命中题库时填充）
    val bankQuestionId: String = "",
    val bankStem: String = "",
    val bankOptions: List<String> = emptyList(),
    val bankAnswer: String = "",
    val bankAnalysis: String = ""
) {
    /** 是否来自题库 */
    val isFromBank: Boolean get() = bankQuestionId.isNotEmpty()

    /** 列表摘要：题库题取题干前60字，OCR题取原文前60字 */
    val displaySummary: String
        get() {
            val text = if (isFromBank) bankStem else questionText
            return if (text.length > 60) text.take(60) + "..." else text
        }
}

object WrongQuestionManager {
    private const val PREFS_NAME = "wrong_questions_prefs"
    private const val KEY_LIST = "wrong_questions_list"

    @Synchronized
    fun getWrongQuestions(context: Context): List<WrongQuestion> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val result = mutableListOf<WrongQuestion>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bankOptions = mutableListOf<String>()
                val optArr = obj.optJSONArray("bankOptions")
                if (optArr != null) {
                    for (j in 0 until optArr.length()) {
                        bankOptions.add(optArr.getString(j))
                    }
                }
                result.add(
                    WrongQuestion(
                        id = obj.getString("id"),
                        questionText = obj.getString("questionText"),
                        imagePath = obj.optString("imagePath", ""),
                        timestamp = obj.getLong("timestamp"),
                        isSummarized = obj.optBoolean("isSummarized", false),
                        summary = obj.optString("summary", ""),
                        bankQuestionId = obj.optString("bankQuestionId", ""),
                        bankStem = obj.optString("bankStem", ""),
                        bankOptions = bankOptions,
                        bankAnswer = obj.optString("bankAnswer", ""),
                        bankAnalysis = obj.optString("bankAnalysis", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.sortedByDescending { it.timestamp }
    }

    /** 从题库数据录入错题 */
    @Synchronized
    fun addFromBank(context: Context, question: Question, bitmap: Bitmap?): WrongQuestion {
        val id = System.currentTimeMillis().toString()
        val imagePath = saveBitmap(context, id, bitmap)
        val optionTexts = question.options.map { it.text }

        val newQuestion = WrongQuestion(
            id = id,
            questionText = question.stem,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            isSummarized = false,
            bankQuestionId = question.id,
            bankStem = question.stem,
            bankOptions = optionTexts,
            bankAnswer = question.answer,
            bankAnalysis = question.analysis
        )

        val list = getWrongQuestions(context).toMutableList()
        list.add(0, newQuestion)
        saveList(context, list)
        return newQuestion
    }

    /** 从 OCR 文本录入错题（题库未命中时） */
    @Synchronized
    fun addFromOcr(context: Context, questionText: String, bitmap: Bitmap?): WrongQuestion {
        val id = System.currentTimeMillis().toString()
        val imagePath = saveBitmap(context, id, bitmap)

        val newQuestion = WrongQuestion(
            id = id,
            questionText = questionText,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            isSummarized = false
        )

        val list = getWrongQuestions(context).toMutableList()
        list.add(0, newQuestion)
        saveList(context, list)
        return newQuestion
    }

    private fun saveBitmap(context: Context, id: String, bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val dir = File(context.filesDir, "wrong_questions")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "wq_$id.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.close()
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @Synchronized
    fun updateWrongQuestion(context: Context, id: String, isSummarized: Boolean, summary: String) {
        val list = getWrongQuestions(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            val q = list[index]
            list[index] = q.copy(isSummarized = isSummarized, summary = summary)
            saveList(context, list)
        }
    }

    @Synchronized
    fun deleteWrongQuestion(context: Context, id: String) {
        val list = getWrongQuestions(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            val q = list[index]
            if (q.imagePath.isNotEmpty()) {
                try {
                    File(q.imagePath).delete()
                } catch (_: Exception) {}
            }
            list.removeAt(index)
            saveList(context, list)
        }
    }

    private fun saveList(context: Context, list: List<WrongQuestion>) {
        try {
            val arr = JSONArray()
            for (q in list) {
                val obj = JSONObject().apply {
                    put("id", q.id)
                    put("questionText", q.questionText)
                    put("imagePath", q.imagePath)
                    put("timestamp", q.timestamp)
                    put("isSummarized", q.isSummarized)
                    put("summary", q.summary)
                    put("bankQuestionId", q.bankQuestionId)
                    put("bankStem", q.bankStem)
                    put("bankAnswer", q.bankAnswer)
                    put("bankAnalysis", q.bankAnalysis)
                    val optArr = JSONArray()
                    for (opt in q.bankOptions) optArr.put(opt)
                    put("bankOptions", optArr)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, arr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
