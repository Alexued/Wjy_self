package com.example.aiassistant.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class KnowledgeCardDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "knowledge_cards.db"
        private const val DB_VERSION = 1

        // ── 分类表 ──
        const val T_CATEGORIES = "categories"
        const val COL_CAT_ID = "id"
        const val COL_CAT_NAME = "name"
        const val COL_CAT_ICON = "icon"
        const val COL_CAT_VISIBLE = "is_visible"
        const val COL_CAT_SORT = "sort_order"

        // ── 卡片表 ──
        const val T_CARDS = "cards"
        const val COL_ID = "id"
        const val COL_CATEGORY = "category"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_TAGS = "tags"
        const val COL_IS_CUSTOM = "is_custom"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_CATEGORIES (
                $COL_CAT_ID TEXT PRIMARY KEY,
                $COL_CAT_NAME TEXT NOT NULL,
                $COL_CAT_ICON TEXT NOT NULL,
                $COL_CAT_VISIBLE INTEGER NOT NULL DEFAULT 1,
                $COL_CAT_SORT INTEGER NOT NULL DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE $T_CARDS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_TAGS TEXT DEFAULT '[]',
                $COL_IS_CUSTOM INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                FOREIGN KEY ($COL_CATEGORY) REFERENCES $T_CATEGORIES($COL_CAT_ID)
            )
        """)

        db.execSQL("CREATE INDEX idx_cards_category ON $T_CARDS($COL_CATEGORY)")

        // 插入预设分类
        val presets = listOf(
            Triple("idiom", "高频成语", "📖"),
            Triple("current_affairs", "时政刷题", "📰"),
            Triple("political_theory", "政治理论", "📕"),
            Triple("essay", "申论积累", "✍️"),
            Triple("common_sense", "常识积累", "💡"),
            Triple("character", "人物素材", "👤"),
            Triple("interview", "面试素材", "🎤")
        )
        presets.forEachIndexed { index, (id, name, icon) ->
            db.execSQL(
                "INSERT INTO $T_CATEGORIES ($COL_CAT_ID, $COL_CAT_NAME, $COL_CAT_ICON, $COL_CAT_VISIBLE, $COL_CAT_SORT) VALUES (?, ?, ?, 1, ?)",
                arrayOf(id, name, icon, index)
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T_CARDS")
        db.execSQL("DROP TABLE IF EXISTS $T_CATEGORIES")
        onCreate(db)
    }

    // ── 分类操作 ──────────────────────────────────────────────────────

    fun getAllCategories(): List<KnowledgeCategory> {
        val list = mutableListOf<KnowledgeCategory>()
        readableDatabase.query(
            T_CATEGORIES, null, null, null, null, null, "$COL_CAT_SORT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(KnowledgeCategory(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(COL_CAT_ID)),
                    name = cursor.getString(cursor.getColumnIndexOrThrow(COL_CAT_NAME)),
                    icon = cursor.getString(cursor.getColumnIndexOrThrow(COL_CAT_ICON)),
                    isVisible = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CAT_VISIBLE)) == 1,
                    sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CAT_SORT))
                ))
            }
        }
        return list
    }

    fun getVisibleCategories(): List<KnowledgeCategory> {
        val list = mutableListOf<KnowledgeCategory>()
        readableDatabase.query(
            T_CATEGORIES, null, "$COL_CAT_VISIBLE = 1", null, null, null, "$COL_CAT_SORT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(KnowledgeCategory(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(COL_CAT_ID)),
                    name = cursor.getString(cursor.getColumnIndexOrThrow(COL_CAT_NAME)),
                    icon = cursor.getString(cursor.getColumnIndexOrThrow(COL_CAT_ICON)),
                    isVisible = true,
                    sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CAT_SORT))
                ))
            }
        }
        return list
    }

    fun getCategoryCount(categoryId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_CARDS WHERE $COL_CATEGORY = ?", arrayOf(categoryId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    // ── 卡片操作 ──────────────────────────────────────────────────────

    fun getCardsByCategory(categoryId: String): List<KnowledgeCard> {
        val list = mutableListOf<KnowledgeCard>()
        readableDatabase.query(
            T_CARDS, null, "$COL_CATEGORY = ?", arrayOf(categoryId),
            null, null, "$COL_UPDATED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToCard(cursor))
            }
        }
        return list
    }

    /**
     * 分页查询指定分类的卡片
     * @param categoryId 分类ID
     * @param page 页码（从0开始）
     * @param pageSize 每页条数
     * @return Pair<当前页数据, 是否还有更多>
     */
    fun getCardsByCategoryPaged(categoryId: String, page: Int, pageSize: Int = 20): Pair<List<KnowledgeCard>, Boolean> {
        val offset = page * pageSize
        val list = mutableListOf<KnowledgeCard>()
        readableDatabase.query(
            T_CARDS, null, "$COL_CATEGORY = ?", arrayOf(categoryId),
            null, null, "$COL_UPDATED_AT DESC",
            "$offset, ${pageSize + 1}"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToCard(cursor))
            }
        }
        val hasMore = list.size > pageSize
        if (hasMore) list.removeAt(list.lastIndex)
        return Pair(list, hasMore)
    }

    /**
     * 分页查询 + 关键词搜索
     */
    fun searchCards(categoryId: String, keyword: String, page: Int, pageSize: Int = 20): Pair<List<KnowledgeCard>, Boolean> {
        val offset = page * pageSize
        val like = "%$keyword%"
        val list = mutableListOf<KnowledgeCard>()
        readableDatabase.query(
            T_CARDS, null,
            "$COL_CATEGORY = ? AND ($COL_TITLE LIKE ? OR $COL_CONTENT LIKE ?)",
            arrayOf(categoryId, like, like),
            null, null, "$COL_UPDATED_AT DESC",
            "$offset, ${pageSize + 1}"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToCard(cursor))
            }
        }
        val hasMore = list.size > pageSize
        if (hasMore) list.removeAt(list.lastIndex)
        return Pair(list, hasMore)
    }

    /**
     * 获取分类下卡片总数
     */
    fun getCardCount(categoryId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_CARDS WHERE $COL_CATEGORY = ?", arrayOf(categoryId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getCard(id: Long): KnowledgeCard? {
        readableDatabase.query(
            T_CARDS, null, "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToCard(cursor) else null
        }
    }

    fun insertCard(card: KnowledgeCard): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_CATEGORY, card.category)
            put(COL_TITLE, card.title)
            put(COL_CONTENT, card.content)
            put(COL_TAGS, card.tags)
            put(COL_IS_CUSTOM, if (card.isCustom) 1 else 0)
            put(COL_CREATED_AT, now)
            put(COL_UPDATED_AT, now)
        }
        return writableDatabase.insert(T_CARDS, null, values)
    }

    fun updateCard(card: KnowledgeCard): Int {
        val values = ContentValues().apply {
            put(COL_TITLE, card.title)
            put(COL_CONTENT, card.content)
            put(COL_TAGS, card.tags)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.update(T_CARDS, values, "$COL_ID = ?", arrayOf(card.id.toString()))
    }

    fun deleteCard(id: Long): Int {
        return writableDatabase.delete(T_CARDS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun deleteCards(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            var count = 0
            for (id in ids) {
                count += db.delete(T_CARDS, "$COL_ID = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    fun deleteCardsByCategory(categoryId: String): Int {
        return writableDatabase.delete(T_CARDS, "$COL_CATEGORY = ? AND $COL_IS_CUSTOM = 1", arrayOf(categoryId))
    }

    // ── 批量导入 ──────────────────────────────────────────────────────

    fun importCards(cards: List<KnowledgeCard>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (card in cards) {
                val values = ContentValues().apply {
                    put(COL_CATEGORY, card.category)
                    put(COL_TITLE, card.title)
                    put(COL_CONTENT, card.content)
                    put(COL_TAGS, card.tags)
                    put(COL_IS_CUSTOM, 1)
                    put(COL_CREATED_AT, now)
                    put(COL_UPDATED_AT, now)
                }
                db.insert(T_CARDS, null, values)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    // ── 导出 ──────────────────────────────────────────────────────────

    fun exportCardsByCategory(categoryId: String): List<KnowledgeCard> {
        return getCardsByCategory(categoryId)
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    private fun cursorToCard(cursor: android.database.Cursor): KnowledgeCard {
        return KnowledgeCard(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            category = cursor.getString(cursor.getColumnIndexOrThrow(COL_CATEGORY)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
            content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
            tags = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAGS)),
            isCustom = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_CUSTOM)) == 1,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT))
        )
    }
}
