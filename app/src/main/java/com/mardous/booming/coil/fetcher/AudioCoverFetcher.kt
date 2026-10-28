package com.mardous.booming.coil.fetcher

import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.kyant.taglib.TagLib
import com.mardous.booming.BuildConfig
import com.mardous.booming.coil.model.AudioCover
import com.mardous.booming.coil.util.AudioCoverUtils
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.extensions.media.asAlbumCoverUri
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import com.mardous.booming.online.data.spotify.thumbnailUrl
import okio.IOException
import okio.buffer
import okio.source

class AudioCoverFetcher(
    private val loader: ImageLoader,
    private val options: Options,
    private val cover: AudioCover
) : Fetcher {

    private val contentResolver get() = options.context.contentResolver

    override suspend fun fetch(): FetchResult? {
        val scheme = cover.uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val data = loader.components.map(cover.uri.toString(), options)
            val output = loader.components.newFetcher(data, options, loader)
            if (output != null) {
                val (fetcher) = output
                return fetcher.fetch()
            }
        }
        val stream = try {
            if (cover.isIgnoreMediaStore) {
                AudioCoverUtils.fallback(cover.path, cover.isUseFolderArt)
                    ?: contentResolver.openFileDescriptor(cover.uri, "r")?.use { fd ->
                        TagLib.getFrontCover(fd.dup().detachFd())?.data?.inputStream()
                    }
            } else {
                val albumArt = if (cover.albumId > 0) {
                    runCatching {
                        contentResolver.openInputStream(cover.albumId.asAlbumCoverUri())
                    }.getOrNull()
                } else {
                    null
                }
                albumArt
                    ?: AudioCoverUtils.fallback(cover.path, cover.isUseFolderArt)
                    ?: contentResolver.openFileDescriptor(cover.uri, "r")?.use { fd ->
                        TagLib.getFrontCover(fd.dup().detachFd())?.data?.inputStream()
                    }
            }
        } catch (e: IOException) {
            Log.e("AudioCoverFetcher", "Unable to decode cover image for ${cover.path}", e)
            null
        }

        if (stream == null &&
            !cover.artistName.isArtistNameUnknown() &&
            NetworkFeature.Images.Albums.isEnabled) {
            val imageUrl = fetchSpotifyCoverUrl()
            if (imageUrl != null) {
                val data = loader.components.map(imageUrl, options)
                val output = loader.components.newFetcher(data, options, loader)
                val (fetcher) = checkNotNull(output) { "no supported fetcher for $imageUrl" }
                return fetcher.fetch()
            }
        }

        if (stream == null) return null
        return SourceFetchResult(
            source = ImageSource(
                source = stream.source().buffer(),
                fileSystem = options.fileSystem,
                metadata = null
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    /**
     * Resolves the Spotify cover URL for this item: the session cache
     * first, then the persisted URL map (survives restarts, works
     * offline - the image itself then comes from the Coil disk cache),
     * and finally a fresh search. Successful lookups are persisted.
     *
     * The search query mirrors the tag editor's proven format
     * (title/album name first, artist last); candidates are iterated
     * and the first that actually has art wins. Track search is the
     * fallback for songs whose album tag is unreliable.
     */
    private suspend fun fetchSpotifyCoverUrl(): String? {
        val cacheKey = "${cover.artistName.trim().lowercase()}|${cover.albumName.trim().lowercase()}"
        spotifyCoverCache.get(cacheKey)?.let { return it }
        val context = options.context
        SpotifyPrefs.getAlbumCoverUrl(context, cacheKey)?.let { persisted ->
            spotifyCoverCache.put(cacheKey, persisted)
            return persisted
        }
        val query = if (cover.isAlbum) {
            "${cover.albumName} ${cover.artistName}"
        } else {
            "${cover.title} ${cover.artistName}"
        }
        val url = runCatching {
            if (cover.isAlbum) {
                SpotifyClient.searchAlbums(query, limit = 5)
                    .firstNotNullOfOrNull { it.imageUrl }
                    ?: SpotifyClient.searchTracks("${cover.title} ${cover.artistName}", limit = 5)
                        .firstNotNullOfOrNull { it.thumbnailUrl() }
            } else {
                SpotifyClient.searchTracks(query, limit = 5)
                    .firstNotNullOfOrNull { it.thumbnailUrl() }
            }
        }.onFailure { e ->
            Log.w("AudioCoverFetcher", "Spotify cover lookup failed for '$query'", e)
        }.getOrNull()
        if (BuildConfig.DEBUG) {
            Log.d("AudioCoverFetcher", "Online cover lookup '$query' -> $url")
        }
        if (url != null) {
            spotifyCoverCache.put(cacheKey, url)
            SpotifyPrefs.setAlbumCoverUrl(context, cacheKey, url)
        }
        return url
    }

    class Factory(
        private val preferences: SharedPreferences
    ) : Fetcher.Factory<AudioCover> {
        override fun create(
            data: AudioCover,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return AudioCoverFetcher(
                loader = imageLoader,
                options = options,
                cover = data
            )
        }
    }

    companion object {
        /**
         * Session-scoped memo of resolved Spotify cover URLs, kept in the
         * companion so it survives fetcher instance recreation.
         */
        private val spotifyCoverCache = LruCache<String, String>(200)
    }
}