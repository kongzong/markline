package app.markline.domain

/**
 * 核心数据模型：一切都是 Event
 *
 * status 定义：
 *   0 = 已创建（刚写入，增强信息尚未补全）
 *   1 = 已完成
 *   2 = 定位失败
 *   3 = 录音失败
 */
data class Event(
    val id: Long = 0,

    /** 签到时间，Unix 毫秒时间戳，签到后立即写入，永不修改 */
    val createdAt: Long,

    /** 纬度，定位失败时为 null */
    val latitude: Double? = null,

    /** 经度，定位失败时为 null */
    val longitude: Double? = null,

    /** 逆地理编码地址，解析失败时为 null */
    val address: String? = null,

    /** 录音文件名（不含路径），录音失败或未启用时为 null */
    val audioFileName: String? = null,

    /** 录音时长（毫秒），audioFileName 为 null 时为 null */
    val audioDuration: Int? = null,

    /** 用户备注，可后续追加 */
    val note: String? = null,

    /** 主题标签，用于将一系列 Mark 统一命名分组 */
    val label: String? = null,

    /** 事件状态 */
    val status: Int = STATUS_CREATED
) {
    companion object {
        const val STATUS_CREATED = 0
        const val STATUS_DONE = 1
        const val STATUS_LOCATION_FAILED = 2
        const val STATUS_AUDIO_FAILED = 3
    }
}
