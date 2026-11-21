package com.bandstudio.app.update

import org.json.JSONArray
import org.json.JSONObject

/** Một bản phát hành trên GitHub Releases. [versionCode] và [sha256] do CI ghi trong phần mô tả (xem build.yml). */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val prerelease: Boolean,
)

/** Phân tích danh sách release (JSON của GitHub) và chọn bản mới nhất phù hợp. Hàm thuần để test được. */
object UpdateParser {
    /** Chỉ tải từ GitHub qua HTTPS; chặn mọi nguồn khác kể cả khi JSON bị sửa. */
    fun isAllowedUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        return host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
    }

    private fun field(body: String, name: String): String? =
        Regex("(?im)^\\s*" + name + "\\s*:\\s*(\\S+)\\s*$").find(body)?.groupValues?.get(1)

    fun parseRelease(o: JSONObject): UpdateInfo? {
        if (o.optBoolean("draft", false)) return null
        val body = o.optString("body", "")
        val code = field(body, "versionCode")?.toLongOrNull() ?: return null
        val sha = field(body, "sha256")?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (name.endsWith(".apk") && isAllowedUrl(url)) {
                return UpdateInfo(
                    versionName = o.optString("tag_name", "").removePrefix("v").ifBlank { "?" },
                    versionCode = code, apkUrl = url, sha256 = sha, size = a.optLong("size", -1L),
                    prerelease = o.optBoolean("prerelease", false),
                )
            }
        }
        return null
    }

    /** Bản có versionCode cao nhất trong danh sách (bỏ bản thử nghiệm nếu [includePrerelease] = false); null nếu không có bản hợp lệ. */
    fun latest(json: String, includePrerelease: Boolean): UpdateInfo? {
        val arr = try { JSONArray(json) } catch (e: Exception) { return null }
        var best: UpdateInfo? = null
        for (i in 0 until arr.length()) {
            val info = parseRelease(arr.optJSONObject(i) ?: continue) ?: continue
            if (info.prerelease && !includePrerelease) continue
            if (best == null || info.versionCode > best.versionCode) best = info
        }
        return best
    }
}
