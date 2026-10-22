package com.mardous.booming.online.data.innertube

import com.google.gson.annotations.SerializedName

/**
 * Data models for InnerTube playlist management payloads.
 */
data class EditPlaylistBody(
    val playlistId: String,
    val actions: List<PlaylistAction>
)

sealed class PlaylistAction {
    data class AddVideo(
        @SerializedName("action") val action: String = "ACTION_ADD_VIDEO",
        @SerializedName("addedVideoId") val addedVideoId: String
    ) : PlaylistAction()

    data class AddPlaylist(
        @SerializedName("action") val action: String = "ACTION_ADD_PLAYLIST",
        @SerializedName("addedFullListId") val addedFullListId: String
    ) : PlaylistAction()

    data class RemoveVideo(
        @SerializedName("action") val action: String = "ACTION_REMOVE_VIDEO",
        @SerializedName("setVideoId") val setVideoId: String,
        @SerializedName("removedVideoId") val removedVideoId: String
    ) : PlaylistAction()

    data class MoveVideo(
        @SerializedName("action") val action: String = "ACTION_MOVE_VIDEO_BEFORE",
        @SerializedName("setVideoId") val setVideoId: String,
        @SerializedName("movedSetVideoIdSuccessor") val movedSetVideoIdSuccessor: String? = null
    ) : PlaylistAction()

    data class RenamePlaylist(
        @SerializedName("action") val action: String = "ACTION_SET_PLAYLIST_NAME",
        @SerializedName("playlistName") val playlistName: String
    ) : PlaylistAction()
}

data class CreatePlaylistBody(
    val title: String,
    val description: String = "",
    val privacyStatus: String = "PRIVATE",
    val videoIds: List<String>? = null
)
