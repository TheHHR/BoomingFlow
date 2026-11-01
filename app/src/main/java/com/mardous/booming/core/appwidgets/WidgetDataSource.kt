package com.mardous.booming.core.appwidgets

import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.toBitmap
import com.mardous.booming.core.appwidgets.config.SongSource
import com.mardous.booming.core.appwidgets.state.PlaybackState
import com.mardous.booming.core.appwidgets.widget.LibraryWidget
import com.mardous.booming.core.palette.PaletteProcessor
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.playback.library.LibraryProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Everything a placed widget shows that comes from the library rather than the player */
internal object WidgetDataSource : KoinComponent {

    private const val ARTWORK_SIZE = 256

    private val repository: Repository by inject()
    private val library by lazy { LibraryProvider(repository) }

    // Widget updates happen far more often than the track changes
    @Volatile
    private var cachedSong: Song? = null

    @Volatile
    private var cachedSeed: Pair<Long, Int?>? = null

    /** Streamed tracks key their palette by video id, never by numeric library id. */
    @Volatile
    private var cachedRemoteSeed: Pair<String, Int?>? = null

    fun invalidate() {
        cachedSong = null
        cachedSeed = null
        cachedRemoteSeed = null
    }

    /** Carries the data that [needs] demands. [currentSong] is the queued
     *  remote (streamed) track, if any — its title/artist/artwork/favorite
     *  bypass the local-only lookups exactly like they do in the player. */
    suspend fun enrich(
        context: Context,
        base: PlaybackState,
        needs: Set<WidgetData>,
        currentSong: Song? = null
    ): PlaybackState {
        val songIds = SongSource.entries
            .filter { it.requires in needs }
            .associateWith { library.getPlayableSongIds(it.mediaId, LibraryWidget.POOL) }

        // Remote songs resolve nothing from the library: carry the queued
        // values directly. seedColor extracts from the streamed cover
        // (same http(s) Coil branch the player uses); favorite mirrors
        // the player's twin resolution in LibraryViewModel.isSongFavorite.
        if (currentSong != null) {
            return base.copy(
                seedColor = if (WidgetData.Palette in needs) remoteSeedColorFor(context, currentSong) else null,
                isFavorite = WidgetData.Favourite in needs && isRemoteFavorite(currentSong),
                songIds = songIds
            )
        }

        val songId = base.songId
            ?: return base.copy(seedColor = null, isFavorite = false, songIds = songIds)

        val song = songFor(songId)
        return base.copy(
            currentTitle = song.title,
            currentArtist = song.artistName,
            seedColor = if (WidgetData.Palette in needs) seedColorFor(context, song) else null,
            isFavorite = WidgetData.Favourite in needs && repository.isSongFavorite(songId),
            songIds = songIds
        )
    }

    /** Same twin resolution the player shows: a downloaded copy's favorite flag wins. */
    private suspend fun isRemoteFavorite(song: Song): Boolean {
        val key = song.data.removePrefix("yt:")
        val uri = com.mardous.booming.online.download.DownloadRegistry.get(key)
            ?: com.mardous.booming.online.download.DownloadRegistry.get("sp:$key")
        val real = uri?.let { repository.songsByUri(android.net.Uri.parse(it)).firstOrNull() }
        if (real != null && real != Song.emptySong) {
            return repository.isSongFavorite(real.id)
        }
        return repository.isSongFavorite(song.id)
    }

    // Avoid repeated MediaStore reads
    private suspend fun songFor(id: Long): Song =
        cachedSong?.takeIf { it.id == id } ?: repository.songById(id).also { cachedSong = it }

    private suspend fun seedColorFor(context: Context, song: Song): Int? {
        cachedSeed?.takeIf { it.first == song.id }?.let { return it.second }
        val seed = coverOf(context, song, ARTWORK_SIZE)
            ?.let { PaletteProcessor.getPaletteColor(context, it) }
            ?.backgroundColor
        cachedSeed = song.id to seed
        return seed
    }

    /** Streamed cover palette, memoized per video id so replays skip the fetch. */
    private suspend fun remoteSeedColorFor(context: Context, song: Song): Int? {
        val key = song.data.removePrefix("yt:")
        cachedRemoteSeed?.takeIf { it.first == key }?.let { return it.second }
        val seed = coverOf(context, song, ARTWORK_SIZE)
            ?.let { PaletteProcessor.getPaletteColor(context, it) }
            ?.backgroundColor
        cachedRemoteSeed = key to seed
        return seed
    }

    private suspend fun coverOf(context: Context, song: Song, size: Int): Bitmap? {
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context)
                .data(song)
                .scale(Scale.FILL)
                .size(size)
                .build()
        )
        return result.image?.toBitmap(size, size)
    }
}
