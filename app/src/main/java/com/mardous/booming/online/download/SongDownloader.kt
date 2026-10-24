package com.mardous.booming.online.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.mardous.booming.R
import com.mardous.booming.data.mapper.toSongEntity
import com.mardous.booming.extensions.hasQ
import com.mardous.booming.online.data.innertube.YouTubeMusic
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.online.ui.OnlineToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val videoId: String,
    val title: String,
    val percent: Int,
    val state: State
) {
    enum class State { QUEUED, RUNNING, DONE, FAILED }
}

sealed interface DownloadEvent {
    data class Completed(val videoId: String, val spotifyTrackId: String?) : DownloadEvent
    data class Failed(val videoId: String, val spotifyTrackId: String?) : DownloadEvent
    data class FavoriteApplied(val videoId: String) : DownloadEvent
}

/**
 * Downloads audio-only progressive streams for Spotify tracks (matched on
 * YouTube Music) and publishes them to MediaStore so they appear in the
 * local library. Downloaded tracks are registered by videoId so playback
 * can prefer the local copy.
 */
class SongDownloader(
    private val context: Context,
    private val repository: com.mardous.booming.data.repository.Repository,
    private val musicBrainzService: com.mardous.booming.data.remote.musicbrainz.MusicBrainzService
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<DownloadTask>(Channel.UNLIMITED)
    private val enqueuedTrackIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingFavoriteVideoIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingPlaylistAdditions =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap.KeySetView<Long, Boolean>>()
    private val worker by lazy {
        scope.launch { for (task in queue) processTask(task) }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    val downloadedVideoIds: Set<String>
        get() = DownloadRegistry.all(context).keys

    fun downloadedDisplayName(videoId: String): String? =
        DownloadRegistry.displayName(context, videoId)

    fun isDownloaded(videoId: String): Boolean {
        val uri = registeredUri(videoId) ?: return false
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun registeredUri(videoId: String): String? =
        DownloadRegistry.get(context, videoId) ?: DownloadRegistry.get(context, "sp:$videoId")

    enum class FavoriteResult { APPLIED, DEFERRED, CANCELED }

    /**
     * Favorites a remote song by its real local-library twin. If the track
     * isn't downloaded yet the download is started and the favorite is
     * applied when it completes. A second tap while pending cancels it.
     */
    suspend fun favoriteRemoteSong(song: com.mardous.booming.data.model.Song): FavoriteResult {
        val videoId = song.data.removePrefix("yt:")
        if (videoId.isBlank()) return FavoriteResult.APPLIED

        if (pendingFavoriteVideoIds.remove(videoId)) {
            return FavoriteResult.CANCELED
        }

        val uri = registeredUri(videoId)
        if (uri != null) {
            val realSong = repository.songsByUri(Uri.parse(uri)).firstOrNull()
            if (realSong != null && realSong != com.mardous.booming.data.model.Song.emptySong) {
                repository.toggleFavorite(realSong)
                return FavoriteResult.APPLIED
            }
        }

        pendingFavoriteVideoIds.add(videoId)
        enqueueRemote(
            videoId = videoId,
            title = song.title,
            artist = song.artistName,
            album = song.albumName.takeIf { it.isNotBlank() },
            thumbnailUrl = song.uri.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
        )
        return FavoriteResult.DEFERRED
    }

    /**
     * Adds remote songs to local playlists via their local-library twins,
     * downloading them first when needed. Local songs pass through directly.
     */
    suspend fun addSongsToPlaylists(songs: List<com.mardous.booming.data.model.Song>, playlistIds: List<Long>) {
        if (playlistIds.isEmpty()) return
        val localSongs = mutableListOf<com.mardous.booming.data.model.Song>()
        for (song in songs) {
            val key = song.data.removePrefix("yt:")
            if (key.isBlank()) {
                localSongs.add(song)
                continue
            }
            val uri = registeredUri(key)
            val realSong = uri?.let { repository.songsByUri(Uri.parse(it)).firstOrNull() }
            if (realSong != null && realSong != com.mardous.booming.data.model.Song.emptySong) {
                localSongs.add(realSong)
                continue
            }
            // Not downloaded. The key may be a Spotify track id (from search
            // surrogates) or a YouTube video id (from the player queue) —
            // resolve an actual YouTube video id before enqueueing.
            val ytVideoId = if (key.length == 11 && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                key
            } else {
                runCatching {
                    com.mardous.booming.online.data.innertube.YouTubeMusic
                        .search("${song.title} ${song.artistName}", com.mardous.booming.online.data.innertube.SearchFilter.SONGS)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<com.mardous.booming.online.data.innertube.SearchItem.Song>()
                        ?.firstOrNull()?.song?.videoId
                }.getOrNull()
            }
            if (ytVideoId == null) {
                OnlineToast.show(context.getString(R.string.no_youtube_match_found, song.title))
                continue
            }
            if (key != ytVideoId) {
                com.mardous.booming.online.spotify.rememberVideoIdMapping(ytVideoId, key)
            }
            val pending = pendingPlaylistAdditions.getOrPut(ytVideoId) {
                java.util.concurrent.ConcurrentHashMap.newKeySet()
            }
            pending.addAll(playlistIds)
            enqueueRemote(
                videoId = ytVideoId,
                title = song.title,
                artist = song.artistName,
                album = song.albumName.takeIf { it.isNotBlank() },
                thumbnailUrl = song.uri.takeIf {
                    it.scheme == "https" &&
                        (it.host?.contains("scdn.co") == true || it.host?.contains("ytimg.com") == true)
                }?.toString(),
                spotifyTrackId = key.takeIf { it != ytVideoId }
            )
        }
        if (localSongs.isNotEmpty()) {
            for (playlistId in playlistIds) {
                repository.insertSongsInPlaylist(localSongs.map { it.toSongEntity(playlistId) })
            }
        }
    }

    private suspend fun applyPendingActions(videoId: String) {
        val uri = registeredUri(videoId) ?: return
        val realSong = repository.songsByUri(Uri.parse(uri)).firstOrNull()
            ?: return
        if (realSong == com.mardous.booming.data.model.Song.emptySong) return

        val playlistIds = pendingPlaylistAdditions.remove(videoId)
        if (playlistIds != null && playlistIds.isNotEmpty()) {
            for (playlistId in playlistIds) {
                repository.insertSongsInPlaylist(listOf(realSong.toSongEntity(playlistId)))
            }
        }
        if (pendingFavoriteVideoIds.remove(videoId)) {
            repository.toggleFavorite(realSong)
            OnlineToast.show(context.getString(R.string.favorite_added))
            _events.tryEmit(DownloadEvent.FavoriteApplied(videoId))
        }
    }

    fun enqueue(tracks: List<SpotifyTrack>, format: DownloadFormat = DownloadFormat.M4A) {
        if (tracks.isEmpty()) return
        worker
        notifyStart()
        for (track in tracks) {
            scope.launch {
                val trackKey = track.id.ifEmpty { track.name }
                if (!enqueuedTrackIds.add(trackKey)) {
                    return@launch
                }
                val resolved = withContext(Dispatchers.IO) { track.resolveForPlayback() }
                if (resolved == null) {
                    mark(track.id, track.name, DownloadProgress.State.FAILED, 0)
                    enqueuedTrackIds.remove(trackKey)
                    return@launch
                }
                val videoId = resolved.song.data.removePrefix("yt:")
                // Already downloaded: re-verify the file and refresh its
                // tags/artwork with the current metadata instead of skipping.
                val existingUri = registeredUri(videoId)
                if (existingUri != null) {
                    queue.trySend(
                        DownloadTask(
                            videoId = videoId,
                            spotifyTrackId = track.id,
                            title = track.name,
                            artist = artistOf(track),
                            album = track.album?.name,
                            thumbnailUrl = track.thumbnailUrl(),
                            format = format,
                            trackNumber = track.trackNumber,
                            year = releaseYear(track),
                            refreshUri = existingUri
                        )
                    )
                    _activeCount.value = _activeCount.value + 1
                    return@launch
                }
                // A matching file may already exist in the user's music library
                // (downloaded by an older build, another app, or before the
                // Spotify-id registry existed). Adopt it instead of re-downloading.
                val existing = withContext(Dispatchers.IO) {
                    findExistingLocalFile(track.name, artistOf(track), track.durationMs)
                }
                if (existing != null) {
                    DownloadRegistry.put(context, videoId, existing.toString(), track.name)
                    if (track.id.isNotBlank()) {
                        DownloadRegistry.put(context, "sp:${track.id}", existing.toString(), track.name)
                    }
                    mark(videoId, track.name, DownloadProgress.State.DONE, 100)
                    _events.tryEmit(DownloadEvent.Completed(videoId, track.id))
                    enqueuedTrackIds.remove(trackKey)
                    return@launch
                }
                queue.trySend(
                    DownloadTask(
                        videoId = videoId,
                        spotifyTrackId = track.id,
                        title = track.name,
                        artist = artistOf(track),
                        album = track.album?.name,
                        thumbnailUrl = track.thumbnailUrl(),
                        format = format,
                        trackNumber = track.trackNumber,
                        year = releaseYear(track),
                    )
                )
                _activeCount.value = _activeCount.value + 1
            }
        }
    }

    /**
     * Looks for an existing audio file in the device's music collection whose
     * title and artist match the given track (duration within a few seconds).
     * Falls back to a title + duration match when the artist tag differs.
     */
    private fun findExistingLocalFile(title: String, artist: String, durationMs: Int): Uri? {
        val byTitleAndArtist = queryLocalFile(
            selection = "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE AND " +
                "${MediaStore.Audio.Media.ARTIST} = ? COLLATE NOCASE",
            selectionArgs = arrayOf(title.trim(), artist.trim()),
            durationMs = durationMs
        )
        if (byTitleAndArtist != null) return byTitleAndArtist
        if (durationMs <= 0) return null
        return queryLocalFile(
            selection = "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE",
            selectionArgs = arrayOf(title.trim()),
            durationMs = durationMs
        )
    }

    private fun queryLocalFile(
        selection: String,
        selectionArgs: Array<String>,
        durationMs: Int
    ): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DURATION
        )
        return try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val duration = cursor.getLong(1)
                    if (durationMs <= 0 || duration <= 0 ||
                        Math.abs(duration - durationMs) <= 3000L
                    ) {
                        val id = cursor.getLong(0)
                        return ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local file lookup failed", e)
            null
        }
    }

    /**
     * Enqueues a download for an already-resolved online song (used by the
     * like-to-favorite flow in the playback service, which has no Spotify
     * track object — only the queue song).
     */
    fun enqueueRemote(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        thumbnailUrl: String?,
        spotifyTrackId: String? = null
    ) {
        if (videoId.isBlank()) return
        val trackId = spotifyTrackId
            ?: com.mardous.booming.online.spotify.spotifyTrackIdFor(videoId)
        worker
        notifyStart()
        scope.launch {
            val key = "yt:$videoId"
            if (isDownloaded(videoId) || !enqueuedTrackIds.add(key)) {
                return@launch
            }
            val existing = withContext(Dispatchers.IO) { findExistingLocalFile(title, artist, 0) }
            if (existing != null) {
                DownloadRegistry.put(context, videoId, existing.toString(), title)
                if (!trackId.isNullOrBlank()) {
                    DownloadRegistry.put(context, "sp:$trackId", existing.toString(), title)
                }
                mark(videoId, title, DownloadProgress.State.DONE, 100)
                _events.tryEmit(DownloadEvent.Completed(videoId, trackId))
                enqueuedTrackIds.remove(key)
                return@launch
            }
            queue.trySend(
                DownloadTask(
                    videoId = videoId,
                    spotifyTrackId = trackId,
                    title = title,
                    artist = artist,
                    album = album,
                    thumbnailUrl = thumbnailUrl,
                    format = DownloadFormat.M4A
                )
            )
            _activeCount.value = _activeCount.value + 1
        }
    }

    /**
     * Drops registry entries whose file no longer exists (e.g. deleted from
     * a file manager). Called once at app startup so badges, the downloads
     * manager and offline playback reflect reality.
     */
    fun purgeStaleEntries() {
        scope.launch {
            val entries = DownloadRegistry.all(context)
            var removed = 0
            for ((key, uri) in entries) {
                if (key.startsWith("sp:")) continue
                val alive = runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
                }.getOrDefault(false)
                if (!alive) {
                    DownloadRegistry.removeByUri(context, uri)
                    removed++
                }
            }
            if (removed > 0) {
                Log.i(TAG, "Purged $removed stale download entries")
                _events.tryEmit(DownloadEvent.Completed("purge", null))
            }
        }
    }

    /**
     * Enqueues downloads only for tracks that aren't downloaded yet — used
     * by the per-playlist "Auto download" option. Never refreshes existing
     * files, so re-running it on every playlist load is cheap.
     */
    fun enqueueMissing(tracks: List<SpotifyTrack>, format: DownloadFormat = DownloadFormat.M4A) {
        if (tracks.isEmpty()) return
        val missing = tracks.filterNot { it.id.isNotBlank() && isSpotifyTrackDownloaded(it.id) }
        if (missing.isEmpty()) return
        enqueue(missing, format)
    }

    fun deleteDownloaded(videoId: String) {
        val uri = DownloadRegistry.get(context, videoId) ?: return
        deleteUri(uri)
    }

    fun isSpotifyTrackDownloaded(trackId: String): Boolean =
        DownloadRegistry.get(context, "sp:$trackId") != null

    fun downloadedSpotifyTrackIds(): Set<String> =
        DownloadRegistry.all(context).keys
            .filter { it.startsWith("sp:") }
            .map { it.removePrefix("sp:") }
            .toSet()

    fun deleteSpotifyTrackDownload(trackId: String) {
        val uri = DownloadRegistry.get(context, "sp:$trackId") ?: return
        deleteUri(uri)
    }

    private fun deleteUri(uri: String) {
        try {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "content") {
                context.contentResolver.delete(parsed, null, null)
            } else {
                parsed.path?.let { java.io.File(it).delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete download $uri", e)
        }
        DownloadRegistry.removeByUri(context, uri)
    }

    private suspend fun processTask(task: DownloadTask) = withContext(Dispatchers.IO) {
        try {
            mark(task.videoId, task.title, DownloadProgress.State.RUNNING, 0)
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    if (task.isRefresh) {
                        performRefresh(task)
                    } else {
                        performDownload(task)
                    }
                    mark(task.videoId, task.title, DownloadProgress.State.DONE, 100)
                    _progress.value = _progress.value - task.videoId
                    _events.tryEmit(DownloadEvent.Completed(task.videoId, task.spotifyTrackId))
                    notifyUpdate()
                    applyPendingActions(task.videoId)
                    return@withContext
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt $attempt failed for ${task.videoId}", e)
                }
            }
            mark(task.videoId, task.title, DownloadProgress.State.FAILED, 0)
            _events.tryEmit(DownloadEvent.Failed(task.videoId, task.spotifyTrackId))
            val hadPending = pendingFavoriteVideoIds.remove(task.videoId) ||
                pendingPlaylistAdditions.remove(task.videoId) != null
            if (hadPending) {
                OnlineToast.show(context.getString(R.string.favorite_download_failed))
            }
        } finally {
            _activeCount.value = (_activeCount.value - 1).coerceAtLeast(0)
            if (!task.spotifyTrackId.isNullOrBlank()) {
                enqueuedTrackIds.remove(task.spotifyTrackId)
            }
            enqueuedTrackIds.remove("yt:${task.videoId}")
        }
    }

    private suspend fun performDownload(task: DownloadTask) {
        val streamUrl = YouTubeMusic.getStreamUrl(
            videoId = task.videoId,
            forceHighQuality = true,
            overridePreferOpus = task.format.preferOpus,
            title = task.title,
            artist = task.artist,
            isRetry = false
        ).getOrElse { cause ->
            throw IOException("Stream resolution failed for ${task.videoId}", cause)
        }

        val tempFile = File.createTempFile("bflow_", task.format.fileExtension, context.cacheDir)
        try {
            val request = Request.Builder().url(streamUrl).build()
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${task.videoId}")
                val body = resp.body ?: throw IOException("Empty body")
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                            if (contentLength > 0) {
                                val percent = ((total * 100) / contentLength).toInt()
                                if (percent != lastPercent) {
                                    mark(task.videoId, task.title, DownloadProgress.State.RUNNING, percent)
                                    lastPercent = percent
                                }
                            }
                        }
                        output.flush()
                        if (contentLength > 0 && total < contentLength) {
                            throw IOException("Incomplete download $total/$contentLength")
                        }
                    }
                }
            }

            val genre = fetchGenre(task.title, task.artist)
            writeTags(tempFile, task, genre)

            // Race guard: another download (or an import finishing while we
            // were streaming) may have published a matching file. Adopt it
            // instead of writing a duplicate into MediaStore.
            val existing = findExistingLocalFile(task.title, task.artist, 0)
            if (existing != null) {
                DownloadRegistry.put(context, task.videoId, existing.toString(), task.title)
                if (!task.spotifyTrackId.isNullOrBlank()) {
                    DownloadRegistry.put(context, "sp:${task.spotifyTrackId}", existing.toString(), task.title)
                }
                return
            }

            val uri = publish(tempFile, task, genre)
            if (uri == null) {
                throw IOException("Publish failed for ${task.videoId}")
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Re-verifies a downloaded song: rewrites its tags (genre, year, track
     * number, album artist) and re-embeds the artwork on the published file,
     * then rescans so the library picks the changes up. If the file is gone
     * from the media collection the stale registry entry is dropped so the
     * next download can start fresh.
     */
    private suspend fun performRefresh(task: DownloadTask) {
        val uri = Uri.parse(task.refreshUri.orEmpty())
        val genre = fetchGenre(task.title, task.artist)

        val opened = runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")
        }.getOrNull() ?: run {
            DownloadRegistry.removeByUri(context, task.refreshUri.orEmpty())
            throw IOException("Refresh target missing for ${task.videoId}")
        }

        opened.use { fd ->
            runCatching {
                TagLib.savePropertyMap(fd.dup().detachFd(), tagProperties(task, genre))
                task.thumbnailUrl?.let { url ->
                    decodeBitmap(url)?.let { bitmap ->
                        val data = ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            out.toByteArray()
                        }
                        TagLib.savePictures(
                            fd.dup().detachFd(),
                            arrayOf(Picture(data, "Embedded Front Cover", "Front Cover", "image/jpeg"))
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Refresh tag write failed for ${task.videoId}", it) }
        }

        // Update the MediaStore row's genre/year columns and rescan.
        val path = queryDataPath(uri)
        if (path != null) {
            runCatching {
                val values = ContentValues().apply {
                    genre?.let { put(MediaStore.Audio.Media.GENRE, it) }
                    task.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
                    task.trackNumber?.let { put(MediaStore.Audio.Media.TRACK, it) }
                }
                if (values.size() > 0) {
                    context.contentResolver.update(uri, values, null, null)
                }
                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            }
        }
        OnlineToast.show(context.getString(R.string.download_refreshed, task.title))
    }

    private fun queryDataPath(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private suspend fun fetchGenre(title: String, artist: String): String? {
        // MusicBrainz first — community-maintained tags with vote counts.
        runCatching {
            val search = musicBrainzService.searchRecording(title, artist)
            val recording = search.recordings.firstOrNull() ?: return@runCatching null
            val details = musicBrainzService.getRecordingDetails(recording.id)
            details.genres.maxByOrNull { it.count ?: 0 }?.name
        }.getOrNull()?.let { return it }
        return runCatching { queryItunesGenre(title, artist) }.getOrNull()
    }

    /**
     * Genre lookup via the public iTunes Search API (tokenless) — the same
     * source SpatialFlow uses for full song metadata.
     */
    private fun queryItunesGenre(title: String, artist: String): String? {
        val query = "$title $artist"
        val url = "https://itunes.apple.com/search?term=" +
            java.net.URLEncoder.encode(query, Charsets.UTF_8.name()) +
            "&entity=song&limit=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BoomingFlow/${android.os.Build.VERSION.RELEASE} (Android)")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            return results.getJSONObject(0)
                .optString("primaryGenreName")
                .takeIf { it.isNotBlank() }
        }
    }

    private fun tagProperties(
        task: DownloadTask,
        genre: String?
    ): HashMap<String, Array<String>> {
        val properties = HashMap<String, Array<String>>().apply {
            put("TITLE", arrayOf(task.title))
            put("ARTIST", arrayOf(task.artist))
            put("ALBUMARTIST", arrayOf(task.artist))
            if (!task.album.isNullOrBlank()) put("ALBUM", arrayOf(task.album))
            task.trackNumber?.let { put("TRACKNUMBER", arrayOf(it.toString())) }
            task.year?.let {
                put("YEAR", arrayOf(it.toString()))
                put("DATE", arrayOf(it.toString()))
            }
            if (!genre.isNullOrBlank()) put("GENRE", arrayOf(genre))
        }
        return properties
    }

    private fun writeTags(file: File, task: DownloadTask, genre: String?) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                TagLib.savePropertyMap(fd.dup().detachFd(), tagProperties(task, genre))

                task.thumbnailUrl?.let { url ->
                    decodeBitmap(url)?.let { bitmap ->
                        val data = ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            out.toByteArray()
                        }
                        TagLib.savePictures(
                            fd.dup().detachFd(),
                            arrayOf(Picture(data, "Embedded Front Cover", "Front Cover", "image/jpeg"))
                        )
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Tag write failed for ${task.videoId}", it) }
    }

    private fun publish(tempFile: File, task: DownloadTask, genre: String?): Uri? {
        val fileName = sanitize("${task.title} - ${task.artist}") + task.format.fileExtension
        val uri = if (hasQ()) {
            publishScoped(tempFile, task, fileName, genre)
        } else {
            publishLegacy(tempFile, task, fileName)
        }
        if (uri != null) {
            DownloadRegistry.put(context, task.videoId, uri.toString(), task.title)
            if (!task.spotifyTrackId.isNullOrBlank()) {
                DownloadRegistry.put(context, "sp:${task.spotifyTrackId}", uri.toString(), task.title)
            }
        }
        return uri
    }

    private fun publishScoped(tempFile: File, task: DownloadTask, fileName: String, genre: String?): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, task.format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, task.title)
            put(MediaStore.Audio.Media.ARTIST, task.artist)
            if (!task.album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, task.album)
            task.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
            task.trackNumber?.let { put(MediaStore.Audio.Media.TRACK, it) }
            if (!genre.isNullOrBlank()) put(MediaStore.Audio.Media.GENRE, genre)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(itemUri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Output stream null")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (e: Exception) {
            Log.e(TAG, "Scoped publish failed for ${task.videoId}", e)
            runCatching { resolver.delete(itemUri, null, null) }
            null
        }
    }

    private fun publishLegacy(tempFile: File, task: DownloadTask, fileName: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "BoomingFlow"
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val outFile = File(dir, fileName)
        return try {
            tempFile.copyTo(outFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf(task.format.mimeType), null)
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy publish failed for ${task.videoId}", e)
            null
        }
    }

    private fun decodeBitmap(url: String): Bitmap? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
    } catch (e: Exception) {
        null
    }

    private fun mark(videoId: String, title: String, state: DownloadProgress.State, percent: Int) {
        _progress.value = _progress.value + (videoId to DownloadProgress(videoId, title, percent, state))
    }

    private fun notifyStart() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_24dp)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun notifyUpdate() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!canNotify()) return
        val active = _activeCount.value
        if (active <= 0) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24dp)
                .setContentTitle(context.getString(R.string.download_notification_title))
                .setContentText(context.getString(R.string.download_notification_text, active))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun artistOf(track: SpotifyTrack): String =
        track.artists.joinToString(", ") { it.name }.ifEmpty { "Unknown artist" }

    private fun releaseYear(track: SpotifyTrack): Int? =
        track.album?.releaseDate?.take(4)?.toIntOrNull()

    private fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private data class DownloadTask(
        val videoId: String,
        val spotifyTrackId: String?,
        val title: String,
        val artist: String,
        val album: String?,
        val thumbnailUrl: String?,
        val format: DownloadFormat,
        val trackNumber: Int? = null,
        val year: Int? = null,
        val refreshUri: String? = null
    ) {
        val isRefresh get() = refreshUri != null
    }

    companion object {
        private const val TAG = "SongDownloader"
        private const val CHANNEL_ID = "booming_downloads"
        private const val NOTIFICATION_ID = 4001
        private const val MAX_ATTEMPTS = 3
        private const val RELATIVE_PATH = "Music/BoomingFlow"
    }
}

enum class DownloadFormat(val mimeType: String, val fileExtension: String, val preferOpus: Boolean) {
    M4A("audio/mp4", ".m4a", false),
    OPUS("audio/opus", ".opus", true)
}
