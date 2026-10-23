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
import com.mardous.booming.extensions.hasQ
import com.mardous.booming.online.data.innertube.YouTubeMusic
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.spotify.resolveForPlayback
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
}

/**
 * Downloads audio-only progressive streams for Spotify tracks (matched on
 * YouTube Music) and publishes them to MediaStore so they appear in the
 * local library. Downloaded tracks are registered by videoId so playback
 * can prefer the local copy.
 */
class SongDownloader(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<DownloadTask>(Channel.UNLIMITED)
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
        val uri = DownloadRegistry.get(context, videoId) ?: return false
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun enqueue(tracks: List<SpotifyTrack>, format: DownloadFormat = DownloadFormat.M4A) {
        if (tracks.isEmpty()) return
        worker
        notifyStart()
        for (track in tracks) {
            scope.launch {
                val resolved = withContext(Dispatchers.IO) { track.resolveForPlayback() }
                if (resolved == null) {
                    mark(track.id, track.name, DownloadProgress.State.FAILED, 0)
                    return@launch
                }
                val videoId = resolved.song.data.removePrefix("yt:")
                if (isDownloaded(videoId)) {
                    mark(videoId, track.name, DownloadProgress.State.DONE, 100)
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
                        format = format
                    )
                )
                _activeCount.value = _activeCount.value + 1
            }
        }
    }

    /**
     * Looks for an existing audio file in the device's music collection whose
     * title and artist match the given track (duration within a few seconds).
     */
    private fun findExistingLocalFile(title: String, artist: String, durationMs: Int): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DURATION
        )
        val selection =
            "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE AND " +
            "${MediaStore.Audio.Media.ARTIST} = ? COLLATE NOCASE"
        val selectionArgs = arrayOf(title.trim(), artist.trim())
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
                    performDownload(task)
                    mark(task.videoId, task.title, DownloadProgress.State.DONE, 100)
                    _progress.value = _progress.value - task.videoId
                    _events.tryEmit(DownloadEvent.Completed(task.videoId, task.spotifyTrackId))
                    notifyUpdate()
                    return@withContext
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt $attempt failed for ${task.videoId}", e)
                }
            }
            mark(task.videoId, task.title, DownloadProgress.State.FAILED, 0)
            _events.tryEmit(DownloadEvent.Failed(task.videoId, task.spotifyTrackId))
        } finally {
            _activeCount.value = (_activeCount.value - 1).coerceAtLeast(0)
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

            writeTags(tempFile, task)
            val uri = publish(tempFile, task)
            if (uri == null) {
                throw IOException("Publish failed for ${task.videoId}")
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun writeTags(file: File, task: DownloadTask) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                val properties = HashMap<String, Array<String>>().apply {
                    put("TITLE", arrayOf(task.title))
                    put("ARTIST", arrayOf(task.artist))
                    if (!task.album.isNullOrBlank()) put("ALBUM", arrayOf(task.album))
                }
                TagLib.savePropertyMap(fd.dup().detachFd(), properties)

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

    private fun publish(tempFile: File, task: DownloadTask): Uri? {
        val fileName = sanitize("${task.title} - ${task.artist}") + task.format.fileExtension
        val uri = if (hasQ()) {
            publishScoped(tempFile, task, fileName)
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

    private fun publishScoped(tempFile: File, task: DownloadTask, fileName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, task.format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, task.title)
            put(MediaStore.Audio.Media.ARTIST, task.artist)
            if (!task.album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, task.album)
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

    private fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private data class DownloadTask(
        val videoId: String,
        val spotifyTrackId: String?,
        val title: String,
        val artist: String,
        val album: String?,
        val thumbnailUrl: String?,
        val format: DownloadFormat
    )

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
