package app.markline.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import app.markline.domain.Event
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 主题分享长图生成器：把某主题下的打卡记录绘成一张图文并茂的长图。
 *
 * 设计要点：
 * - 纯 Canvas + StaticLayout 手绘，宽 1080px、高度随内容自适应，零额外依赖
 * - 两遍绘制：第一遍只读照片尺寸（inJustDecodeBounds）+ EXIF 方向，算出精确布局与总高；
 *   第二遍逐张采样解码 → 画布 → recycle，同一时刻内存中只有一张照片位图，防 OOM
 * - 位图用 RGB_565（无透明需求），内存减半；输出 JPEG(85)
 * - 上限 50 条记录，且总高超过 MAX_HEIGHT 时丢弃尾部并显示截断提示
 */
object SharePosterGenerator {

    /** 单条记录：事件 + 该事件的全部照片（画廊顺序，封面在前） */
    data class Source(val event: Event, val photos: List<String>)

    private const val MAX_EVENTS = 50
    private const val MAX_HEIGHT = 12000

    // ── 版面尺寸（px，画布宽 1080）──
    private const val W = 1080
    private const val PAD = 48
    private const val LINE_X = 96
    private const val CARD_X = 132
    private const val CARD_W = W - PAD - CARD_X          // 900
    private const val CARD_PAD = 36
    private const val PHOTO_W = CARD_W - CARD_PAD * 2    // 828
    private const val PHOTO_MIN_H = 240
    private const val PHOTO_MAX_H = 540
    private const val STAT_W = 312
    private const val STAT_H = 132
    private const val STAT_GAP = 24

    // ── 配色（与 App 主题一致）──
    private val C_ACCENT = 0xFF0F6E56.toInt()
    private val C_DEEP = 0xFF085041.toInt()
    private val C_TEXT = 0xFF2C2C2A.toInt()
    private val C_SUB = 0xFF888780.toInt()
    private val C_FAINT = 0xFFB4B2A9.toInt()
    private val C_TILE = 0xFFF1EFE8.toInt()
    private val C_BORDER = 0xFFE4E4DF.toInt()
    private val C_CHIP_BG = 0xFFE1F5EE.toInt()
    private val C_WHITE = 0xFFFFFFFF.toInt()

    private const val FONT = "sans-serif"

    // ── 对外入口 ──

    fun generate(context: Context, label: String, sources: List<Source>): Bitmap {
        require(sources.isNotEmpty()) { "没有可分享的记录" }

        val sorted = sources.sortedByDescending { it.event.createdAt }
        val oldest = sorted.last().event.createdAt
        val newest = sorted.first().event.createdAt

        // ── 第一遍：布局测量 ──
        val noteWidth = PHOTO_W
        val groups = mutableListOf<Group>()
        var lastDayKey: String? = null
        for (src in sorted.take(MAX_EVENTS)) {
            val dayKey = TimeUtil.toDayKey(src.event.createdAt)
            val group = groups.lastOrNull()?.takeIf { lastDayKey == dayKey }
                ?: Group(dateLabel = TimeUtil.formatDate(src.event.createdAt)).also {
                    groups.add(it); lastDayKey = dayKey
                }
            val photos = src.photos.mapNotNull { readPhotoMeta(context, it) }
            val note = src.event.note?.takeIf { it.isNotBlank() }?.let {
                buildNoteLayout(it, noteWidth)
            }
            group.cards.add(Card(src.event, photos, note))
        }

        // ── 高度预算：超高丢弃尾部整组 ──
        var truncated = sorted.size > MAX_EVENTS
        var footerTop = measureHeight(groups)
        while (groups.size > 1 && footerTop > MAX_HEIGHT) {
            groups.removeAt(groups.size - 1)
            truncated = true
            footerTop = measureHeight(groups)
        }

        // ── 第二遍：绘制 ──
        val bitmap = Bitmap.createBitmap(W, footerTop, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(C_WHITE)

        drawHeader(canvas, label, oldest, newest, sorted)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BORDER; strokeWidth = 3f }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_ACCENT }
        var y = HEADER_H
        var lineTop = -1f
        var lineBottom = -1f

        for (group in groups) {
            val dotY = y + 18f
            if (lineTop < 0f) lineTop = dotY
            canvas.drawCircle(LINE_X.toFloat(), dotY, 10f, dotPaint)
            canvas.drawText(group.dateLabel, CARD_X.toFloat(), dotY + 11f, datePaint())
            y += 56
            for (card in group.cards) {
                drawCard(canvas, context, card, y)
                y += card.height + 24
            }
            y += 16
            lineBottom = y - 24 - 16f
        }
        if (lineBottom > lineTop) {
            canvas.drawLine(LINE_X.toFloat(), lineTop, LINE_X.toFloat(), lineBottom, linePaint)
        }

        if (truncated) {
            y += 8
            drawTruncatedChip(canvas, y)
            y += 56 + 24
        }

        drawFooter(canvas, y + 24)
        return bitmap
    }

    /** 生成 JPEG 文件并唤起系统分享面板 */
    fun shareAsImage(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "markline_share_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享主题长图"))
    }

    /** 保存到相册 Pictures/MarkLine（API 29+ 无需存储权限），返回是否成功 */
    fun saveToAlbum(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "markline_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MarkLine")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── 布局模型 ──

    private class PhotoMeta(val uri: String, val rawW: Int, val rawH: Int, val degrees: Float) {
        val aspect: Float get() = if (rawH <= 0) 1.33f else rawW.toFloat() / rawH.toFloat()
        fun drawHeight(): Int = (PHOTO_W / aspect).roundToInt().coerceIn(PHOTO_MIN_H, PHOTO_MAX_H)
        fun sampleSize(): Int {
            var s = 1
            val target = PHOTO_W * 1.6
            while (max(rawW, rawH) / (s * 2) >= target) s *= 2
            return s
        }
    }

    private class Card(val event: Event, val photos: List<PhotoMeta>, val note: StaticLayout?) {
        val height: Int = run {
            var bottom = CARD_PAD + 58                       // 头部（时间+地址）行
            photos.forEach { bottom += it.drawHeight() + 12 }
            if (photos.isNotEmpty()) bottom -= 12
            if (note != null) {
                if (photos.isNotEmpty()) bottom += 22
                bottom += note.height
            }
            bottom + CARD_PAD
        }
    }

    private class Group(val dateLabel: String) {
        val cards = mutableListOf<Card>()
        val height: Int get() = 56 + cards.sumOf { it.height + 24 } - 24 + 16
    }

    private fun measureHeight(groups: List<Group>): Int {
        var h = HEADER_H
        groups.forEachIndexed { i, g -> h += g.height + if (i > 0) 24 else 0 }
        return h + FOOTER_H
    }

    // 头部固定高度：顶栏 12 + 标题/副标题/统计区
    private const val HEADER_H = 396
    private const val FOOTER_H = 150

    // ── 绘制 ──

    private fun drawHeader(canvas: Canvas, label: String, oldest: Long, newest: Long, sorted: List<Source>) {
        // 顶部品牌色条
        val bar = Paint().apply { color = C_ACCENT }
        canvas.drawRect(0f, 0f, W.toFloat(), 12f, bar)

        // 主题名 + 「主题」chip
        val title = titlePaint()
        canvas.drawText(label, PAD.toFloat(), 122f, title)
        val titleW = title.measureText(label)
        val chipText = "主题"
        val chipPaint = textPaint(24f, false, C_ACCENT)
        val chipW = chipPaint.measureText(chipText) + 40
        val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_CHIP_BG }
        val chipRect = RectF(PAD + titleW + 20, 86f, PAD + titleW + 20 + chipW, 130f)
        canvas.drawRoundRect(chipRect, 22f, 22f, chipBg)
        val fm = chipPaint.fontMetrics
        canvas.drawText(chipText, chipRect.centerX() - chipPaint.measureText(chipText) / 2,
            chipRect.centerY() - (fm.ascent + fm.descent) / 2, chipPaint)

        // 副标题：日期范围 + 记录数
        val sub = textPaint(28f, false, C_SUB)
        val range = if (oldest == newest) TimeUtil.formatDate(oldest)
        else "${TimeUtil.formatDate(oldest)} — ${TimeUtil.formatDate(newest)}"
        canvas.drawText("$range · 共 ${sorted.size} 条记录", PAD.toFloat(), 178f, sub)

        // 统计块
        val places = sorted.mapNotNull { it.event.address?.takeIf { a -> a.isNotBlank() } }.distinct().size
        val photoCount = sorted.sumOf { it.photos.size }
        val latest = TimeUtil.friendlyDate(newest)
        drawStat(canvas, PAD.toFloat(), "覆盖地点", "$places 处")
        drawStat(canvas, (PAD + STAT_W + STAT_GAP).toFloat(), "现场照片", "$photoCount 张")
        drawStat(canvas, (PAD + (STAT_W + STAT_GAP) * 2).toFloat(), "最近打卡", latest)
    }

    private fun drawStat(canvas: Canvas, x: Float, label: String, value: String) {
        val y = 206f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_TILE }
        canvas.drawRoundRect(RectF(x, y, x + STAT_W, y + STAT_H), 16f, 16f, bg)
        canvas.drawText(label, x + 24, y + 48, textPaint(22f, false, C_SUB))
        canvas.drawText(value, x + 24, y + 102, textPaint(40f, true, C_TEXT))
    }

    private fun drawCard(canvas: Canvas, context: Context, card: Card, top: Int) {
        val left = CARD_X.toFloat()
        val right = (CARD_X + CARD_W).toFloat()
        val bottom = (top + card.height).toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = C_WHITE; style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = C_BORDER; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        canvas.drawRoundRect(RectF(left, top.toFloat(), right, bottom), 24f, 24f, bg)
        canvas.drawRoundRect(RectF(left, top.toFloat(), right, bottom), 24f, 24f, border)

        // 头部：时间（加粗）· 地址（省略）
        val timeP = textPaint(30f, true, C_TEXT)
        val addrP = textPaint(26f, false, C_SUB)
        val baseline = top + CARD_PAD + 34f
        val x0 = left + CARD_PAD
        canvas.drawText(TimeUtil.formatTime(card.event.createdAt), x0, baseline, timeP)
        val addr = card.event.address?.takeIf { it.isNotBlank() }
        if (addr != null) {
            val sep = " · "
            val timeW = timeP.measureText(TimeUtil.formatTime(card.event.createdAt))
            val sepW = addrP.measureText(sep)
            canvas.drawText(sep, x0 + timeW, baseline, addrP)
            val avail = CARD_W - CARD_PAD * 2 - timeW - sepW
            val ell = TextUtils.ellipsize(addr, addrP, avail, TextUtils.TruncateAt.END)
            canvas.drawText(ell, 0, ell.length, x0 + timeW + sepW, baseline, addrP)
        }

        // 照片：全部纵向堆叠，全宽横幅
        var cy = top + CARD_PAD + 58
        if (card.photos.isNotEmpty()) {
            for (photo in card.photos) {
                drawPhoto(context, canvas, photo, left + CARD_PAD, cy.toFloat())
                cy += photo.drawHeight() + 12
            }
            cy -= 12
        }

        // 备注（全文，自动换行）
        card.note?.let {
            if (card.photos.isNotEmpty()) cy += 22
            canvas.save()
            canvas.translate(x0, cy.toFloat())
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawPhoto(context: Context, canvas: Canvas, photo: PhotoMeta, x: Float, y: Float) {
        val h = photo.drawHeight().toFloat()
        val rect = RectF(x, y, x + PHOTO_W, y + h)
        canvas.save()
        val clip = Path().apply { addRoundRect(rect, 20f, 20f, Path.Direction.CW) }
        canvas.clipPath(clip)
        // 失效兜底底色
        canvas.drawColor(C_TILE)
        val bmp = decodeSampled(context, photo)
        if (bmp != null) {
            // 居中裁切铺满横幅
            val scale = max(rect.width() / bmp.width, rect.height() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dx = rect.left - (dw - rect.width()) / 2
            val dy = rect.top - (dh - rect.height()) / 2
            val m = Matrix().apply { postScale(scale, scale); postTranslate(dx, dy) }
            canvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
            bmp.recycle()
        }
        canvas.restore()
    }

    private fun drawTruncatedChip(canvas: Canvas, top: Int) {
        val text = "仅展示最近 $MAX_EVENTS 条"
        val p = textPaint(24f, false, C_SUB)
        val w = p.measureText(text) + 56
        val left = (W - w) / 2
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_TILE }
        val rect = RectF(left, top.toFloat(), left + w, top + 56f)
        canvas.drawRoundRect(rect, 28f, 28f, bg)
        val fm = p.fontMetrics
        canvas.drawText(text, rect.centerX() - p.measureText(text) / 2,
            rect.centerY() - (fm.ascent + fm.descent) / 2, p)
    }

    private fun drawFooter(canvas: Canvas, top: Int) {
        val line = Paint().apply { color = C_TILE; strokeWidth = 2f }
        canvas.drawLine(PAD.toFloat(), top.toFloat(), (W - PAD).toFloat(), top.toFloat(), line)
        val base = top + 62f
        canvas.drawText("MarkLine", PAD.toFloat(), base, textPaint(30f, true, C_ACCENT))
        val slogan = textPaint(22f, false, C_FAINT).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("点点 mark，汇聚成 line", (W - PAD).toFloat(), base, slogan)
    }

    // ── 照片元信息与解码 ──

    private fun readPhotoMeta(context: Context, uri: String): PhotoMeta? {
        val bounds = readBounds(context, uri) ?: return null
        val degrees = readDegrees(context, uri)
        val (w, h) = if (degrees == 90f || degrees == 270f) bounds.second to bounds.first else bounds
        if (w <= 0 || h <= 0) return null
        return PhotoMeta(uri, w, h, degrees)
    }

    private fun readBounds(context: Context, uri: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            when {
                uri.startsWith("/") -> BitmapFactory.decodeFile(uri, opts)
                uri.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(uri).path, opts)
                else -> context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readDegrees(context: Context, uri: String): Float = try {
        val orientation = when {
            uri.startsWith("/") -> ExifInterface(uri)
            uri.startsWith("file://") -> ExifInterface(Uri.parse(uri).path ?: return 0f)
            else -> context.contentResolver.openInputStream(Uri.parse(uri))?.use { ExifInterface(it) }
        }?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }

    private fun decodeSampled(context: Context, photo: PhotoMeta): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inSampleSize = photo.sampleSize() }
        val bmp = when {
            photo.uri.startsWith("/") -> BitmapFactory.decodeFile(photo.uri, opts)
            photo.uri.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(photo.uri).path, opts)
            else -> context.contentResolver.openInputStream(Uri.parse(photo.uri))?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } ?: return null
        if (photo.degrees != 0f) {
            val m = Matrix().apply { postRotate(photo.degrees) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                .also { if (it != bmp) bmp.recycle() }
        } else bmp
    } catch (_: Exception) {
        null
    }

    // ── 文本工具 ──

    private fun textPaint(size: Float, bold: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = size
        this.color = color
        this.typeface = if (bold) Typeface.create(FONT, Typeface.BOLD) else Typeface.create(FONT, Typeface.NORMAL)
    }

    private fun titlePaint() = textPaint(64f, true, C_DEEP)
    private fun datePaint() = textPaint(30f, true, 0xFF444441.toInt())

    private fun buildNoteLayout(note: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(note, 0, note.length, textPaint(26f, false, C_SUB), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1f)
            .build()
}
