package com.apk.claw.android.utils

import android.content.Context
import com.apk.claw.android.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.util.UUID

/**
 * MMKV 键值存储工具类
 *
 * 使用方式：
 *   // 在 Application.onCreate 中初始化
 *   KVUtils.init(context)
 *
 *   // 存取数据
 *   KVUtils.putString("key", "value")
 *   val value = KVUtils.getString("key", "default")
 */
object KVUtils {

    data class LlmProfile(
        val id: String,
        val name: String,
        val apiKey: String,
        val baseUrl: String,
        val modelName: String
    )

    data class LocalTaskRecord(
        val id: String,
        val task: String,
        val status: String,
        val result: String,
        val modelName: String,
        val createdAt: Long,
        val updatedAt: Long,
        val thinking: String? = null,
        val debugLog: String? = null,
        val debugEnabled: Boolean = false
    )

    object LocalTaskStatus {
        const val RUNNING = "running"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }

    private val gson = Gson()

    // 钉钉配置
    const val KEY_DINGTALK_APP_KEY = "DEFAULT_DINGTALK_APP_KEY"
    const val KEY_DINGTALK_APP_SECRET = "DEFAULT_DINGTALK_APP_SECRET"
    // 飞书配置
    const val KEY_FEISHU_APP_ID = "DEFAULT_FEISHU_APP_ID"
    const val KEY_FEISHU_APP_SECRET = "DEFAULT_FEISHU_APP_SECRET"
    // QQ 机器人配置
    const val KEY_QQ_APP_ID = "DEFAULT_QQ_APP_ID"
    const val KEY_QQ_APP_SECRET = "DEFAULT_QQ_APP_SECRET"
    // Discord 机器人配置
    const val KEY_DISCORD_BOT_TOKEN = "DEFAULT_DISCORD_BOT_TOKEN"
    // Telegram 机器人配置
    const val KEY_TELEGRAM_BOT_TOKEN = "DEFAULT_TELEGRAM_BOT_TOKEN"
    // 微信 iLink Bot 配置
    const val KEY_WECHAT_BOT_TOKEN = "DEFAULT_WECHAT_BOT_TOKEN"
    const val KEY_WECHAT_API_BASE_URL = "DEFAULT_WECHAT_API_BASE_URL"
    const val KEY_WECHAT_UPDATES_CURSOR = "DEFAULT_WECHAT_UPDATES_CURSOR"

    private lateinit var mmkv: MMKV

    private const val DEFAULT_INT = 0
    private const val DEFAULT_LONG = 0L
    private const val DEFAULT_BOOL = false
    private const val DEFAULT_FLOAT = 0f
    private const val DEFAULT_DOUBLE = 0.0

    /**
     * 在 Application.onCreate 中调用初始化
     */
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
    }

    // ==================== String ====================
    fun putString(key: String, value: String?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    // ==================== Int ====================
    fun putInt(key: String, value: Int): Boolean {
        return mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = DEFAULT_INT): Int {
        return mmkv.decodeInt(key, defaultValue)
    }

    // ==================== Long ====================
    fun putLong(key: String, value: Long): Boolean {
        return mmkv.encode(key, value)
    }

    fun getLong(key: String, defaultValue: Long = DEFAULT_LONG): Long {
        return mmkv.decodeLong(key, defaultValue)
    }

    // ==================== Boolean ====================
    fun putBoolean(key: String, value: Boolean): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = DEFAULT_BOOL): Boolean {
        return mmkv.decodeBool(key, defaultValue)
    }

    // ==================== Float ====================
    fun putFloat(key: String, value: Float): Boolean {
        return mmkv.encode(key, value)
    }

    fun getFloat(key: String, defaultValue: Float = DEFAULT_FLOAT): Float {
        return mmkv.decodeFloat(key, defaultValue)
    }

    // ==================== Double ====================
    fun putDouble(key: String, value: Double): Boolean {
        return mmkv.encode(key, value)
    }

    fun getDouble(key: String, defaultValue: Double = DEFAULT_DOUBLE): Double {
        return mmkv.decodeDouble(key, defaultValue)
    }

    // ==================== Bytes ====================
    fun putBytes(key: String, value: ByteArray?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBytes(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    // ==================== 常用操作 ====================
    fun contains(key: String): Boolean {
        return mmkv.containsKey(key)
    }

    fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }

    fun remove(vararg keys: String) {
        mmkv.removeValuesForKeys(keys)
    }

    fun clear() {
        mmkv.clearAll()
    }

    fun getAllKeys(): Array<String> {
        return mmkv.allKeys() ?: emptyArray()
    }

    /**
     * 同步写入磁盘（默认是异步的）
     */
    fun sync() {
        mmkv.sync()
    }


    // ==================== 引导页 ====================
    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // ==================== 钉钉配置 ====================
    fun getDingtalkAppKey(): String = getString(KEY_DINGTALK_APP_KEY, "")
    fun setDingtalkAppKey(value: String) = putString(KEY_DINGTALK_APP_KEY, value)
    fun getDingtalkAppSecret(): String = getString(KEY_DINGTALK_APP_SECRET, "")
    fun setDingtalkAppSecret(value: String) = putString(KEY_DINGTALK_APP_SECRET, value)

    // ==================== 飞书配置 ====================
    fun getFeishuAppId(): String = getString(KEY_FEISHU_APP_ID, "")
    fun setFeishuAppId(value: String) = putString(KEY_FEISHU_APP_ID, value)
    fun getFeishuAppSecret(): String = getString(KEY_FEISHU_APP_SECRET, "")
    fun setFeishuAppSecret(value: String) = putString(KEY_FEISHU_APP_SECRET, value)

    // ==================== QQ 机器人配置 ====================
    fun getQqAppId(): String = getString(KEY_QQ_APP_ID, "")
    fun setQqAppId(value: String) = putString(KEY_QQ_APP_ID, value)
    fun getQqAppSecret(): String = getString(KEY_QQ_APP_SECRET, "")
    fun setQqAppSecret(value: String) = putString(KEY_QQ_APP_SECRET, value)

    // ==================== Discord 机器人配置 ====================
    fun getDiscordBotToken(): String = getString(KEY_DISCORD_BOT_TOKEN, "")
    fun setDiscordBotToken(value: String) = putString(KEY_DISCORD_BOT_TOKEN, value)

    // ==================== Telegram 机器人配置 ====================
    fun getTelegramBotToken(): String = getString(KEY_TELEGRAM_BOT_TOKEN, "")
    fun setTelegramBotToken(value: String) = putString(KEY_TELEGRAM_BOT_TOKEN, value)

    // ==================== 微信 iLink Bot 配置 ====================
    fun getWechatBotToken(): String = getString(KEY_WECHAT_BOT_TOKEN, "")
    fun setWechatBotToken(value: String) = putString(KEY_WECHAT_BOT_TOKEN, value)
    fun getWechatApiBaseUrl(): String = getString(KEY_WECHAT_API_BASE_URL, "")
    fun setWechatApiBaseUrl(value: String) = putString(KEY_WECHAT_API_BASE_URL, value)
    fun getWechatUpdatesCursor(): String = getString(KEY_WECHAT_UPDATES_CURSOR, "")
    fun setWechatUpdatesCursor(value: String) = putString(KEY_WECHAT_UPDATES_CURSOR, value)

    // ==================== 局域网配置服务 ====================
    private const val KEY_CONFIG_SERVER_ENABLED = "KEY_CONFIG_SERVER_ENABLED"
    fun isConfigServerEnabled(): Boolean = getBoolean(KEY_CONFIG_SERVER_ENABLED, false)
    fun setConfigServerEnabled(enabled: Boolean) = putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled)

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"
    private const val KEY_LLM_PROFILES = "KEY_LLM_PROFILES"
    private const val KEY_SELECTED_LLM_PROFILE_ID = "KEY_SELECTED_LLM_PROFILE_ID"

    private const val DEFAULT_LLM_PROFILE_ID = "gpt-5-5-default"
    private const val DEFAULT_LLM_PROFILE_NAME = "GPT-5.5"
    private const val DEFAULT_LLM_BASE_URL = "https://gw2.oops.asia/v1"
    private const val DEFAULT_LLM_MODEL_NAME = "gpt-5.5"
    private const val LEGACY_MIMO_PROFILE_ID = "mimo-v2-5-pro-default"
    private const val LEGACY_MIMO_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
    private const val LEGACY_MIMO_MODEL_NAME = "mimo-v2.5-pro"

    private const val KEY_LOCAL_TASK_HISTORY = "KEY_LOCAL_TASK_HISTORY"
    private const val KEY_LOCAL_TASK_DEBUG_ENABLED = "KEY_LOCAL_TASK_DEBUG_ENABLED"
    private const val MAX_LOCAL_TASK_HISTORY = 50
    private const val MAX_LOCAL_TASK_TEXT_LENGTH = 120_000

    private fun defaultLlmProfile(): LlmProfile {
        return LlmProfile(
            id = DEFAULT_LLM_PROFILE_ID,
            name = DEFAULT_LLM_PROFILE_NAME,
            apiKey = BuildConfig.DEFAULT_LLM_API_KEY,
            baseUrl = DEFAULT_LLM_BASE_URL,
            modelName = DEFAULT_LLM_MODEL_NAME
        )
    }

    fun getDefaultLlmBaseUrl(): String = DEFAULT_LLM_BASE_URL

    private fun isLegacyMimoDefaultProfile(profile: LlmProfile): Boolean {
        return profile.id == LEGACY_MIMO_PROFILE_ID &&
                profile.baseUrl == LEGACY_MIMO_BASE_URL &&
                profile.modelName == LEGACY_MIMO_MODEL_NAME
    }

    private fun migrateBundledLlmProfileIfNeeded(profiles: MutableList<LlmProfile>) {
        val selectedId = getSelectedLlmProfileId()
        val removedLegacyDefault = profiles.removeAll { isLegacyMimoDefaultProfile(it) }
        if (removedLegacyDefault) {
            if (profiles.none { it.id == DEFAULT_LLM_PROFILE_ID }) {
                profiles.add(0, defaultLlmProfile())
            }
            if (selectedId == LEGACY_MIMO_PROFILE_ID) {
                setSelectedLlmProfileId(DEFAULT_LLM_PROFILE_ID)
            }
        }
    }

    private fun migrateLegacyLlmProfileIfNeeded(profiles: MutableList<LlmProfile>) {
        val hasLegacyValue = contains(KEY_LLM_API_KEY) || contains(KEY_LLM_BASE_URL) || contains(KEY_LLM_MODEL_NAME)
        if (!hasLegacyValue) return

        val legacyApiKey = getString(KEY_LLM_API_KEY, "")
        val legacyBaseUrl = getString(KEY_LLM_BASE_URL, "")
        val legacyModel = getString(KEY_LLM_MODEL_NAME, "")
        val isCurrentDefault = legacyApiKey == BuildConfig.DEFAULT_LLM_API_KEY &&
                legacyBaseUrl == DEFAULT_LLM_BASE_URL &&
                legacyModel == DEFAULT_LLM_MODEL_NAME
        val isLegacyMimoDefault = legacyBaseUrl == LEGACY_MIMO_BASE_URL &&
                legacyModel == LEGACY_MIMO_MODEL_NAME
        if (legacyApiKey.isEmpty() || isCurrentDefault || isLegacyMimoDefault) return
        if (profiles.any { it.apiKey == legacyApiKey && it.baseUrl == legacyBaseUrl && it.modelName == legacyModel }) return

        profiles.add(
            LlmProfile(
                id = "legacy-${UUID.randomUUID()}",
                name = "Imported Model",
                apiKey = legacyApiKey,
                baseUrl = legacyBaseUrl,
                modelName = legacyModel
            )
        )
    }

    fun getLlmProfiles(): List<LlmProfile> {
        val type = object : TypeToken<MutableList<LlmProfile>>() {}.type
        val profiles = try {
            gson.fromJson<MutableList<LlmProfile>>(getString(KEY_LLM_PROFILES, ""), type)
        } catch (_: Exception) {
            null
        } ?: mutableListOf()

        migrateBundledLlmProfileIfNeeded(profiles)
        if (profiles.none { it.id == DEFAULT_LLM_PROFILE_ID }) {
            profiles.add(0, defaultLlmProfile())
        }
        migrateLegacyLlmProfileIfNeeded(profiles)
        saveLlmProfiles(profiles)
        if (getSelectedLlmProfileId().isEmpty() || profiles.none { it.id == getSelectedLlmProfileId() }) {
            setSelectedLlmProfileId(profiles.first().id)
        }
        return profiles
    }

    fun saveLlmProfiles(profiles: List<LlmProfile>) {
        putString(KEY_LLM_PROFILES, gson.toJson(profiles.distinctBy { it.id }))
    }

    fun getSelectedLlmProfileId(): String = getString(KEY_SELECTED_LLM_PROFILE_ID, "")

    private fun setSelectedLlmProfileId(id: String) = putString(KEY_SELECTED_LLM_PROFILE_ID, id)

    fun getSelectedLlmProfile(): LlmProfile {
        val profiles = getLlmProfiles()
        val selectedId = getSelectedLlmProfileId()
        return profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
    }

    fun selectLlmProfile(id: String): Boolean {
        val exists = getLlmProfiles().any { it.id == id }
        if (exists) {
            setSelectedLlmProfileId(id)
        }
        return exists
    }

    fun upsertLlmProfile(profile: LlmProfile): LlmProfile {
        val normalized = profile.copy(
            id = profile.id.ifEmpty { "profile-${UUID.randomUUID()}" },
            name = profile.name.ifBlank { profile.modelName.ifBlank { "Custom Model" } },
            apiKey = profile.apiKey.trim(),
            baseUrl = profile.baseUrl.trim(),
            modelName = profile.modelName.trim()
        )
        val profiles = getLlmProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            profiles[index] = normalized
        } else {
            profiles.add(normalized)
        }
        saveLlmProfiles(profiles)
        return normalized
    }

    fun deleteLlmProfile(id: String): Boolean {
        val profiles = getLlmProfiles().toMutableList()
        if (profiles.size <= 1) return false
        val removed = profiles.removeAll { it.id == id }
        if (removed) {
            saveLlmProfiles(profiles)
            if (getSelectedLlmProfileId() == id) {
                setSelectedLlmProfileId(profiles.first().id)
            }
        }
        return removed
    }

    fun getLlmApiKey(): String = getSelectedLlmProfile().apiKey
    fun setLlmApiKey(value: String) {
        val profile = getSelectedLlmProfile()
        upsertLlmProfile(profile.copy(apiKey = value))
        putString(KEY_LLM_API_KEY, value)
    }
    fun getLlmBaseUrl(): String = getSelectedLlmProfile().baseUrl
    fun setLlmBaseUrl(value: String) {
        val profile = getSelectedLlmProfile()
        upsertLlmProfile(profile.copy(baseUrl = value))
        putString(KEY_LLM_BASE_URL, value)
    }
    fun getLlmModelName(): String = getSelectedLlmProfile().modelName
    fun setLlmModelName(value: String) {
        val profile = getSelectedLlmProfile()
        upsertLlmProfile(profile.copy(modelName = value))
        putString(KEY_LLM_MODEL_NAME, value)
    }

    /** 是否已配置 LLM（API Key 非空即视为已配置） */
    fun hasLlmConfig(): Boolean = getLlmApiKey().isNotEmpty()

    fun isLocalTaskDebugEnabled(): Boolean = getBoolean(KEY_LOCAL_TASK_DEBUG_ENABLED, false)

    fun setLocalTaskDebugEnabled(enabled: Boolean) = putBoolean(KEY_LOCAL_TASK_DEBUG_ENABLED, enabled)

    fun createLocalTask(
        task: String,
        modelName: String,
        id: String = newLocalTaskId(),
        debugEnabled: Boolean = isLocalTaskDebugEnabled()
    ): LocalTaskRecord {
        val now = System.currentTimeMillis()
        val record = LocalTaskRecord(
            id = id,
            task = task,
            status = LocalTaskStatus.RUNNING,
            result = "",
            modelName = modelName,
            createdAt = now,
            updatedAt = now,
            thinking = "",
            debugLog = "",
            debugEnabled = debugEnabled
        )
        val history = getLocalTaskHistory().toMutableList()
        history.removeAll { it.id == id }
        history.add(0, record)
        saveLocalTaskHistory(history)
        return record
    }

    fun newLocalTaskId(): String = "local-${System.currentTimeMillis()}-${UUID.randomUUID()}"

    fun getLocalTaskHistory(): List<LocalTaskRecord> {
        val type = object : TypeToken<MutableList<LocalTaskRecord>>() {}.type
        val records = try {
            gson.fromJson<MutableList<LocalTaskRecord>>(getString(KEY_LOCAL_TASK_HISTORY, ""), type)
        } catch (_: Exception) {
            null
        } ?: emptyList()
        return records.map { record ->
            record.copy(
                thinking = record.thinking ?: "",
                debugLog = record.debugLog ?: ""
            )
        }
    }

    private fun saveLocalTaskHistory(history: List<LocalTaskRecord>) {
        putString(KEY_LOCAL_TASK_HISTORY, gson.toJson(history.take(MAX_LOCAL_TASK_HISTORY)))
    }

    fun appendLocalTaskResult(id: String, content: String) {
        appendLocalTaskText(
            id = id,
            content = content,
            current = { it.result },
            update = { record, merged -> record.copy(result = merged) }
        )
    }

    fun appendLocalTaskThinking(id: String, content: String) {
        appendLocalTaskText(
            id = id,
            content = content,
            current = { it.thinking.orEmpty() },
            update = { record, merged -> record.copy(thinking = merged) }
        )
    }

    fun appendLocalTaskDebugLog(id: String, content: String, force: Boolean = false) {
        val text = content.trim()
        if (text.isEmpty()) return
        val history = getLocalTaskHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return
        val record = history[index]
        if (!force && !record.debugEnabled) return
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "[$timestamp] $text"
        val merged = mergeText(record.debugLog.orEmpty(), line, separator = "\n")
        history[index] = record.copy(debugLog = merged, updatedAt = System.currentTimeMillis())
        saveLocalTaskHistory(history)
    }

    fun updateLocalTaskStatus(id: String, status: String, message: String? = null) {
        val history = getLocalTaskHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return
        val record = history[index]
        val result = if (message.isNullOrBlank()) record.result else {
            if (record.result.isBlank()) message.trim() else record.result + "\n\n" + message.trim()
        }
        history[index] = record.copy(
            status = status,
            result = result,
            updatedAt = System.currentTimeMillis()
        )
        saveLocalTaskHistory(history)
    }

    fun clearLocalTaskHistory() = putString(KEY_LOCAL_TASK_HISTORY, "[]")

    private fun appendLocalTaskText(
        id: String,
        content: String,
        current: (LocalTaskRecord) -> String,
        update: (LocalTaskRecord, String) -> LocalTaskRecord
    ) {
        val text = content.trim()
        if (text.isEmpty()) return
        val history = getLocalTaskHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return
        val record = history[index]
        val merged = mergeText(
            current = current(record),
            addition = text
        )
        history[index] = update(record, merged).copy(updatedAt = System.currentTimeMillis())
        saveLocalTaskHistory(history)
    }

    private fun mergeText(current: String, addition: String, separator: String = "\n\n"): String {
        val merged = if (current.isBlank()) addition else current + separator + addition
        return if (merged.length <= MAX_LOCAL_TASK_TEXT_LENGTH) {
            merged
        } else {
            merged.takeLast(MAX_LOCAL_TASK_TEXT_LENGTH)
        }
    }
}
