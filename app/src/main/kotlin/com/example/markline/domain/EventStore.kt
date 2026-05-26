package com.example.markline.domain

/**
 * EventStore 接口
 * 实现：SQLiteEventStore
 */
interface EventStore {

    /** 插入新 Event，返回新记录的 id */
    fun insert(event: Event): Long

    /**
     * 更新 Event 的增强字段（位置、地址、音频、状态）
     * Append-only 原则：createdAt 不可修改
     */
    fun updateEnhancement(
        id: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        audioFileName: String? = null,
        audioDuration: Int? = null,
        status: Int? = null
    )

    /** 追加 note */
    fun updateNote(id: Long, note: String)

    /** 设置主题标签 */
    fun updateLabel(id: Long, label: String)

    /** 查询所有已使用的主题标签（去重） */
    fun queryLabels(): List<String>

    /**
     * 关键字搜索：在 note、address、label、日期 中模糊匹配
     * 返回匹配结果，按 created_at 倒序，最多 200 条
     */
    fun search(query: String, limit: Int = 200): List<Event>

    /** 查询全部 Event，按 created_at 倒序 */
    fun queryAll(): List<Event>

    /** 查询最近 n 条 Event，按 created_at 倒序 */
    fun queryRecent(limit: Int): List<Event>

    /** 按 id 查询单条 */
    fun queryById(id: Long): Event?

    /** 别名，兼容 EventDetailScreen */
    fun findById(id: Long): Event? = queryById(id)

    /** 删除单条 */
    fun deleteById(id: Long)
}
