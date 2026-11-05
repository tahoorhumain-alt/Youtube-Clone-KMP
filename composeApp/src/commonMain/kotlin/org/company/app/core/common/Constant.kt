package org.company.app.core.common

object Constant {
    const val REELS_BASE_URL = "https://reels-video-storage.onrender.com"
    const val BASE_URL = "$REELS_BASE_URL/"
    const val TIMEOUT = 10000L
    // Render free tier sleeps; the first request can take ~60s
    const val REELS_TIMEOUT = 90000L

    /** Video "id" is now the playable URL coming from the server. */
    fun playUrl(id: String?): String = id.orEmpty()
    const val VIDEO_URL = ""
}
