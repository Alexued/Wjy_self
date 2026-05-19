package com.example.aiassistant.questionbank

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.OpenAIApiService

class TextSelectionCallback(
    private val context: Context,
    private val textView: TextView,
    private val onSearch: ((String) -> Unit)? = null
) : ActionMode.Callback {

    companion object {
        private const val MENU_COPY = 1
        private const val MENU_QA = 2
        private const val MENU_SEARCH = 3
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.clear()
        menu.add(0, MENU_COPY, 0, "复制")
        menu.add(0, MENU_QA, 1, "问答")
        menu.add(0, MENU_SEARCH, 2, "搜索")
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val start = textView.selectionStart
        val end = textView.selectionEnd
        if (start < 0 || end < 0 || start >= end) return false
        val selectedText = textView.text.substring(start, end).trim()
        if (selectedText.isEmpty()) return false

        when (item.itemId) {
            MENU_COPY -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("selected_text", selectedText))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                mode.finish()
                return true
            }
            MENU_QA -> {
                mode.finish()
                showQaDialog(selectedText)
                return true
            }
            MENU_SEARCH -> {
                mode.finish()
                onSearch?.invoke(selectedText)
                return true
            }
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {}

    private fun showQaDialog(selectedText: String) {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("问答")
            .setMessage("正在思考...")
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()

        val baseUrl = AppPreferences.getApiBaseUrl(context)
        val apiKey = AppPreferences.getApiKey(context)
        val model = try {
            val models = com.example.aiassistant.ModelManager.allModels
            models.firstOrNull()?.model ?: "deepseek-chat"
        } catch (_: Exception) { "deepseek-chat" }

        OpenAIApiService.analyzeWithSystemPrompt(
            ocrText = "",
            systemPrompt = "你是一个公务员考试辅导助手。请用简洁的中文回答用户的问题。如果用户粘贴了一道题目，请分析题目并给出答案和解析。",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            userMessage = "请回答以下问题：\n$selectedText",
            onComplete = { response ->
                (context as? android.app.Activity)?.runOnUiThread {
                    try {
                        dialog.setMessage(response)
                    } catch (_: Exception) {}
                }
            },
            onError = { error ->
                (context as? android.app.Activity)?.runOnUiThread {
                    try {
                        dialog.setMessage("请求失败: $error")
                    } catch (_: Exception) {}
                }
            }
        )
    }

    /**
     * 为 TextView 设置自定义文字选择操作栏
     */
    fun attachToTextView() {
        textView.customSelectionActionModeCallback = this
    }
}
