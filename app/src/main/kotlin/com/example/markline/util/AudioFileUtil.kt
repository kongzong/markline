package com.example.markline.util

import android.content.Context
import java.io.File

/**
 * 音频文件路径解析工具。
 *
 * 设计原则：
 * - DB 只存文件名（如 "rec_1716567890123.m4a"），不存绝对路径
 * - 运行时通过本工具统一拼接完整路径
 * - 将来若更换存储位置，只需修改此文件
 */
object AudioFileUtil {

    private const val SUB_DIR = "audio"

    /** 根据文件名获取 File 对象 */
    fun getAudioFile(context: Context, fileName: String): File =
        File(context.getExternalFilesDir(null), "$SUB_DIR/$fileName")

    /** 删除音频文件，成功返回 true */
    fun deleteAudioFile(context: Context, fileName: String): Boolean {
        val file = getAudioFile(context, fileName)
        return !file.exists() || file.delete()
    }
}
