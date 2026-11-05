package org.company.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.company.app.core.common.Constant.REELS_BASE_URL
import org.company.app.core.common.Constant.REELS_TIMEOUT

data class Reel(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String? = null,
    val isReel: Boolean = false,
    val durationSec: Int = 0,
    val views: String = "0",
    val likes: String = "0",
    val channel: String = "Reels Storage",
    val description: String = "",
    val publishedAt: String = "2024-01-01T00:00:00Z",
)

/**
 * Single source of all videos (long + reels) from https://reels-video-storage.onrender.com
 *
 * Tolerant parsing: JSON array, or object wrapping an array under
 * videos / reels / shorts / data / items / results.
 * A video is a reel when: type/category says reel|short, OR it came from a /reels|/shorts path,
 * OR duration <= 60s, OR width < height. Otherwise it is a long video.
 * Once you know your real endpoints, put them first in PATHS.
 */
class ReelsApi(private val client: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private var cache: List<Reel>? = null

    suspend fun all(forceRefresh: Boolean = false): List<Reel> = mutex.withLock {
        if (!forceRefresh) cache?.let { return it }
        val merged = LinkedHashMap<String, Reel>()
        var lastError: Exception? = null
        for (path in PATHS) {
            try {
                val response = client.get("$REELS_BASE_URL$path") {
                    timeout {
                        requestTimeoutMillis = REELS_TIMEOUT
                        socketTimeoutMillis = REELS_TIMEOUT
                        connectTimeoutMillis = REELS_TIMEOUT
                    }
                }
                if (!response.status.isSuccess()) continue
                val forceReel = path.contains("reel") || path.contains("short")
                parse(response.bodyAsText(), forceReel).forEach { r ->
                    val old = merged[r.url]
                    merged[r.url] = if (old == null) r else old.copy(isReel = old.isReel || r.isReel)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (merged.isEmpty()) throw lastError ?: IllegalStateException("No videos found on $REELS_BASE_URL")
        merged.values.toList().also { cache = it }
    }

    /** Long videos. Falls back to everything if the server has no long videos. */
    suspend fun longVideos(): List<Reel> = all().let { l -> l.filter { !it.isReel }.ifEmpty { l } }

    /** Reels/shorts. Falls back to everything if the server has no reels. */
    suspend fun reels(): List<Reel> = all().let { l -> l.filter { it.isReel }.ifEmpty { l } }

    private fun parse(body: String, forceReel: Boolean): List<Reel> {
        val root = try { json.parseToJsonElement(body) } catch (e: Exception) { return emptyList() }
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("videos", "reels", "shorts", "data", "items", "results")
                .firstNotNullOfOrNull { root[it] as? JsonArray } ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapIndexedNotNull { index, el ->
            when (el) {
                is JsonPrimitive -> prim(el)?.takeIf { looksLikeUrl(it) }?.let {
                    Reel("$index", "Video ${index + 1}", absolute(it), isReel = forceReel)
                }
                is JsonObject -> {
                    val url = el.str("url", "videoUrl", "video_url", "src", "link", "path", "file", "secure_url")
                        ?: return@mapIndexedNotNull null
                    val type = el.str("type", "category", "kind", "format", "orientation")?.lowercase().orEmpty()
                    val dur = duration(el)
                    val w = el.str("width")?.toIntOrNull()
                    val h = el.str("height")?.toIntOrNull()
                    val reel = forceReel ||
                        type.contains("reel") || type.contains("short") ||
                        type.contains("vertical") || type.contains("portrait") ||
                        (dur in 1..60) || (w != null && h != null && w < h)
                    Reel(
                        id = el.str("id", "_id", "uuid") ?: "$index",
                        title = el.str("title", "name", "caption", "filename") ?: "Video ${index + 1}",
                        url = absolute(url),
                        thumbnail = el.str("thumbnail", "thumbnailUrl", "thumb", "poster", "image")?.let { absolute(it) },
                        isReel = reel,
                        durationSec = dur,
                        views = el.str("views", "viewCount") ?: "0",
                        likes = el.str("likes", "likeCount") ?: "0",
                        channel = el.str("channel", "channelName", "author", "uploader", "user") ?: "Reels Storage",
                        description = el.str("description", "desc") ?: "",
                        publishedAt = el.str("createdAt", "created_at", "uploadedAt", "publishedAt", "date")
                            ?: "2024-01-01T00:00:00Z",
                    )
                }
                else -> null
            }
        }
    }

    private fun duration(o: JsonObject): Int {
        val raw = o.str("duration", "durationSec", "length") ?: return 0
        raw.toDoubleOrNull()?.let { return it.toInt() }
        val parts = raw.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.let { prim(it) } }

    private fun prim(p: JsonPrimitive): String? = p.content.takeIf { it.isNotBlank() && it != "null" }

    private fun looksLikeUrl(s: String) =
        s.startsWith("http") || s.startsWith("/") || Regex("\\.(mp4|webm|mkv|mov|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(s)

    private fun absolute(u: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("/") -> "$REELS_BASE_URL$u"
        else -> "$REELS_BASE_URL/$u"
    }

    private companion object {
        val PATHS = listOf(
            "/api/videos", "/videos", "/api/v1/videos",
            "/api/reels", "/reels", "/api/shorts", "/shorts",
            "",
        )
    }
}
