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
    private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    private const val DEFAULT_MODEL = "gpt-4o"
    private const val DEFAULT_PROMPT = "请仔细分析这张截图的内容，给出详细的解析和学习建议。"

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
}
