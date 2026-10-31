package com.mardous.booming.coil.fetcher

import android.content.ContentResolver
import android.content.SharedPreferences
import android.util.LruCache
import android.webkit.MimeTypeMap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.mardous.booming.coil.CustomArtistImageManager
import com.mardous.booming.coil.model.ArtistImage
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source

class ArtistImageFetcher(
    private val loader: ImageLoader,
    private val options: Options,
    private val customImageManager: CustomArtistImageManager,
    private val image: ArtistImage
) : Fetcher {

    private val contentResolver: ContentResolver
        get() = options.context.contentResolver

    /**
     * Searches Spotify for the artist and returns the image URL of the best
     * name match, or null. Results are memoized per session so scrolling
     * the artists page doesn't re-search for every rebind.
     */
    private suspend fun fetchSpotifyImageUrl(artistName: String): String? {
        val context = options.context
        // Aliases resolve to the canonical name up front: merged
        // spellings share one session entry, one persisted URL and one
        // search query, so the wrongly-spelled entry inherits the
        // canonical entry's cover art.
        val canonicalName = artistName.trim().let {
            com.mardous.booming.util.Preferences.canonicalArtistName(it)
        }
        val cacheKey = canonicalName.lowercase()
        spotifySearchCache.get(cacheKey)?.let { return it }
        // Persisted URL first: survives restarts, works offline (the image
        // itself then comes from the Coil disk cache).
        SpotifyPrefs.getArtistImageUrl(context, cacheKey)?.let { persisted ->
            synchronized(spotifySearchCache) { spotifySearchCache.put(cacheKey, persisted) }
            return persisted
        }

        val candidates = runCatching {
            SpotifyClient.searchArtists(canonicalName, limit = 5)
        }.getOrDefault(emptyList())

        val best = candidates
            .filter { !it.imageUrl.isNullOrBlank() }
            .maxByOrNull { nameSimilarity(it.name, canonicalName) }
        val url = best?.takeIf {
            nameSimilarity(it.name, canonicalName) >= MIN_NAME_SIMILARITY
        }?.imageUrl

        synchronized(spotifySearchCache) { spotifySearchCache.put(cacheKey, url) }
        // Only successful matches are persisted - a failed/no-result search
        // is retried later instead of being remembered forever.
        if (url != null) {
            SpotifyPrefs.setArtistImageUrl(context, cacheKey, url)
        }
        return url
    }

    override suspend fun fetch(): FetchResult? {
        if (customImageManager.hasCustomImage(image)) {
            val imageFile = customImageManager.getCustomImageFile(image)
            if (imageFile?.isFile == true) {
                return SourceFetchResult(
                    source = ImageSource(imageFile.toOkioPath(), options.fileSystem),
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(imageFile.extension),
                    dataSource = DataSource.DISK
                )
            }
        }

        if (!image.isNameUnknown && NetworkFeature.Images.Artists.isEnabled) {
            // Spotify: higher-quality real artist photos, works even without
            // a connected account via the anonymous token.
            val spotifyImageUrl = fetchSpotifyImageUrl(image.name)
            if (spotifyImageUrl != null) {
                val data = loader.components.map(spotifyImageUrl, options)
                val output = loader.components.newFetcher(data, options, loader)
                val (fetcher) = checkNotNull(output) { "no supported fetcher for $spotifyImageUrl" }
                return fetcher.fetch()
            }
        }

        check(image.id > 0 || image.id == Artist.VARIOUS_ARTISTS_ID) { "invalid artist ID (${image.id})" }
        val stream = checkNotNull(contentResolver.openInputStream(image.coverUri)) {
            "couldn't open stream from ${image.coverUri}"
        }
        return SourceFetchResult(
            source = ImageSource(
                source = stream.source().buffer(),
                fileSystem = options.fileSystem,
                metadata = null
            ),
            mimeType = contentResolver.getType(image.coverUri),
            dataSource = DataSource.DISK
        )
    }

    class Factory(
        private val preferences: SharedPreferences,
        private val customImageManager: CustomArtistImageManager
    ) : Fetcher.Factory<ArtistImage> {
        override fun create(
            data: ArtistImage,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return ArtistImageFetcher(
                loader = imageLoader,
                options = options,
                customImageManager = customImageManager,
                image = data
            )
        }
    }

    companion object {
        /** Minimum Jaro-Winkler similarity for a Spotify name match. */
        private const val MIN_NAME_SIMILARITY = 0.90

        /**
         * Session-scoped memo of Spotify image lookups (null = searched and
         * found nothing). Kept in the companion so it survives fetcher
         * instance recreation.
         */
        private val spotifySearchCache = LruCache<String, String?>(200)

        private val nameComparator = JaroWinklerSimilarity()

        fun nameSimilarity(a: String, b: String): Double =
            nameComparator.apply(a.trim().lowercase(), b.trim().lowercase())
    }
}
