package com.dreemurr.shinsekai.update

import com.dreemurr.shinsekai.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// 下载页地址；页面头部「手动维护区」的 APP_VERSION / APK_URL 是版本信息唯一数据源。
private const val PAGE_URL = "https://dreemurr.com.cn/shinsekai/"

// APK_URL 提取失败时的回退路径（与下载页「固定文件名覆盖更新」约定一致）。
private const val DEFAULT_APK_PATH = "downloads/Shinsekai-Android.apk"

private const val TIMEOUT_SECONDS = 10L

private val VERSION_REGEX = Regex("APP_VERSION\\s*=\\s*\"([^\"]+)\"")
private val APK_URL_REGEX = Regex("APK_URL\\s*=\\s*\"([^\"]+)\"")

sealed interface UpdateCheckResult {
    // downloadUrl 为已解析成绝对地址的 APK 直链。
    data class Latest(val versionName: String, val downloadUrl: String) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    // message 为中文原因。
    data class Failure(val message: String) : UpdateCheckResult
}

// 检查更新：GET 下载页 HTML，正则提取手动维护区两个变量比较版本；服务器侧零改动，发版仍是覆盖 APK + 改 APP_VERSION。
class UpdateRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(PAGE_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    UpdateCheckResult.Failure("服务器响应异常（HTTP ${response.code}）")
                } else {
                    val html = runCatching { response.body.string() }.getOrNull()
                    if (html.isNullOrBlank()) {
                        UpdateCheckResult.Failure("服务器返回空响应")
                    } else {
                        resolveUpdate(html)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            UpdateCheckResult.Failure(friendlyError(e))
        }
    }

    // 解析手动维护区并比较版本；服务器版本严格大于本机才返回 Latest（相等或更旧都按已最新，防降级弹窗）。
    private fun resolveUpdate(html: String): UpdateCheckResult {
        val serverRaw = VERSION_REGEX.find(html)?.groupValues?.get(1)
            ?: return UpdateCheckResult.Failure("版本信息解析失败")
        val server = parseVersion(serverRaw)
            ?: return UpdateCheckResult.Failure("服务器版本格式错误")
        val local = parseVersion(BuildConfig.VERSION_NAME)
            ?: return UpdateCheckResult.Failure("本机版本格式错误")
        if (!isNewer(server, local)) return UpdateCheckResult.UpToDate

        // APK_URL 是页面相对路径，按页面地址解析成绝对直链；解析失败回退固定路径。
        val apkUrl = APK_URL_REGEX.find(html)?.groupValues?.get(1) ?: DEFAULT_APK_PATH
        val downloadUrl = runCatching { URI(PAGE_URL).resolve(apkUrl).toString() }
            .getOrDefault(PAGE_URL + DEFAULT_APK_PATH)
        return UpdateCheckResult.Latest(serverRaw, downloadUrl)
    }

    // 版本号剥掉 v/V 前缀后按「.」分段转数字；任何一段非数字即格式错误。
    private fun parseVersion(raw: String): List<Int>? {
        val body = raw.trim().removePrefix("v").removePrefix("V")
        if (body.isBlank()) return null
        return body.split(".").map { segment -> segment.trim().toIntOrNull() ?: return null }
    }

    // 逐段数字比较，缺段按 0 补齐（1.0 与 1.0.0 视为相等）。
    private fun isNewer(server: List<Int>, local: List<Int>): Boolean {
        for (i in 0 until maxOf(server.size, local.size)) {
            val a = server.getOrElse(i) { 0 }
            val b = local.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "无法解析主机，请检查网络"
        is IOException -> "网络连接失败，请检查网络"
        else -> e.message ?: "检查失败"
    }
}
