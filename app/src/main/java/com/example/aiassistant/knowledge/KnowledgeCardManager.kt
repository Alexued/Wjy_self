package com.example.aiassistant.knowledge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object KnowledgeCardManager {

    private lateinit var db: KnowledgeCardDb

    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = KnowledgeCardDb(context.applicationContext)
        }
    }

    fun getDb(): KnowledgeCardDb {
        check(::db.isInitialized) { "KnowledgeCardManager not initialized" }
        return db
    }

    // ── 分类 ──────────────────────────────────────────────────────────

    fun getVisibleCategories(): List<KnowledgeCategory> = db.getVisibleCategories()

    fun getAllCategories(): List<KnowledgeCategory> = db.getAllCategories()

    fun getCategoryCount(categoryId: String): Int = db.getCategoryCount(categoryId)

    // ── 卡片 ──────────────────────────────────────────────────────────

    fun getCardsByCategory(categoryId: String): List<KnowledgeCard> =
        db.getCardsByCategory(categoryId)

    fun getCardsPaged(categoryId: String, page: Int, pageSize: Int = 20): Pair<List<KnowledgeCard>, Boolean> =
        db.getCardsByCategoryPaged(categoryId, page, pageSize)

    fun searchCards(categoryId: String, keyword: String, page: Int, pageSize: Int = 20): Pair<List<KnowledgeCard>, Boolean> =
        db.searchCards(categoryId, keyword, page, pageSize)

    fun getCardCount(categoryId: String): Int = db.getCardCount(categoryId)

    fun getCard(id: Long): KnowledgeCard? = db.getCard(id)

    fun addCard(card: KnowledgeCard): Long = db.insertCard(card)

    fun updateCard(card: KnowledgeCard): Int = db.updateCard(card)

    fun deleteCard(id: Long): Int = db.deleteCard(id)
    fun deleteCards(ids: Collection<Long>): Int = db.deleteCards(ids)

    // ── 导入导出 ──────────────────────────────────────────────────────

    /**
     * 导出指定分类的卡片为 JSON 字符串
     */
    fun exportToJson(categoryId: String): String {
        val cards = db.exportCardsByCategory(categoryId)
        val arr = JSONArray()
        for (card in cards) {
            arr.put(JSONObject().apply {
                put("title", card.title)
                put("content", card.content)
                put("tags", card.tags)
            })
        }
        return JSONObject().apply {
            put("category", categoryId)
            put("cards", arr)
            put("count", cards.size)
        }.toString(2)
    }

    /**
     * 从 JSON 字符串导入卡片
     * @return 导入成功的卡片数量
     */
    fun importFromJson(json: String): Int {
        val obj = JSONObject(json)
        val categoryId = obj.getString("category")
        val arr = obj.getJSONArray("cards")
        val cards = mutableListOf<KnowledgeCard>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            cards.add(KnowledgeCard(
                category = categoryId,
                title = item.optString("title", ""),
                content = item.optString("content", ""),
                tags = item.optString("tags", "[]"),
                isCustom = true
            ))
        }
        return db.importCards(cards)
    }

    /**
     * 从 CSV 格式导入卡片（标题,内容,标签）
     */
    fun importFromCsv(categoryId: String, csv: String): Int {
        val cards = mutableListOf<KnowledgeCard>()
        csv.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val parts = trimmed.split(",", limit = 3)
                if (parts.size >= 2) {
                    cards.add(KnowledgeCard(
                        category = categoryId,
                        title = parts[0].trim(),
                        content = parts[1].trim(),
                        tags = if (parts.size >= 3) parts[2].trim() else "[]",
                        isCustom = true
                    ))
                }
            }
        }
        return if (cards.isEmpty()) 0 else db.importCards(cards)
    }
}
