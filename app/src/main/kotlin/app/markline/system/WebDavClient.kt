package app.markline.system

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（坚果云等标准 WebDAV 服务）。
 *
 * 基于 OkHttp 实现——必须用它而不是 HttpURLConnection：
 * Android 的 HttpURLConnection 只允许 GET/POST/PUT 等 8 种标准方法，
 * 调 setRequestMethod("MKCOL") 会直接抛 ProtocolException，WebDAV 目录创建无法进行。
 *
 * - GET / PUT / MKCOL 三个操作足够支撑同步
 * - Basic Auth；坚果云需使用「应用密码」（账户信息 → 安全选项 → 添加应用密码）
 *
 * 线程模型：调用方自行放在 IO 线程。
 */
class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val pass: String
) {
    /** 规范化根目录：确保以 / 结尾 */
    private val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun authHeader(): String {
        val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
        return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    private fun newRequest(method: String, path: String, body: ByteArray? = null): Request {
        val builder = Request.Builder()
            .url(root + path.removePrefix("/"))
            .header("Authorization", authHeader())
            .header("User-Agent", "MarkLine/1.0")
        when {
            body != null -> builder.method(
                method,
                body.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            else -> builder.method(method, null)
        }
        return builder.build()
    }

    /** GET 文件内容；返回 null 表示 404（远端不存在），抛异常表示网络/认证错误 */
    fun get(path: String): ByteArray? {
        client.newCall(newRequest("GET", path)).execute().use { resp ->
            return when {
                resp.isSuccessful -> resp.body?.bytes()
                resp.code == 404 -> null
                else -> throw SyncException(describeFailure("GET $path", resp))
            }
        }
    }

    /** PUT 上传内容；2xx 视为成功 */
    fun put(path: String, bytes: ByteArray) {
        client.newCall(newRequest("PUT", path, bytes)).execute().use { resp ->
            if (!resp.isSuccessful) throw SyncException(describeFailure("PUT $path", resp))
        }
    }

    /** 创建远端目录（已存在返回 false；MKCOL 成功返回 true） */
    fun mkcol(path: String): Boolean {
        client.newCall(newRequest("MKCOL", path)).execute().use { resp ->
            return resp.isSuccessful
        }
    }

    /**
     * 确保目录存在：先建 parent（如 MarkLine/），再逐级建其下子目录（如 MarkLine/photos/）。
     * 已存在的目录 MKCOL 返回 405，静默视为成功。
     */
    fun ensureDirs(parent: String, subDirs: List<String>) {
        mkcol("$parent/")
        for (dir in subDirs) {
            mkcol("$parent/$dir/")
        }
    }

    /** 连通性测试：GET 不存在的探测文件，404 = 认证与网络均正常；异常 = 网络或凭据问题 */
    fun probe(): Boolean {
        return get("markline_probe_${System.currentTimeMillis()}.tmp") == null
    }

    /**
     * 构造带诊断信息的失败描述：HTTP 状态 + 原因短语 + 服务器错误正文摘要。
     * 坚果云的错误正文（如 401 应用密码错误、507 容量/流量超限）对定位问题非常有用。
     */
    private fun describeFailure(op: String, resp: okhttp3.Response): String {
        val code = resp.code
        val reason = resp.message ?: ""
        val body = try {
            resp.body?.string() ?: ""
        } catch (_: Exception) { "" }
        val bodySnippet = body.take(200).replace(Regex("\\s+"), " ").trim()
        val hint = when (code) {
            401 -> "（账号或应用密码错误；坚果云需用「应用密码」而非登录密码）"
            507 -> "（网盘容量或当月流量超限）"
            else -> ""
        }
        return buildString {
            append("$op HTTP $code $reason $hint")
            if (bodySnippet.isNotEmpty()) append(" | 服务器返回: $bodySnippet")
        }.trim()
    }
}

/** 同步失败异常（带用户可读消息） */
class SyncException(message: String) : Exception(message)
