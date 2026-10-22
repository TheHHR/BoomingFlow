package com.mardous.booming.online.model

import androidx.compose.runtime.Immutable
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.Serializable
import androidx.core.net.toUri

@Immutable
data class SongItem(
    @JvmField val id: Long,
    @JvmField val title: String,
    @JvmField val artist: String,
    @JvmField val albumId: Long,
    @JvmField val path: String?,
    @JvmField val duration: Long,
    @JvmField val dateAdded: Long,
    @JvmField val data: String?
) : Serializable {

    @JvmField var thumbnailUrl: String? = null
    @JvmField var animatedThumbnailUrl: String? = null
    @JvmField var albumName: String? = null
    @JvmField var videoId: String? = null
    @JvmField var artistId: String? = null
    @JvmField var lufs: Float? = null
    @JvmField var isExplicit: Boolean = false
    @JvmField var hasDolbyAtmos: Boolean = false
    @JvmField var atmosMimeType: String? = null
    @JvmField var atmosStreamUri: String? = null

    private var uriString: String? = null

    var contentUri: Uri
        get() = uriString?.toUri() ?: "content://media/external/audio/media/$id".toUri()
        set(value) {
            uriString = value.toString()
        }

    fun cloneWithAnimatedUrl(url: String): SongItem {
        return SongItem(id, title, artist, albumId, path, duration, dateAdded, data).also {
            it.thumbnailUrl = this.thumbnailUrl
            it.animatedThumbnailUrl = url
            it.videoId = this.videoId
            it.artistId = this.artistId
            it.lufs = this.lufs
            it.isExplicit = this.isExplicit
            it.uriString = this.uriString
            it.hasDolbyAtmos = this.hasDolbyAtmos
            it.atmosMimeType = this.atmosMimeType
            it.atmosStreamUri = this.atmosStreamUri
        }
    }

    // Main constructor with automatic cleaning fallbacks
    constructor(
        id: Long,
        rawTitle: String?,
        rawArtist: String?,
        albumId: Long,
        path: String?,
        duration: Long,
        dateAdded: Long
    ) : this(
        id,
        cleanTitle(rawTitle, path),
        cleanArtist(rawArtist, path),
        albumId,
        path,
        duration,
        dateAdded,
        path
    )

    // Secondary constructor without duration for backwards compatibility
    constructor(id: Long, title: String?, artist: String?, albumId: Long, path: String?, dateAdded: Long) : 
        this(id, title, artist, albumId, path, 0, dateAdded)

    fun getAlbumArtUri(): Uri? {
        if (thumbnailUrl != null && (thumbnailUrl!!.startsWith("http://") || thumbnailUrl!!.startsWith("https://"))) {
            return enhanceThumbnailUrl(thumbnailUrl!!).toUri()
        }
        // For local tracks, return the track's unique MediaStore audio content URI
        // so Coil extracts each individual song's embedded artwork without albumId collision
        if (id > 0) {
            return contentUri
        }
        if (path != null) {
            return Uri.fromFile(File(path))
        }
        if (thumbnailUrl != null) {
            return thumbnailUrl!!.toUri()
        }
        return contentUri
    }

    fun getEmbeddedPicture(context: android.content.Context): ByteArray? {
        val currentPath = path ?: return null
        if (currentPath.startsWith("http")) return null
        val mmr = android.media.MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                mmr.setDataSource(pfd.fileDescriptor)
                return mmr.embeddedPicture
            }
        } catch (_: Exception) {
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {}
        }
        return null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val songItem = other as SongItem
        return id == songItem.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    companion object {
        private fun cleanTitle(rawTitle: String?, path: String?): String {
            var cleanTitle = rawTitle ?: "Unknown Title"
            if (cleanTitle.trim().isEmpty() || cleanTitle.equals("<unknown>", ignoreCase = true)) {
                cleanTitle = "Unknown Title"
            }
            if (path != null && cleanTitle == "Unknown Title") {
                try {
                    var fileName = File(path).name
                    if (fileName.lowercase().endsWith(".mp3")) {
                        fileName = fileName.substring(0, fileName.length - 4)
                    } else if (fileName.lowercase().endsWith(".m4a")) {
                        fileName = fileName.substring(0, fileName.length - 4)
                    } else if (fileName.lowercase().endsWith(".opus")) {
                        fileName = fileName.substring(0, fileName.length - 5)
                    }
                    if (fileName.contains(" - ")) {
                        val parts = fileName.split(" - ", limit = 2)
                        cleanTitle = parts[0].trim()
                    } else {
                        cleanTitle = fileName.trim()
                    }
                } catch (_: Exception) {}
            }
            return cleanTitle
        }

        private fun cleanArtist(rawArtist: String?, path: String?): String {
            var cleanArtist = rawArtist ?: "Unknown Artist"
            if (cleanArtist.trim().isEmpty() || cleanArtist.equals("<unknown>", ignoreCase = true)) {
                cleanArtist = "Unknown Artist"
            }
            if (path != null && cleanArtist == "Unknown Artist") {
                try {
                    var fileName = File(path).name
                    if (fileName.lowercase().endsWith(".mp3")) {
                        fileName = fileName.substring(0, fileName.length - 4)
                    } else if (fileName.lowercase().endsWith(".m4a")) {
                        fileName = fileName.substring(0, fileName.length - 4)
                    } else if (fileName.lowercase().endsWith(".opus")) {
                        fileName = fileName.substring(0, fileName.length - 5)
                    }
                    if (fileName.contains(" - ")) {
                        val parts = fileName.split(" - ", limit = 2)
                        cleanArtist = parts[1].trim()
                    }
                } catch (_: Exception) {}
            }
            return cleanArtist
        }

        @JvmStatic
        @JvmOverloads
        fun createOnlineSong(videoId: String?, title: String?, artist: String?, streamUrl: String?, durationMs: Long, thumbnailUrl: String?, artistId: String? = null, animatedThumbnailUrl: String? = null, isExplicit: Boolean = false): SongItem {
            val id = videoId?.hashCode()?.toLong() ?: (streamUrl?.hashCode()?.toLong() ?: System.currentTimeMillis())
            val song = SongItem(id, title ?: "Unknown Title", artist ?: "Unknown Artist", -1, streamUrl, durationMs, System.currentTimeMillis())
            song.contentUri = (streamUrl ?: "").toUri()
            song.thumbnailUrl = thumbnailUrl?.let { enhanceThumbnailUrl(it) }
            song.animatedThumbnailUrl = animatedThumbnailUrl
            song.videoId = videoId
            song.artistId = artistId
            song.isExplicit = isExplicit
            return song
        }

        @JvmStatic
        fun enhanceThumbnailUrl(url: String): String {
            if (url.isEmpty()) return url
            
            // Handle googleusercontent/ggpht dynamic image sizing parameters
            if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
                val regex = "(=[ws]\\d+.*)$".toRegex()
                return if (regex.containsMatchIn(url)) {
                    url.replace(regex, "=w1000-h1000-l90-rj")
                } else {
                    "$url=w1000-h1000-l90-rj"
                }
            }
            
            // Handle standard YouTube video thumbnails (fall back safely to hqdefault)
            if (url.contains("ytimg.com") || url.contains("youtube.com/vi/")) {
                if (url.contains("/default.jpg") || url.contains("/mqdefault.jpg") || url.contains("/hqdefault.jpg")) {
                    return url.replace(Regex("/(default|mqdefault)\\.jpg$"), "/hqdefault.jpg")
                }
            }
            
            return url
        }
    }
}


