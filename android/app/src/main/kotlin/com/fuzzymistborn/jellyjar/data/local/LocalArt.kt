package com.fuzzymistborn.jellyjar.data.local

import android.content.Context
import java.io.File

/**
 * Poster art kept on disk so it still shows offline. One file per Jellyfin item id
 * (`filesDir/thumbnails/<id>.jpg`, the 400px Primary image) — written for downloaded
 * movies/episodes and for the series and season they belong to.
 */
object LocalArt {
    private val primaryImage = Regex("""/Items/([^/?]+)/Images/Primary(?:\?(.*))?$""")

    fun dir(context: Context): File = File(context.filesDir, "thumbnails")

    fun file(context: Context, itemId: String): File = File(dir(context), "$itemId.jpg")

    /**
     * `file://` URI for the saved poster behind a Jellyfin Primary-image URL, or null when the URL
     * isn't one or nothing is saved for that item. Only the default poster size is mapped: a
     * larger request (e.g. a 1080px hero) shouldn't be answered with a 400px file.
     */
    fun uriForImageUrl(context: Context, url: String): String? {
        val match = primaryImage.find(url) ?: return null
        val maxWidth = match.groupValues[2].split('&')
            .firstOrNull { it.startsWith("maxWidth=") }?.removePrefix("maxWidth=")?.toIntOrNull()
        if (maxWidth != null && maxWidth > 500) return null
        val f = file(context, match.groupValues[1])
        return if (f.isFile && f.length() > 0) "file://${f.absolutePath}" else null
    }
}
