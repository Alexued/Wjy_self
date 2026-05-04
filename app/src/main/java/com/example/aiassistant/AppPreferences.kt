package com.example.aiassistant

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect

/**
 * 封装所有用户配置的持久化存储（SharedPreferences）
 * 包含：API BaseUrl、API Key、自定义 Model、自定义提示词、截图模式、固定区域
 */
object AppPreferences {

    private const val PREFS_NAME = "ai_assistant_prefs"
    private const val KEY_API_BASE_URL = "api_base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_API_MODEL = "api_model"
    private const val KEY_PROMPT = "custom_prompt"
    private const val KEY_FLOAT_ENABLED = "float_enabled"

    // 截图模式：自定义区域 / 固定区域
    private const val KEY_CAPTURE_MODE = "capture_mode"
    const val MODE_CUSTOM_AREA = 0   // 每次手动框选
    const val MODE_FIXED_AREA = 1    // 首次框选后固定

    // 固定区域的坐标
    private const val KEY_FIXED_LEFT = "fixed_left"
    private const val KEY_FIXED_TOP = "fixed_top"
    private const val KEY_FIXED_RIGHT = "fixed_right"
    private const val KEY_FIXED_BOTTOM = "fixed_bottom"
    private const val KEY_FIXED_REGION_SET = "fixed_region_set"

    // 默认值
    const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    private const val DEFAULT_MODEL = "deepseek-v4-flash"
    private const val DEFAULT_PROMPT = "你是一个专业的解题助手。请分析截图中的题目，严格遵循以下规则：\n" +
            "1. 如果是选择题，第一行直接给出正确答案（如：正确答案：A），不要有任何铺垫\n" +
            "2. 然后逐个分析A/B/C/D每个选项，说明该选项正确或错误的具体原因\n" +
            "3. 只输出与题目解析直接相关的内容，不要问候语、结束语、学习建议等多余的话\n" +
            "4. 禁止使用Markdown格式（不要用**、#、-、*等标记符号），用纯文本输出"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiBaseUrl(context: Context): String =
        prefs(context).getString(KEY_API_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setApiBaseUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_API_BASE_URL, url).apply()

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(context: Context, key: String) =
        prefs(context).edit().putString(KEY_API_KEY, key).apply()

    fun getApiModel(context: Context): String =
        prefs(context).getString(KEY_API_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setApiModel(context: Context, model: String) =
        prefs(context).edit().putString(KEY_API_MODEL, model).apply()

    fun getPrompt(context: Context): String =
        prefs(context).getString(KEY_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT

    fun setPrompt(context: Context, prompt: String) =
        prefs(context).edit().putString(KEY_PROMPT, prompt).apply()

    fun isFloatEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOAT_ENABLED, false)

    fun setFloatEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_FLOAT_ENABLED, enabled).apply()

    // ── 截图模式 ──────────────────────────────────────────────────────
    fun getCaptureMode(context: Context): Int =
        prefs(context).getInt(KEY_CAPTURE_MODE, MODE_CUSTOM_AREA)

    fun setCaptureMode(context: Context, mode: Int) =
        prefs(context).edit().putInt(KEY_CAPTURE_MODE, mode).apply()

    // ── 固定区域 ──────────────────────────────────────────────────────
    fun isFixedRegionSet(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIXED_REGION_SET, false)

    fun getFixedRegion(context: Context): Rect {
        val p = prefs(context)
        return Rect(
            p.getInt(KEY_FIXED_LEFT, 0),
            p.getInt(KEY_FIXED_TOP, 0),
            p.getInt(KEY_FIXED_RIGHT, 0),
            p.getInt(KEY_FIXED_BOTTOM, 0)
        )
    }

    fun setFixedRegion(context: Context, rect: Rect) {
        prefs(context).edit()
            .putInt(KEY_FIXED_LEFT, rect.left)
            .putInt(KEY_FIXED_TOP, rect.top)
            .putInt(KEY_FIXED_RIGHT, rect.right)
            .putInt(KEY_FIXED_BOTTOM, rect.bottom)
            .putBoolean(KEY_FIXED_REGION_SET, true)
            .apply()
    }

    fun clearFixedRegion(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_FIXED_REGION_SET, false)
            .remove(KEY_FIXED_LEFT).remove(KEY_FIXED_TOP)
            .remove(KEY_FIXED_RIGHT).remove(KEY_FIXED_BOTTOM)
            .apply()
    }

    // ── AI 解析卡片位置与大小 ───────────────────────────────────────────
    private const val KEY_CARD_X = "card_x"
    private const val KEY_CARD_Y = "card_y"
    private const val KEY_CARD_W = "card_w"
    private const val KEY_CARD_H = "card_h"
    private const val KEY_CARD_SAVED = "card_saved"

    fun isCardBoundsSaved(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CARD_SAVED, false)

    fun saveCardBounds(context: Context, x: Int, y: Int, w: Int, h: Int) {
        prefs(context).edit()
            .putInt(KEY_CARD_X, x)
            .putInt(KEY_CARD_Y, y)
            .putInt(KEY_CARD_W, w)
            .putInt(KEY_CARD_H, h)
            .putBoolean(KEY_CARD_SAVED, true)
            .apply()
    }

    fun getCardBounds(context: Context): IntArray {
        val p = prefs(context)
        return intArrayOf(
            p.getInt(KEY_CARD_X, 0),
            p.getInt(KEY_CARD_Y, 0),
            p.getInt(KEY_CARD_W, 0),
            p.getInt(KEY_CARD_H, 0)
        )
    }
}
