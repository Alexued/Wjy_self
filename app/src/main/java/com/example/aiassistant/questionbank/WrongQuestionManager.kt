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
    var summary: String = ""
)

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
                result.add(
                    WrongQuestion(
                        id = obj.getString("id"),
                        questionText = obj.getString("questionText"),
                        imagePath = obj.optString("imagePath", ""),
                        timestamp = obj.getLong("timestamp"),
                        isSummarized = obj.getBoolean("isSummarized"),
                        summary = obj.optString("summary", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun addWrongQuestion(context: Context, questionText: String, bitmap: Bitmap?): WrongQuestion {
        val id = System.currentTimeMillis().toString()
        var imagePath = ""
        if (bitmap != null) {
            try {
                val dir = File(context.filesDir, "wrong_questions")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "wq_$id.jpg")
                val fos = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
                fos.close()
                imagePath = file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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
