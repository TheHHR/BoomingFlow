# BoomingFlow

A fork of [Booming Music](https://github.com/ChristiansM/BoomingMusic) reworked into **BoomingFlow**: a local-first Android music player with a **Spotify home/discovery front end** and a **YouTube Music streaming back end**. Spotify provides the browse surface (home feed, playlists, liked songs); every play is resolved to a YouTube Music stream and played through the stock Media3 pipeline.

- **Package:** `com.thehhr.boomingflow` (debug: `com.thehhr.boomingflow.debug` — coexists with stock Booming Music)
- **Code namespace (unchanged):** `com.mardous.booming` — R/BuildConfig/manifest stay upstream
- **Versioning:** `versionCode = MAJOR×1,000,000 + MINOR×100,000 + PATCH×10,000 + trackOffset×100 + build` (Stable track offset = 3), so `1.0.1` → `1010300`. Literal `versionCode` in `app/build.gradle.kts` must match `currentVersion.code` (build enforces it).
- **Signer:** `CN=BoomingFlow` keystore (`boomingflow` release config).
- **Repo / releases:** https://github.com/TheHHR/BoomingFlow

## Feature phases (status)

| Phase | Feature | Status |
|---|---|---|
| 1 | Identity: applicationId `com.thehhr.boomingflow`, version 1.0.0, shortcut targetPackage fix | ✅ v1.0.0 |
| 2 | YouTube streaming playback (`innertube://` + ResolvingDataSource) + Settings→Streaming→"Play test stream" | ✅ |
| 3 | **Discover tab**: Spotify connect card, `home()` shelves, playlist detail (Play all / Shuffle / Play next / Add to queue) | ✅ |
| 4 | **Playlists 3-sections**: Your playlists / Liked Songs / Spotify shelf; Liked Songs detail screen; 1.0.1 | ✅ v1.0.1 |
| 5 | Device smoke test + this document | ✅ v1.0.2 |
| 6 | **Fixes (1.0.5)**: online-song covers in player/mini-player/queue (`AudioCoverFetcher` http(s) branch), playlist detail big title + back-arrow listener, Discover anonymous-feed race (authState wiring + eager `StreamingAuthViewModel` bootstrap in `MainActivity` + `isAuthenticated()` gate), Discover toolbar search button, official Material explore icon | ✅ |
| 7 | **Downloads (1.1.0)**: batch download playlists / Liked Songs (M4A default / Opus picker, track-pick sheet), per-track Download popup action, TagLib tags + embedded art, MediaStore publish to `Music/BoomingFlow`, playback prefers downloaded local copy via registry lookup in `ResolvingDataSource` | ✅ |

## How playback works

```
SpotifyTrack ── resolveForPlayback() ──► YouTubeMusic.search(query, SONGS) ──► OnlineSong (videoId)
      (cache: com.mardous.booming.online.spotify.spotifyResolutionCache)

OnlineQueueItem = MediaItem(uri=innertube://<videoId>, mediaId="yt:<videoId>", metadata)
                + UnindexedSong registry entry (key = mediaId)
      │
      ▼
PlayerViewModel.playOnlineQueue / addOnlineQueue / playOnlineQueueNext
      │  registers songs via Repository.registerOnlineSong (before buildPlayQueue eviction!)
      ▼
MediaController ──► PlaybackService (MediaLibraryService)
      │
      ▼
DefaultMediaSourceFactory(ResolvingDataSource(DefaultDataSource(OkHttpDataSource)))
      │  open(innertube://…) → runBlocking { YouTubeMusic.getStreamUrl(videoId) } → https URL
      ▼
OkHttp interceptor: StreamClientUtils.applyRequestProfile (UA by ?c= param)
                    + one-shot 403 retry with ANDROID_VR UA, no Origin/Referer → googlevideo CDN
```

Key mechanics (all in `app/src/main/java/com/mardous/booming/`):

- **`online/playback/OnlinePlayback.kt`** — data-source factory, `buildOnlineQueueItem`, `onlineMediaId`/`onlineContentUri`, `RESOLVED_FROM_FILE` extras.
- **Registry** — `SongRepository.registerOnlineSong(key, song)` → `ConcurrentHashMap`; `songsByMediaItems` falls back to it, so queue eviction (`PlaybackService.buildPlayQueue`), the player UI (`QueueStateHolder.currentSong`), and restore behave correctly. MediaIds are namespaced `yt:<videoId>` (never numeric → no MediaStore collisions, never a reserved prefix → `LibraryProvider` passes URI-carrying items untouched).
- **Errors** — `PlaybackService.onPlayerError`: for `innertube://` items, up to 2 re-resolve attempts (clears `YouTubeMusic` + `NewPipeStreamExtractor` video caches, keeps position) before the generic skip logic.
- **ReplayGain / scrobble guards** — `isOnlineContentUri()` gates RG tag reads; `RESOLVED_FROM_FILE=true` keeps online items out of history/play-count/scrobbling and out of queue persistence (online queue items intentionally don't survive process death).
- **URL expiry** — resolution happens lazily at every `DataSource.open()`, and the `MediaItem` URI stays `innertube://`, so a queue can sit around indefinitely and still play fresh.

## UI map

- **Discover tab** (`ui/screen/discover/`) — `CategoryInfo.Discover` (Settings → Appearance → Library categories controls visibility; default-visible slot #2), nav id `nav_discover`.
  - `DiscoverFragment` + `DiscoverViewModel` → `SpotifyClient.home()` → greeting + horizontal shelves (`HorizontalListAdapter` + `SpotifyCardAdapter`), connect card opens `StreamingLoginDialogFragment(Spotify, WebView)`.
  - `SpotifyPlaylistFragment` + `SpotifyPlaylistViewModel` (`nav_spotify_playlist`, **nullable** `playlistId` — omitted ⇒ Liked Songs source) — paged `playlistTracks()`/`likedSongs()`, Play all/Shuffle resolve the first track then stream the next 30 into the queue in the background.
- **Playlists tab** (`ui/screen/library/playlists/`) — widened to `AbsRecyclerViewCustomGridSizeFragment<RecyclerView.Adapter<*>, …>` hosting a `ConcatAdapter`: local section header + `PlaylistAdapter` (grid cells, full-span lookup for everything else) + Liked Songs header/row (`LikedSongsRowAdapter`) + Spotify shelf. New `PlaylistsSectionsViewModel` loads `likedSongs(total)` + `myPlaylists()` paging; sections self-hide when empty or Spotify disconnected.
- **Bridge** (`online/spotify/SpotifyPlayback.kt`) — `resolveForPlayback()` / `resolveBatchForPlayback(n)` → `OnlineQueueItem`.

## Build & release

- **CI builds only** (no local APKs). Two workflows in `.github/workflows/`:
  - **Android CI** (`workflow_dispatch` on `master`) — debug APK artifact for smoke tests. Pushes do **not** auto-trigger; dispatch manually.
  - **Build & Release BoomingFlow APK** — dispatched with `--ref v1.0.x` after tagging. Publishes `BoomingFlow-<version>-github-arm64-v8a.apk` to the GitHub release.
- **Release procedure:** bump `versionMajor/Minor/Patch` + matching `versionCode` → push → tag `v1.0.x` → `gh workflow run "Build & Release BoomingFlow APK" --ref v1.0.x` → verify (`aapt2 dump badging` = expected id/version, `apksigner verify` = `CN=BoomingFlow`).
- Before pushing, run locally: `gradlew compileGithubDebugKotlin processGithubDebugResources` — Kotlin compilation does **not** catch invalid theme attributes; the resource link does (CI failed once on `?shapeAppearanceCornerRound`).

## Known limitations (v1)

- Online queue items are not persisted across process death (same as external-open preview files).
- ~~In-app cover for online tracks falls back to Deezer/placeholder~~ **Fixed in 1.0.5** — `AudioCoverFetcher` now delegates http(s) cover URIs (the YT thumbnail stored on `UnindexedSong.uri`) to Coil's network fetcher; downloaded tracks read embedded art via TagLib.
- Spotify home parser keeps **playlist** items only (upstream `filterIsInstance<Playlist>()`); album/artist cards need `parseHomeItem` branches.
- Liked Songs loads pages of 50; the row subtitle uses `paging.total`.
- No stream disk cache (no `SimpleCache`); no queue-persisted re-resolution after restart.
- Spotify auth = cookie (`sp_dc`) → web-player TOTP token flow (not official OAuth).
- Downloads (1.1.0): Opus format saves the raw WebM/Opus byte stream with `.opus` extension (no remux — no FFmpeg in deps); tags are best-effort on non-MP4 containers. Sequential worker, no pause/resume UI, no WorkManager (long batches may be killed by the OS if the app is backgrounded). Downloaded-state badges / "downloaded songs" manager are v2.

## Hotfix log

- **1.0.2** (device smoke test, SM-G990E / Android 16): v1.0.1 crashed with
  `UnknownFormatConversionException` in `refreshSectionVisibility` — three format strings
  (`liked_songs_subtitle`, `discover_playlist_subtitle`, `no_youtube_match_found`) had been
  written via shell expandable here-strings, which swallowed `$d`/`$s` (`%1$d` → `%1$ `).
  **Lesson:** never author `%1$s`-style Android format strings through PowerShell `@"..."@`
  here-strings; use the edit tool or single-quoted strings.
- **1.0.3 → 1.0.4**: opening **Liked Songs** crashed with
  `IllegalArgumentException: Required argument "playlistId" is missing` from the generated
  `SpotifyPlaylistFragmentArgs.fromBundle`. Marking the arg `app:nullable="true"` alone was
  not enough, and adding `app:defaultValue="@null"` (1.0.3) still left it *required* in the
  safe-args codegen. 1.0.4 abandons the delegate entirely: the arg is a plain required
  string, Liked Songs navigates with the sentinel id `"liked"`,
  `SpotifyPlaylistFragment` reads `arguments?.getString("playlistId")` manually (null ⇒
  Liked Songs), and the VM maps `null`/`"liked"` to the `likedSongs()` loader. This also
  survives restoring back-stack entries saved by older crashing builds.
  **Lesson:** in this project's safe-args setup, only explicit `defaultValue` entries on
  *typed* args count — nullable `string` args remain required; prefer manual
  `getString()` reads when an argument must be optional.
- Verified on-device (1.0.4): cold start restores the previously-crashing Liked Songs
  entry and renders `68 tracks • Spotify` + track list, zero crash entries; tapping a liked
  track plays (`state=PLAYING(3)`, 84 s played / 188 s buffered).

## Manual smoke checklist

Executed on-device 2026-10-22 (Samsung SM-G990E, Android 16, release build 1.0.2):

1. ✅ Install/update in place (same id + `CN=BoomingFlow` signer).
2. ✅ Settings → Streaming → Play test stream → audio plays (streaming pipeline green).
3. ✅ Discover tab → greeting + Spotify home shelves ("Popular radio", "Featured Charts",
   "Discover Weekly") render from the connected account; playlist detail opens
   ("Spotify Playlist • by TheHHR • 67 tracks", Play/Shuffle, numbered track rows).
4. ✅ Playlists tab → three sections: "Your playlists" rows, "Liked Songs" row with
   `68 tracks • Spotify`, "Spotify" shelf ("TheHHR-PLAYLIST", "T Playlist").
5. ✅ Tap Play on a Spotify playlist → MediaSession `state=PLAYING(3)`, position advancing,
   103 s buffered (YouTube CDN) — the full resolve→innertube→OkHttp→ExoPlayer chain.
6. ✅ No entries in the crash log buffer across all of the above.

Reference run (rerun anytime):

1. Install `BoomingFlow-<v>-github-arm64-v8a.apk` → app id `com.thehhr.boomingflow`, coexists with Booming Music.
2. **Settings → Streaming → Play test stream** → Rick Astley plays (proves resolver + headers + registry without Spotify).
3. **Discover tab** → Connect Spotify (WebView) → home shelves load → open a playlist → tap a track → plays; Play all / Shuffle / overflow (Play next, Add to queue) work; back/refresh work.
4. **Playlists tab** → three sections; Liked Songs opens detail (title "Liked Songs"); Spotify shelf cards open detail; sort/grid-size/new/import menus still work; sections hide with Spotify disconnected.
5. Kill the app while an online track plays → restart → local queue restored, online items dropped (expected).
6. Airplane mode mid-stream → error → skip to next (after 2 re-resolve attempts fail).

Additions for 1.0.5 / 1.1.0 (pending device run):

7. **Player covers**: open Discover → play an online track → full player + mini player show the real thumbnail (was placeholder); queue rows too.
8. **Playlist detail title**: open a Spotify playlist → big header shows the playlist NAME (not just "Spotify Playlist • by …"); back arrow still navigates up after title loads. Liked Songs header shows "Liked Songs".
9. **Discover feed**: cold-start app straight to the Discover tab (never visiting Playlists) → personalized feed (greeting + account shelves), not the generic/anonymous page. Toolbar search icon (left) → opens Search. Log out → Discover shows the connect card.
10. **Icon**: Discover bottom-nav icon is a proper compass (correct needle, continuous ring, filled state when selected).
11. **Downloads**: playlist detail → toolbar Download → sheet with track checkboxes + M4A/Opus toggle → Download → files land in `Music/BoomingFlow` (visible in library Songs after scan), progress notification counts down. Per-track popup → Download. After a download finishes, tapping that track in the playlist plays the LOCAL file (logcat: ResolvingDataSource picks `content://media…`, no googlevideo request).
