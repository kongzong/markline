package app.markline.domain

/**
 * 一条 Event 可关联多张现场照片。
 *
 * 签到 / 导入保持「一图一事件」——它们各生成一张照片并作为封面；
 * 事件详情页支持继续追加照片，形成多图（画廊）。
 *
 * @param id           自增主键
 * @param eventId      所属 Event 的 id
 * @param photoUri     照片 Uri（相册导入为 content Uri；拍照签到为 App 私有目录的 FileProvider Uri）
 * @param mediaSource  来源：MEDIA_SOURCE_IMPORTED / MEDIA_SOURCE_PHOTO
 * @param createdAt    加入时间（毫秒），用于排序
 */
data class EventPhoto(
    val id: Long = 0,
    val eventId: Long,
    val photoUri: String,
    val mediaSource: Int = Event.MEDIA_SOURCE_IMPORTED,
    val createdAt: Long = System.currentTimeMillis()
)
