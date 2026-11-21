package com.bandstudio.app

import com.bandstudio.app.update.UpdateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParserTest {
    private val sha = "a".repeat(64)

    private fun release(tag: String, code: Long, pre: Boolean = false, draft: Boolean = false, url: String = "https://github.com/Khang-ayart/Makeabeat/releases/download/$tag/Makeabeat-$tag.apk", body: String? = null) =
        """{"tag_name":"$tag","prerelease":$pre,"draft":$draft,
            "body":${org.json.JSONObject.quote(body ?: "versionCode: $code\nsha256: $sha\nbranch: main")},
            "assets":[{"name":"Makeabeat-$tag.apk","browser_download_url":"$url","size":1234}]}"""

    @Test
    fun picksHighestVersionCodeAndRespectsPrereleaseFlag() {
        val json = "[" + listOf(release("v0.2.60", 60, pre = true), release("v0.2.58", 58), release("v0.2.57", 57)).joinToString(",") + "]"
        assertEquals(58L, UpdateParser.latest(json, includePrerelease = false)!!.versionCode)
        val withPre = UpdateParser.latest(json, includePrerelease = true)!!
        assertEquals(60L, withPre.versionCode); assertTrue(withPre.prerelease)
        assertEquals("0.2.60", withPre.versionName); assertEquals(sha, withPre.sha256); assertEquals(1234L, withPre.size)
    }

    @Test
    fun ignoresDraftsAndMalformedReleases() {
        val json = "[" + listOf(
            release("v0.2.70", 70, draft = true),
            release("v0.2.69", 69, body = "không có thông tin phiên bản"),
            release("v0.2.68", 68, body = "versionCode: 68\nsha256: xyz"),
            release("v0.2.5", 5),
        ).joinToString(",") + "]"
        assertEquals(5L, UpdateParser.latest(json, true)!!.versionCode)
        assertNull(UpdateParser.latest("[]", true))
        assertNull(UpdateParser.latest("không phải json", true))
    }

    @Test
    fun onlyGithubHttpsDownloadsAreAllowed() {
        assertTrue(UpdateParser.isAllowedUrl("https://github.com/a/b/releases/download/v1/x.apk"))
        assertTrue(UpdateParser.isAllowedUrl("https://objects.githubusercontent.com/abc"))
        assertFalse(UpdateParser.isAllowedUrl("http://github.com/a.apk"))
        assertFalse(UpdateParser.isAllowedUrl("https://evil.example.com/github.com/x.apk"))
        assertFalse(UpdateParser.isAllowedUrl("https://github.com.evil.com/x.apk"))
        assertFalse(UpdateParser.isAllowedUrl("https://notgithub.com/x.apk"))
        // JSON bị sửa trỏ APK sang nguồn lạ thì bị bỏ qua
        assertNull(UpdateParser.latest("[" + release("v0.2.9", 9, url = "https://evil.example.com/x.apk") + "]", true))
        assertNotNull(UpdateParser.latest("[" + release("v0.2.9", 9) + "]", true))
    }
}
