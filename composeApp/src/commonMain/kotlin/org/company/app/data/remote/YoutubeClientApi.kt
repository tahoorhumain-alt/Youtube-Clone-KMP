package org.company.app.data.remote

import org.company.app.domain.model.categories.VideoCategories
import org.company.app.domain.model.channel.BrandingSettings
import org.company.app.domain.model.channel.Channel
import org.company.app.domain.model.channel.ChannelX
import org.company.app.domain.model.channel.ContentDetails as ChContentDetails
import org.company.app.domain.model.channel.ContentOwnerDetails
import org.company.app.domain.model.channel.Localized as ChLocalized
import org.company.app.domain.model.channel.RelatedPlaylists
import org.company.app.domain.model.channel.Snippet as ChSnippet
import org.company.app.domain.model.channel.Statistics as ChStatistics
import org.company.app.domain.model.channel.Status as ChStatus
import org.company.app.domain.model.comments.Comments
import org.company.app.domain.model.search.Search
import org.company.app.domain.model.videos.ContentDetails
import org.company.app.domain.model.videos.High
import org.company.app.domain.model.videos.Item
import org.company.app.domain.model.videos.Medium
import org.company.app.domain.model.videos.PageInfo
import org.company.app.domain.model.videos.Snippet
import org.company.app.domain.model.videos.Statistics
import org.company.app.domain.model.videos.Thumbnails
import org.company.app.domain.model.videos.Youtube

/**
 * Same public API as before, but backed ONLY by the reels-video-storage server.
 * No YouTube API is called. A video's `id` is its playable URL.
 */
class YoutubeClientApi(private val server: ReelsApi) {

    private fun Reel.toItem(): Item {
        val s = durationSec % 60
        val m = (durationSec / 60) % 60
        val h = durationSec / 3600
        val iso = "PT" + (if (h > 0) "${h}H" else "") + "${m}M${s}S"
        val thumb = thumbnail ?: url
        return Item(
            id = url,
            snippet = Snippet(
                channelId = "server",
                channelTitle = channel,
                defaultAudioLanguage = null,
                defaultLanguage = null,
                description = description,
                publishedAt = publishedAt,
                tags = null,
                thumbnails = Thumbnails(
                    high = High(url = thumb),
                    maxres = null,
                    medium = Medium(url = thumb),
                ),
                title = title,
            ),
            statistics = Statistics(commentCount = "0", favoriteCount = "0", likeCount = likes, viewCount = views),
            contentDetails = ContentDetails(duration = iso, contentRating = null, regionRestriction = null),
        )
    }

    private fun List<Reel>.toYoutube() = Youtube(
        items = map { it.toItem() },
        pageInfo = PageInfo(resultsPerPage = size, totalResults = size),
    )

    private fun emptyYoutube() = Youtube(items = emptyList())

    // ---- Videos ----
    suspend fun getVideoList(userRegion: String): Youtube = server.longVideos().toYoutube()
    suspend fun getRelevance(): Youtube = server.longVideos().shuffled().toYoutube()
    suspend fun getRelevanceVideos(): Youtube = server.longVideos().toYoutube()

    suspend fun getVideosUsingIds(ids: String): Youtube = byIds(ids)
    suspend fun getMultipleVideos(videoId: String): Youtube = byIds(videoId)
    suspend fun getSingleVideoDetail(videoId: String): Youtube = byIds(videoId)

    private suspend fun byIds(ids: String): Youtube {
        val wanted = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return server.all().filter { it.url in wanted }.toYoutube()
    }

    // ---- Search (local filter over server videos) ----
    suspend fun getSearch(query: String, userRegion: String): Search {
        val q = query.trim().lowercase()
        val hits = server.all().filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
        return Search(items = hits.map { r ->
            val t = r.thumbnail ?: r.url
            org.company.app.domain.model.search.Item(
                etag = r.id,
                id = org.company.app.domain.model.search.Id(kind = "youtube#video", videoId = r.url),
                kind = "youtube#searchResult",
                snippet = org.company.app.domain.model.search.Snippet(
                    channelId = "server",
                    channelTitle = r.channel,
                    description = r.description,
                    liveBroadcastContent = "none",
                    publishTime = r.publishedAt,
                    publishedAt = r.publishedAt,
                    thumbnails = org.company.app.domain.model.search.Thumbnails(
                        default = org.company.app.domain.model.search.Default(url = t),
                        high = org.company.app.domain.model.search.High(url = t),
                        medium = org.company.app.domain.model.search.Medium(url = t),
                    ),
                    title = r.title,
                ),
            )
        })
    }

    suspend fun getChannelSearch(channelID: String, query: String): Search = getSearch(query, "")
    suspend fun getOwnChannelVideos(channelId: String): Search = getSearch("", "")
    suspend fun getChannelLiveStreams(channelID: String): Search = Search(items = emptyList())

    // ---- Channel (one fake channel so UI index accesses never crash) ----
    private fun fakeChannel(): Channel {
        val th = org.company.app.domain.model.channel.Thumbnails(
            default = org.company.app.domain.model.channel.Default(88, "", 88),
            high = org.company.app.domain.model.channel.High(800, "", 800),
            medium = org.company.app.domain.model.channel.Medium(240, "", 240),
        )
        return Channel(
            items = listOf(
                org.company.app.domain.model.channel.Item(
                    brandingSettings = BrandingSettings(channel = ChannelX(title = "Reels Storage")),
                    contentDetails = ChContentDetails(RelatedPlaylists(likes = "", uploads = "")),
                    contentOwnerDetails = ContentOwnerDetails(),
                    etag = "server",
                    id = "server",
                    kind = "youtube#channel",
                    snippet = ChSnippet(
                        customUrl = "@reels",
                        description = "Videos from reels-video-storage",
                        localized = ChLocalized("", "Reels Storage"),
                        publishedAt = "2024-01-01T00:00:00Z",
                        thumbnails = th,
                        title = "Reels Storage",
                    ),
                    statistics = ChStatistics(false, "0", "0", "0"),
                    status = ChStatus(isLinked = false, longUploadsStatus = "", privacyStatus = "public"),
                )
            )
        )
    }

    suspend fun getChannelDetails(channelId: String): Channel = fakeChannel()
    suspend fun getChannelBranding(channelId: String): Channel = fakeChannel()

    // ---- Not provided by the server ----
    suspend fun getPlaylists(channelId: String): Youtube = emptyYoutube()
    suspend fun getChannelSections(channelId: String): Youtube = emptyYoutube()
    suspend fun getChannelVideos(playlistID: String): Youtube = emptyYoutube()
    suspend fun getChannelCommunity(channelId: String): Youtube = emptyYoutube()
    suspend fun getComments(videoId: String, order: String): Comments = Comments(items = emptyList())
    suspend fun getVideoCategories(): VideoCategories = VideoCategories(items = emptyList())
}
