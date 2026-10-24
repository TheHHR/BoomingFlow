package com.mardous.booming.ui

import com.mardous.booming.online.data.spotify.SpotifyAlbum
import com.mardous.booming.online.data.spotify.SpotifyArtist
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.SpotifyTrack

interface ISpotifySearchCallback {
    fun spotifyTrackClick(track: SpotifyTrack, tracks: List<SpotifyTrack>)
    fun spotifyTrackPlay(track: SpotifyTrack, tracks: List<SpotifyTrack>)
    fun spotifyTrackPlayNext(track: SpotifyTrack)
    fun spotifyTrackEnqueue(track: SpotifyTrack)
    fun spotifyTrackDownload(track: SpotifyTrack)
    fun spotifyTrackAddToPlaylist(track: SpotifyTrack)
    fun spotifyTrackGoToAlbum(track: SpotifyTrack)
    fun spotifyTrackGoToArtist(track: SpotifyTrack)
    fun spotifyPlaylistClick(playlist: SpotifyPlaylist)
    fun spotifyArtistClick(artist: SpotifyArtist)
    fun spotifyAlbumClick(album: SpotifyAlbum)
}
