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

    // ── 番茄钟应用拦截（按任务白名单） ──────────────────────────────────
    private const val KEY_APP_BLOCKER_ENABLED = "pomodoro_app_blocker_enabled"
    private const val KEY_APP_BLOCKER_CURRENT_TASK = "pomodoro_app_blocker_current_task"
    // 每个任务的白名单存储在 key = "task_wl_{taskTitle}" 中，值为逗号分隔的包名

    /** 系统核心应用包名（始终放行） */
    val SYSTEM_DEFAULT_PACKAGES = setOf(
        "com.android.systemui",          // 系统UI
        "com.android.launcher3",         // AOSP桌面
        "com.google.android.apps.nexuslauncher", // Pixel桌面
        "com.sec.android.app.launcher",  // 三星桌面
        "com.huawei.android.launcher",   // 华为桌面
        "com.miui.home",                 // 小米桌面
        "com.oppo.launcher",             // OPPO桌面
        "com.vivo.launcher",             // vivo桌面
        "com.bbk.launcher2",             // vivo OriginOS 桌面
        "com.android.settings",          // 系统设置
        "com.android.phone",             // 电话
        "com.android.dialer",            // 拨号
        "com.android.contacts",          // 联系人
        "com.android.mms",               // 短信
        "com.android.camera",            // 相机
        "com.android.deskclock",         // 时钟
        "com.android.calculator2",       // 计算器
        "com.android.filemanager",       // 文件管理
        "com.android.packageinstaller",  // 安装器
        "com.android.permissioncontroller", // 权限管理
        "com.android.vending",           // Google Play
        "com.google.android.gms",        // Google Play 服务
        "com.android.providers.downloads", // 下载管理
        "com.android.emergency",         // 紧急呼叫
        // 手机厂商安全中心/手机管家（防止开启或运行时被误拦截）
        "com.miui.securitycenter",       // 小米/红米 手机管家/安全中心
        "com.huawei.systemmanager",      // 华为/荣耀 手机管家
        "com.coloros.safecenter",        // OPPO 手机管家
        "com.oppo.safe",                 // OPPO 安全中心
        "com.vivo.safecenter",           // vivo i管家
        "com.samsung.android.sm",        // 三星 智能管理器
        "com.samsung.android.sm_cn",     // 三星 智能管理器(中国版)
        "com.meizu.safe",                // 魅族 手机管家
        "com.android.settings.intelligence", // 系统设置搜索/智能建议
    )

    fun isAppBlockerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APP_BLOCKER_ENABLED, false)
    fun setAppBlockerEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_APP_BLOCKER_ENABLED, v).apply()

    /** 保存当前正在专注的任务名（供 Service 读取白名单） */
    fun setAppBlockerCurrentTask(context: Context, taskTitle: String) =
        prefs(context).edit().putString(KEY_APP_BLOCKER_CURRENT_TASK, taskTitle).apply()
    fun getAppBlockerCurrentTask(context: Context): String =
        prefs(context).getString(KEY_APP_BLOCKER_CURRENT_TASK, "") ?: ""

    /**
     * 获取指定任务的白名单。
     * 首次访问（无记录）时返回系统默认应用作为初始值；
     * 已有记录则返回用户实际选择的（用户可自由增删系统应用）。
     */
    fun getTaskWhitelist(context: Context, taskTitle: String): Set<String> {
        if (taskTitle.isEmpty()) return SYSTEM_DEFAULT_PACKAGES
        val key = "task_wl_$taskTitle"
        val raw = prefs(context).getString(key, null)
        if (raw == null) {
            // 首次访问，用系统应用初始化
            prefs(context).edit().putString(key, SYSTEM_DEFAULT_PACKAGES.joinToString(",")).apply()
            return SYSTEM_DEFAULT_PACKAGES
        }
        return if (raw.isEmpty()) emptySet() else raw.split(",").toSet()
    }

    /** 保存指定任务的白名单应用包名集合 */
    fun setTaskWhitelist(context: Context, taskTitle: String, packages: Set<String>) {
        prefs(context).edit().putString("task_wl_$taskTitle", packages.joinToString(",")).apply()
    }

    /** 获取所有配置过白名单的任务名列表 */
    fun getAllConfiguredTasks(context: Context): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        for ((key, value) in prefs(context).all) {
            if (key.startsWith("task_wl_") && value is String && value.isNotEmpty()) {
                val taskTitle = key.removePrefix("task_wl_")
                val count = value.split(",").size + SYSTEM_DEFAULT_PACKAGES.size
                result.add(taskTitle to count)
            }
        }
        return result.sortedBy { it.first }
    }

    // ── 词典卡片尺寸 ──────────────────────────────────────────────────

    private const val KEY_DICT_CARD_W = "dict_card_w"
    private const val KEY_DICT_CARD_H = "dict_card_h"

    fun getDictCardWidth(context: Context): Int =
        prefs(context).getInt(KEY_DICT_CARD_W, 0)

    fun getDictCardHeight(context: Context): Int =
        prefs(context).getInt(KEY_DICT_CARD_H, 0)

    fun saveDictCardSize(context: Context, w: Int, h: Int) {
        prefs(context).edit()
            .putInt(KEY_DICT_CARD_W, w)
            .putInt(KEY_DICT_CARD_H, h)
            .apply()
    }

    // ── 悬浮球长按行为 ──────────────────────────────────────────────

    private const val KEY_LONG_PRESS_ACTION = "long_press_action"
    const val LONG_PRESS_MENU = 0       // 打开菜单（默认）
    const val LONG_PRESS_CLOSE = 1      // 直接关闭

    fun getLongPressAction(context: Context): Int =
        prefs(context).getInt(KEY_LONG_PRESS_ACTION, LONG_PRESS_MENU)

    fun setLongPressAction(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_LONG_PRESS_ACTION, v).apply()

    // ── 悬浮球菜单项配置 ──────────────────────────────────────────────

    private const val KEY_MENU_ITEMS = "ball_menu_items"
    // 默认菜单项：词典, 截图模式, 关闭
    private const val DEFAULT_MENU_ITEMS = "dict,capture_mode,close"

    fun getBallMenuItems(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_MENU_ITEMS, DEFAULT_MENU_ITEMS) ?: DEFAULT_MENU_ITEMS
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun setBallMenuItems(context: Context, items: List<String>) {
        prefs(context).edit().putString(KEY_MENU_ITEMS, items.joinToString(",")).apply()
    }

    /** 所有可用菜单项 */
    val ALL_MENU_ITEMS = listOf(
        "dict" to "词典查询",
        "capture_mode" to "切换截图模式",
        "close" to "关闭悬浮球"
    )

    // ── 首次进入番茄钟引导 ──
    private const val KEY_POMODORO_FIRST_ENTRY = "pomodoro_first_entry"
    fun isPomodoroFirstEntry(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POMODORO_FIRST_ENTRY, true)
    fun setPomodoroFirstEntry(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_POMODORO_FIRST_ENTRY, v).apply()

    // ── 悬浮球单击行为 ──
    private const val KEY_FLOAT_CLICK_ACTION = "float_click_action"
    const val CLICK_ACTION_AI_ANALYZE = 0   // AI 搜题/分析（默认）
    const val CLICK_ACTION_RECORD_WRONG = 1  // 记录错题

    fun getFloatClickAction(context: Context): Int =
        prefs(context).getInt(KEY_FLOAT_CLICK_ACTION, CLICK_ACTION_AI_ANALYZE)

    fun setFloatClickAction(context: Context, action: Int) =
        prefs(context).edit().putInt(KEY_FLOAT_CLICK_ACTION, action).apply()
}
