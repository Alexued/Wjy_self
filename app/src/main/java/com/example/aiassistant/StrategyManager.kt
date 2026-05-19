package com.example.aiassistant

import android.content.Context
import org.json.JSONArray

object StrategyManager {
    private var schemes = mutableListOf<StrategyScheme>()
    private var activeId = ""
    private var loaded = false

    val allSchemes: List<StrategyScheme> get() = schemes.toList()
    val activeScheme: StrategyScheme? get() = schemes.find { it.id == activeId }

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        activeId = AppPreferences.getActiveStrategyId(context)

        val json = AppPreferences.getStrategySchemes(context)
        if (json.isNotEmpty()) {
            schemes = mutableListOf<StrategyScheme>().also {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    it.add(StrategyScheme.fromJson(arr.getJSONObject(i)))
                }
            }
        }

        // 没有方案则创建内置4个
        if (schemes.isEmpty()) {
            val defaultModelId = ModelManager.allModels.firstOrNull()?.id ?: ""
            schemes.addAll(listOf(
                StrategyScheme(id = "builtin_single", name = "单轮推理",
                    r1ModelId = defaultModelId, r2ModelId = "none", r3ModelId = "none", isBuiltIn = true),
                StrategyScheme(id = "builtin_standard", name = "标准双审",
                    r1ModelId = defaultModelId, r2ModelId = defaultModelId, r3ModelId = defaultModelId, isBuiltIn = true),
                StrategyScheme(id = "builtin_selfcheck", name = "自检双审",
                    r1ModelId = defaultModelId, r2ModelId = defaultModelId, r3ModelId = defaultModelId, isBuiltIn = true),
                StrategyScheme(id = "builtin_customr2", name = "自定义R2",
                    r1ModelId = defaultModelId, r2ModelId = defaultModelId, r3ModelId = defaultModelId, isBuiltIn = true)
            ))
            activeId = "builtin_standard"
            save(context)
        }
    }

    fun activate(context: Context, id: String) {
        activeId = id
        AppPreferences.setActiveStrategyId(context, id)
    }

    fun add(context: Context, scheme: StrategyScheme) {
        schemes.add(scheme)
        save(context)
    }

    fun update(context: Context, scheme: StrategyScheme) {
        val idx = schemes.indexOfFirst { it.id == scheme.id }
        if (idx >= 0) {
            schemes[idx] = scheme
            save(context)
        }
    }

    fun delete(context: Context, id: String) {
        if (schemes.any { it.id == id && it.isBuiltIn }) return
        schemes.removeAll { it.id == id }
        if (activeId == id) activeId = schemes.firstOrNull()?.id ?: ""
        save(context)
    }

    /** 构造当前策略的每轮模型配置 */
    fun buildRounds(): List<RoundConfig> {
        val scheme = activeScheme ?: return emptyList()
        val rounds = mutableListOf<RoundConfig>()
        // builtin_single 或 isSingleRound() → 只用 R1
        val singleMode = scheme.id == "builtin_single" || scheme.isSingleRound()
        for ((index, modelId, thinkingOverride) in listOf(
            Triple(0, scheme.r1ModelId, scheme.r1Thinking),
            Triple(1, scheme.r2ModelId, scheme.r2Thinking),
            Triple(2, scheme.r3ModelId, scheme.r3Thinking)
        )) {
            if (singleMode && index > 0) continue
            if (modelId == "none" || modelId.isEmpty()) continue
            val model = ModelManager.get(modelId) ?: continue
            val thinking = thinkingOverride ?: model.thinkingDefault
            rounds.add(RoundConfig(model = model, thinking = thinking))
        }
        android.util.Log.d("StrategyManager", "buildRounds: scheme=${scheme.name}, singleMode=$singleMode, rounds=${rounds.size}")
        return rounds
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (s in schemes) arr.put(s.toJson())
        AppPreferences.setStrategySchemes(context, arr.toString())
        AppPreferences.setActiveStrategyId(context, activeId)
    }
}
