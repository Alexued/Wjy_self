package com.example.aiassistant

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect

/**
 * 封装所有用户配置的持久化存储（SharedPreferences）
 * 包含：API BaseUrl、API Key、自定义 Model、自定义提示词、截图模式、固定区域
 */
object AppPreferences {

    internal const val PREFS_NAME = "ai_assistant_prefs"
    private const val KEY_API_BASE_URL = "api_base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_API_MODEL = "api_model"
    private const val KEY_ACTIVE_TEACHER_ID = "active_teacher_id"
    private const val KEY_FLOAT_ENABLED = "float_enabled"
    private const val KEY_DEFAULT_FULLSCREEN = "default_fullscreen"
    private const val KEY_FLOAT_BALL_SIZE = "float_ball_size"
    private const val KEY_CARD_DISPLAY_MODE = "card_display_mode"
    const val CARD_MODE_FREE = 0        // 自由悬浮（默认）
    const val CARD_MODE_BOTTOM = 1      // 底部弹出
    const val CARD_MODE_ATTACHED = 2    // 附着悬浮球

    // OCR 模式：本地 / 云端
    private const val KEY_OCR_MODE = "ocr_mode"
    const val OCR_MODE_LOCAL = 0
    const val OCR_MODE_CLOUD = 1

    private const val KEY_CLOUD_OCR_URL = "cloud_ocr_url"
    private const val KEY_CLOUD_OCR_TOKEN = "cloud_ocr_token"
    private const val KEY_CLOUD_OCR_TYPE = "cloud_ocr_type"
    private const val KEY_CLOUD_TEXT_OCR_URL = "cloud_text_ocr_url"
    const val CLOUD_OCR_TYPE_LAYOUT = 0   // 布局解析（可识别数字）
    const val CLOUD_OCR_TYPE_TEXT = 1     // 轻量文字识别
    const val DEFAULT_CLOUD_OCR_URL = "https://s1k6ee37p1b2retc.aistudio-app.com/layout-parsing"
    const val DEFAULT_CLOUD_TEXT_OCR_URL = "https://b4q5p4ybl3g3p8o5.aistudio-app.com/ocr"

    // 静默搜题
    private const val KEY_SILENT_SEARCH = "silent_search"

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
    private const val DEFAULT_MODEL = "deepseek-chat"
    const val DEFAULT_API_KEY = "sk-178ddf915bbc4c71b31f0e5ea66dd177"
    const val DEFAULT_CLOUD_OCR_TOKEN = "2c13ab5e937c515153b8245175410120fb961ed7"

    // 默认 prompt 已移至 PromptTemplates.kt，通过 PromptTemplates.getDefaultPrompt() 获取

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiBaseUrl(context: Context): String =
        prefs(context).getString(KEY_API_BASE_URL, DEFAULT_BASE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    fun setApiBaseUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_API_BASE_URL, url).apply()

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, DEFAULT_API_KEY)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_KEY

    fun setApiKey(context: Context, key: String) =
        prefs(context).edit().putString(KEY_API_KEY, key).apply()

    fun getApiModel(context: Context): String =
        prefs(context).getString(KEY_API_MODEL, DEFAULT_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    fun setApiModel(context: Context, model: String) =
        prefs(context).edit().putString(KEY_API_MODEL, model).apply()

    // ── 老师系统 ──────────────────────────────────────────────────────

    fun getActiveTeacherId(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_TEACHER_ID, "huasheng") ?: "huasheng"

    fun setActiveTeacherId(context: Context, id: String) =
        prefs(context).edit().putString(KEY_ACTIVE_TEACHER_ID, id).apply()

    // ── 题型选择 ──────────────────────────────────────────────────────
    private const val KEY_CURRENT_QUESTION_TYPE = "current_question_type"

    fun getCurrentQuestionType(context: Context): QuestionType {
        val ordinal = prefs(context).getInt(KEY_CURRENT_QUESTION_TYPE, 0)
        return QuestionType.fromOrdinal(ordinal)
    }

    fun setCurrentQuestionType(context: Context, type: QuestionType) =
        prefs(context).edit().putInt(KEY_CURRENT_QUESTION_TYPE, type.ordinal).apply()

    // ── Prompt（委托给 TeacherManager） ───────────────────────────────

    /**
     * 获取当前老师·当前题型的 prompt（含覆盖层）。
     * 需要在 TeacherManager.init() 之后调用。
     */
    fun getPrompt(context: Context): String =
        TeacherManager.getPrompt(context, getCurrentQuestionType(context))

    fun getPromptForType(context: Context, type: QuestionType): String =
        TeacherManager.getPrompt(context, type)

    /** 保存 prompt 覆盖层（对内置老师生效，导入老师直接改文件） */
    fun setPrompt(context: Context, prompt: String) {
        val teacher = TeacherManager.activeTeacher
        val type = getCurrentQuestionType(context)
        TeacherManager.setOverlay(context, teacher.id, type, prompt)
    }

    fun resetPromptForType(context: Context, type: QuestionType) {
        TeacherManager.removeOverlay(context, TeacherManager.activeTeacher.id, type)
    }

    // ── AI 模型列表 ────────────────────────────────────────────────────
    private const val KEY_AI_MODELS = "ai_models"
    private const val KEY_STRATEGY_SCHEMES = "strategy_schemes"
    private const val KEY_ACTIVE_STRATEGY_ID = "active_strategy_id"

    fun getAiModels(context: Context): String =
        prefs(context).getString(KEY_AI_MODELS, "") ?: ""

    fun setAiModels(context: Context, json: String) =
        prefs(context).edit().putString(KEY_AI_MODELS, json).apply()

    fun getStrategySchemes(context: Context): String =
        prefs(context).getString(KEY_STRATEGY_SCHEMES, "") ?: ""

    fun setStrategySchemes(context: Context, json: String) =
        prefs(context).edit().putString(KEY_STRATEGY_SCHEMES, json).apply()

    fun getActiveStrategyId(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_STRATEGY_ID, "") ?: ""

    fun setActiveStrategyId(context: Context, id: String) =
        prefs(context).edit().putString(KEY_ACTIVE_STRATEGY_ID, id).apply()

    fun isFloatEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOAT_ENABLED, false)

    fun setFloatEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_FLOAT_ENABLED, enabled).apply()

    fun isDefaultFullscreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEFAULT_FULLSCREEN, false)

    fun setDefaultFullscreen(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_DEFAULT_FULLSCREEN, enabled).apply()

    fun getFloatBallSize(context: Context): Int =
        prefs(context).getInt(KEY_FLOAT_BALL_SIZE, 60) // 默认 60dp

    fun setFloatBallSize(context: Context, size: Int) =
        prefs(context).edit().putInt(KEY_FLOAT_BALL_SIZE, size).apply()

    fun getCardDisplayMode(context: Context): Int =
        prefs(context).getInt(KEY_CARD_DISPLAY_MODE, CARD_MODE_FREE)

    fun setCardDisplayMode(context: Context, mode: Int) =
        prefs(context).edit().putInt(KEY_CARD_DISPLAY_MODE, mode).apply()

    // ── OCR 模式 ──────────────────────────────────────────────────────
    fun getOcrMode(context: Context): Int =
        prefs(context).getInt(KEY_OCR_MODE, OCR_MODE_LOCAL)

    fun setOcrMode(context: Context, mode: Int) =
        prefs(context).edit().putInt(KEY_OCR_MODE, mode).apply()

    fun getCloudOcrUrl(context: Context): String =
        prefs(context).getString(KEY_CLOUD_OCR_URL, DEFAULT_CLOUD_OCR_URL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_OCR_URL

    fun setCloudOcrUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_CLOUD_OCR_URL, url).apply()

    fun getCloudOcrToken(context: Context): String =
        prefs(context).getString(KEY_CLOUD_OCR_TOKEN, DEFAULT_CLOUD_OCR_TOKEN)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_OCR_TOKEN

    fun setCloudOcrToken(context: Context, token: String) =
        prefs(context).edit().putString(KEY_CLOUD_OCR_TOKEN, token).apply()

    fun getCloudOcrType(context: Context): Int =
        prefs(context).getInt(KEY_CLOUD_OCR_TYPE, CLOUD_OCR_TYPE_LAYOUT)

    fun setCloudOcrType(context: Context, type: Int) =
        prefs(context).edit().putInt(KEY_CLOUD_OCR_TYPE, type).apply()

    fun getCloudTextOcrUrl(context: Context): String =
        prefs(context).getString(KEY_CLOUD_TEXT_OCR_URL, DEFAULT_CLOUD_TEXT_OCR_URL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_TEXT_OCR_URL

    fun setCloudTextOcrUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_CLOUD_TEXT_OCR_URL, url).apply()

    // ── 静默搜题 ──────────────────────────────────────────────────────
    fun isSilentSearchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SILENT_SEARCH, false)

    fun setSilentSearchEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_SILENT_SEARCH, enabled).apply()

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

    // ── 多轮推理策略 ────────────────────────────────────────────────────
    private const val KEY_MULTI_PASS_STRATEGY = "multi_pass_strategy"
    const val STRATEGY_STANDARD = 0    // 两轮均用默认prompt
    const val STRATEGY_SELF_CHECK = 1  // R1默认, R2自检
    const val STRATEGY_CUSTOM_R2 = 2   // R1默认, R2用户自定义
    const val STRATEGY_SINGLE = 3      // 单轮推理

    private const val KEY_CUSTOM_R2_PROMPT = "custom_r2_prompt"
    private const val KEY_SELF_CHECK_INSTRUCTION = "self_check_instruction"

    val DEFAULT_SELF_CHECK_INSTRUCTION = "重要补充：请在完成上述分析后，重新审视你的全部推理过程和最终答案。逐一检查：① 题目文字校对是否准确 ② 题型判定是否正确 ③ 逻辑拆解是否合理 ④ 选项排除理由是否充分。如发现错误或遗漏，请修正后再输出最终JSON。"

    fun getMultiPassStrategy(context: Context): Int =
        prefs(context).getInt(KEY_MULTI_PASS_STRATEGY, STRATEGY_STANDARD)

    fun setMultiPassStrategy(context: Context, strategy: Int) =
        prefs(context).edit().putInt(KEY_MULTI_PASS_STRATEGY, strategy).apply()

    fun getCustomR2Prompt(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_R2_PROMPT, "") ?: ""

    fun setCustomR2Prompt(context: Context, prompt: String) =
        prefs(context).edit().putString(KEY_CUSTOM_R2_PROMPT, prompt).apply()

    fun getSelfCheckInstruction(context: Context): String =
        prefs(context).getString(KEY_SELF_CHECK_INSTRUCTION, DEFAULT_SELF_CHECK_INSTRUCTION)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SELF_CHECK_INSTRUCTION

    fun setSelfCheckInstruction(context: Context, instruction: String) =
        prefs(context).edit().putString(KEY_SELF_CHECK_INSTRUCTION, instruction).apply()

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

    // ── 番茄钟设置 ──────────────────────────────────────────────────────
    private const val KEY_POMODORO_FOCUS_MIN = "pomodoro_focus_min"
    private const val KEY_POMODORO_SHORT_BREAK = "pomodoro_short_break"
    private const val KEY_POMODORO_LONG_BREAK = "pomodoro_long_break"
    private const val KEY_POMODORO_LONG_INTERVAL = "pomodoro_long_interval"
    private const val KEY_POMODORO_AUTO_BREAK = "pomodoro_auto_break"
    private const val KEY_POMODORO_AUTO_FOCUS = "pomodoro_auto_focus"
    private const val KEY_POMODORO_BREAK_REMIND = "pomodoro_break_remind"
    private const val KEY_POMODORO_KEEP_SCREEN = "pomodoro_keep_screen"
    private const val KEY_POMODORO_DAILY_TARGET = "pomodoro_daily_target"
    private const val KEY_POMODORO_VIBRATION = "pomodoro_vibration"
    private const val KEY_POMODORO_NOISE_VOLUME = "pomodoro_noise_volume"
    private const val KEY_POMODORO_NOISE_TYPE = "pomodoro_noise_type"

    fun getPomodoroFocusMin(context: Context): Int =
        prefs(context).getInt(KEY_POMODORO_FOCUS_MIN, 25)
    fun setPomodoroFocusMin(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_POMODORO_FOCUS_MIN, v).apply()

    fun getPomodoroShortBreak(context: Context): Int =
        prefs(context).getInt(KEY_POMODORO_SHORT_BREAK, 5)
    fun setPomodoroShortBreak(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_POMODORO_SHORT_BREAK, v).apply()

    fun getPomodoroLongBreak(context: Context): Int =
        prefs(context).getInt(KEY_POMODORO_LONG_BREAK, 15)
    fun setPomodoroLongBreak(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_POMODORO_LONG_BREAK, v).apply()

    fun getPomodoroLongInterval(context: Context): Int =
        prefs(context).getInt(KEY_POMODORO_LONG_INTERVAL, 4)
    fun setPomodoroLongInterval(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_POMODORO_LONG_INTERVAL, v).apply()

    fun isPomodoroAutoBreak(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POMODORO_AUTO_BREAK, true)
    fun setPomodoroAutoBreak(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_POMODORO_AUTO_BREAK, v).apply()

    fun isPomodoroAutoFocus(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POMODORO_AUTO_FOCUS, false)
    fun setPomodoroAutoFocus(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_POMODORO_AUTO_FOCUS, v).apply()

    fun isPomodoroBreakRemind(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POMODORO_BREAK_REMIND, true)
    fun setPomodoroBreakRemind(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_POMODORO_BREAK_REMIND, v).apply()

    fun isPomodoroKeepScreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POMODORO_KEEP_SCREEN, false)
    fun setPomodoroKeepScreen(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_POMODORO_KEEP_SCREEN, v).apply()

    fun getPomodoroDailyTarget(context: Context): Int =
        prefs(context).getInt(KEY_POMODORO_DAILY_TARGET, 8)
    fun setPomodoroDailyTarget(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_POMODORO_DAILY_TARGET, v).apply()

    fun isPomodoroVibration(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POMODORO_VIBRATION, true)
    fun setPomodoroVibration(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_POMODORO_VIBRATION, v).apply()

    fun getPomodoroNoiseVolume(context: Context): Int =
        prefs(context).getInt(KEY_POMODORO_NOISE_VOLUME, 60)
    fun setPomodoroNoiseVolume(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_POMODORO_NOISE_VOLUME, v).apply()

    fun getPomodoroNoiseType(context: Context): String =
        prefs(context).getString(KEY_POMODORO_NOISE_TYPE, "") ?: ""
    fun setPomodoroNoiseType(context: Context, v: String) =
        prefs(context).edit().putString(KEY_POMODORO_NOISE_TYPE, v).apply()
}
