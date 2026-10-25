package app.markline.domain

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
     * 各主题标签及其记录数（仅统计有主题的），
     * 按条数倒序、同条数按标签名升序；用于筛选弹层展示
     */
    fun queryLabelCounts(): List<Pair<String, Int>>

    /** 全库记录总数（含无主题记录），用于筛选弹层「全部」计数 */
    fun countAll(): Int

    /**
     * 按主题标签精确过滤，按 created_at 倒序
     * 不限时间范围，最多返回 limit 条
     */
    fun queryByLabel(label: String, limit: Int = 200): List<Event>

    /**
     * 关键字搜索：在 note、address、label、日期 中模糊匹配
     * 返回匹配结果，按 created_at 倒序，最多 200 条
     */
    fun search(query: String, limit: Int = 200): List<Event>

    /** 查询全部 Event，按 created_at 倒序 */
    fun queryAll(): List<Event>

    /** 查询最近 n 条 Event，按 created_at 倒序 */
    fun queryRecent(limit: Int): List<Event>

    /**
     * 分页查询最近 Event，按 created_at 倒序
     * offset 从 0 开始；用于 Line 瀑布流滚动懒加载，避免一次性拉取全表
     */
    fun queryRecentPage(limit: Int, offset: Int): List<Event>

    /** 按 id 查询单条 */
    fun queryById(id: Long): Event?

    /** 按 uuid 查询单条（跨设备导入用） */
    fun findByUuid(uuid: String): Event?

    /** 按关联照片 Uri 查询单条（相册导入去重用），同时查 event_photo 与 event 封面 */
    fun findByPhotoUri(photoUri: String): Event?

    /** 追加一张照片到指定 Event（返回新行 id） */
    fun insertEventPhoto(
        eventId: Long,
        photoUri: String,
        mediaSource: Int,
        createdAt: Long = System.currentTimeMillis()
    ): Long

    /** 查询某 Event 的全部照片（按加入顺序）；表为空时回退到 event 封面单图（兼容旧数据） */
    fun getEventPhotos(eventId: Long): List<EventPhoto>

    /** 删除某张照片 */
    fun deleteEventPhoto(photoId: Long)

    /** 设置 / 清除 Event 封面（event.photo_uri，列表缩略图用） */
    fun setCoverPhoto(eventId: Long, photoUri: String?)

    /** 别名，兼容 EventDetailScreen */
    fun findById(id: Long): Event? = queryById(id)

    /** 删除单条（级联删除 event_photo 及拍照签到产生的私有文件） */
    fun deleteById(id: Long)
}
