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
| 8 | **Lyrics speed (1.1.24)**: parallel provider race (all providers queried concurrently, early-exit on complete merge, 15s global cap, partial merge kept on timeout), two-stage lyrics display (local file/embedded/Room sources fetched in parallel and shown instantly; network upgrade replaces them in place — stored line-synced lyrics never wait on a word-synced fetch), negative cache (30-min TTL) for songs whose sweep found nothing, CancellationException rethrow so song skips cancel in-flight fetches | ✅ v1.1.24 |
| 9 | **10 new lyrics providers (1.1.25)**: Unison (rich-synced TTML + per-device EC P-256 keyId auth), BiniLyrics (syllable TTML), YouLyPlus (6-mirror TTML + syllable→eLRC), Kouzu (search → syllable→eLRC), SyncLRC (karaoke eLRC, 4-stage fallback + mirror), Spotify / YouTube Captions / NetEase (Paxsenix, NetEase prefers klyric word-by-word), KuGou (modern + legacy Base64 over https), SimpMusic (LRC/plain); shared `SyllableLrcConverter` + `PaxsenixClient`; all default-off, wired into the parallel race; editor picker picks them up automatically | ✅ v1.1.25 |
| 10 | **Lyrics quality ladder + bulletproof fetching (1.1.26)**: strict priority word-by-word > line-synced > plain, offline (file/embedded/stored) as last resort across sources; race stops instantly on word-by-word, line-synced opens a 3.5s grace window for word-by-word before settling, 20s cap; artist fallback sweep (raw track artist when album-artist query finds nothing — the editor query); negative cache records misses only when every provider genuinely finished (never on timeout); removed 6 dead/removed providers (Unison, Kouzu, KuGou, SimpMusic, Paxsenix Spotify, Paxsenix YouTube) — roster now LRCLib, BetterLyrics, Lyrically, BiniLyrics, YouLyPlus, SyncLRC, NetEase; "Lyrics by X" attribution now also shown for plain lyrics (provider threaded through the plain path) | ✅ v1.1.26 |
| 11 | **Provider quick-switch + stored-first (1.1.27)**: fullscreen lyrics FAB now taps open a popup dialog (editor-style AlertDialog + radio rows) listing all 7 providers including disabled ones — tap a provider to fetch from just that one ("Searching…" subtitle), lyrics switch in place and persist to Room (negative cache cleared); failed provider rows show "No lyrics found"; tap-and-hold the FAB opens the lyrics editor (previous tap behavior). Stored (Room) lyrics are now the top priority and final: if a row exists it wins over providers, sidecar files and embedded tags with no auto-upgrades — so a manual switch sticks. Priority order: Stored > provider race (WbW → line → plain) > sidecar file > embedded | ✅ v1.1.27 |
| 12 | **Theme batch (1.1.28)**: dark mode is always pure black (Pure Black option removed, `DARK`/night resolves to the Black style + night overlay, light/dark/system toggle fully functional again); header style option removed — compact always; accent-color flicker fixed (the fast follow-player extraction now uses the same 128px color accuracy as the now-playing cover extraction, so both pipelines select the same swatch instead of fighting); fullscreen lyrics gradient derives from the player's extracted scheme (cover extraction only as fallback); downloaded badges stay fresh (DownloadRegistry now exposes live `downloadedSpotifyIds`/`downloadedVideoIds` StateFlows from a prefs listener — playlist/artist/album/search/sheets collect them, and accent collectors rebind rows so badge colors follow); fullscreen lyrics FAB follows the player scheme when follow-player-color is on; Discover loading swapped to the Material3Expressive wavy indicator | ✅ v1.1.28 |
| 13 | **Pure Black option restored + artist separators + WbW upgrade + inactive-button fix (1.1.29)**: Pure Black switch back (default off, full upstream semantics, all locale strings restored from git history); Library settings → "Artist separators": user-defined separator tokens (JSON list pref, default = the historic built-in set , / ; feat ft with x) with preset chips and custom-token add — drives the main-artist extraction regex (`ArtistNameExt`, cached per token set) and SyncLRC's artist strip; stored lyrics that are not word-by-word now get ONE background upgrade sweep (provider race, WbW-only replacement) — "upgrade attempted" marker (in-memory, cleared on save/switch/delete, retried after timeout-truncated sweeps) so line-synced stored lyrics get replaced when a word-by-word version exists instead of being frozen by stored-first; search filter buttons with follow-player-color ON no longer render the disabled colors (the unchecked-state query was matching the selector's `state_enabled=false` entry — now queries enabled+checkable+unchecked) | ✅ v1.1.29 |
| 14 | **Artists-page splitting + exceptions + "Not artist" (1.1.30)**: the default (album-artists) artists tab now splits multi-artist names by the configured separators — each album is assigned to every resulting artist ("Eminem & King" albums appear under both Eminem and King), grouped by normalized split name so exact-tagged and combined-tagged albums merge; `Artist` gained `nameOverride` and `albumArtist(name)` detail lookup is now the same split-aware in-memory filter (exact-match SQL could never find split names); exceptions list (Library settings → Artist separators → Exceptions, masking semantics: `-` separator + "JAY-Z" exception → "JAY-Z" whole, "JAY-Z - Eminem" → "JAY-Z" + "Eminem"); `extractMainArtistName` rewritten on the splitter (parts except last, comma-joined); "Not artist" action in the artist row menu and the artists multi-selection menu (artists got their own selection menu resource) puts entries on an Ignored artists list — filtered out of the artists page, managed under Library settings → Ignored artists (uncheck to restore). Plain MediaStore-id artists view unchanged | ✅ v1.1.30 |
| 15 | **Plain artists view splitting (1.1.31)**: the "Show album artists = off" (MediaStore) artists mode now splits too — `splitIntoArtists` groups albums by split parts of the artist tag with the same multi-assignment + ignore-list + nameOverride behavior; split entries use stable synthetic negative ids (from the normalized name hash, avoiding the -1/-2 sentinels) and `artist(id)` resolves them by rebuilding the split artist in memory (same pattern as the Various-Artists case); "Not artist" and exceptions work in this mode too; debug builds log the artist-list construction counts | ✅ v1.1.31 |
| 16 | **Artist-split corrections (1.1.32)**: song-level assignment — each song now goes by its own tag (`song.albumArtistName` / `song.artistName`, with the album's derived tag only as fallback for untagged songs) instead of whole-album assignment, so solo tracks on shared albums land only on the artist that actually performs them; multi-separator names split completely (the old greedy full-match regex only ever cut at the LAST separator — now a find()-based left-to-right cut at every separator occurrence: "Eminem, king, ariana @telegramchannel" → 4 entries); the Ignored artists dialog now seeds a row per existing entry (previously empty on open, making items impossible to remove); detail resolvers (`albumArtist(name)`, synthetic-id `artist(id)`) use the same song-level construction so list/detail agree | ✅ v1.1.32 |
| 17 | **Spotify artist pics + page-cover accents + search fix (1.1.33)**: local artists get their picture from Spotify — ArtistImageFetcher gains a Spotify source ahead of Deezer (`searchArtists`, Jaro-Winkler ≥ 0.90 name match, session LruCache, works via the anonymous token without a connected account, gated by "Allow online artist images"); artist-detail and album-detail headers extract a palette from their own displayed art (PaletteProcessor) and color the play/shuffle/search buttons with it (always on, independent of follow-player-color); search fix — the Artists filter no longer leaks collaborators (post-filter by display name: searching "Eminem" no longer returns Rihanna/D12 split out of combined tags) | ✅ v1.1.33 |
| 18 | **Unified lyrics download + persisted Spotify pics + tag-editor Spotify fetch (1.1.34)**: the automated playback download now uses the exact editor download logic — enabled providers only (wifi/provider toggles respected, no dialogs), race stops at the first synced result (word-by-word or line, first arrival wins; plain never stops it), no preferWordSynced/grace-window/WbW-hunt, auto-saves to Room; word-synced upgrade sweeps and markers removed (the "Word-by-word lyrics" preference is rendering-only now); Plain/Synced dialog defaults to Synced; Spotify artist-image URLs persist in SpotifyPrefs (bounded JSON map keyed by normalized name, trimmed at 500) — pics survive restarts and load offline from the Coil disk cache; tag editors gain "Fetch from Spotify" (song editor: searchTracks pick list; album editor: searchAlbums) feeding the existing save-into-tags pipeline | ✅ v1.1.34 |
| 19 | **Deezer removed + tag editor raw tags + playlists reorder (1.1.35)**: data/remote/deezer deleted (service + 3 models), facade/repo functions, DI, api.deezer.com cache-interceptor conditions, proguard rule — "Download image" (Deezer first-hit) removed from the tag editor dialog, "Fetch from Spotify" now first; AudioCoverFetcher's online-cover branch (gated by "Download missing album covers", off by default) now uses Spotify searchAlbums→searchTracks; ArtistImageFetcher trimmed (no more Deezer fallback loop or repository param); tag editor reads name fields raw (`value()` — no forced "/" & ";" splitting), fixing the AC/DC→"AC; DC" prefill and save-back corruption (multi-frame tags show first frame); playlists page reordered: Liked Songs → Spotify shelf → Your playlists last | ✅ v1.1.35 |
| 20 | **Smooth transitions + WbW third type + adaptive loading accents (1.1.36)**: next-song transition smoothed — the cover pager only rebuilds when queue CONTENT changes (was: full adapter teardown per emission), playback-driven page changes suppress the redundant playSongAt round-trip, palettes are memoized per song (instant re-accent on replays), and the window-background swap animates instead of tearing the hierarchy mid-scroll; word-by-word is now a recognized third lyrics type — RawLyrics.Remote carries wordSynced alongside plain/synced (SyncLRC karaoke no longer collapsed), the editor chooser offers Plain/Synced/Word-by-word when a WbW variant was downloaded, and prepareToStore prefers WbW > synced > plain; adaptive-color completion — the wavy loading indicators on Discover and the Spotify playlist/artist/album pages follow the player accent, the bottom bar restores its original themed icon/text/indicator colors on fallback instead of stripping the active-tab pill, and with follow-color on the idle state (nothing playing) keeps the neutral gray no-cover look instead of reverting to Material You | ✅ v1.1.36 |
| 21 | **Auto cover-fetch fix (1.1.37)**: the automatic Spotify cover lookup in AudioCoverFetcher now mirrors the tag editor's proven query format (title/album-name first, artist last — was artist-first, which underperformed) and iterates up to 5 search candidates, taking the first that actually has art instead of a blind first hit; lookup failures are logged in every build and the query/result in debug builds; "Download missing album covers" now defaults to ON (was off — installs that never touched the toggle get auto covers without a manual flip) | ✅ v1.1.37 |
| 22 | **Offline covers + accent fixes + cleanup (1.1.38)**: resolved online-cover URLs persist in SpotifyPrefs (bounded map keyed by normalized artist|album, 1000 entries) and the fetcher consults the session cache → persisted map → fresh search, so covers survive restarts and load offline from the Coil disk cache; the invisible "Configure" buttons in Library → Exclusions are fixed (toggle-accent color lists now only target checkable buttons and carry an empty default state spec, so no drawable state can resolve to transparent); the follow-color-off nav bar uses onSecondaryContainer for the active icon (was the same secondaryContainer as the pill — icon invisible on it) and colorOnSurface for the active label; the neutral idle accent now applies everywhere follow-player-color does — idle/coverless/monochrome all feed the neutral gray accent into the whole Compose scheme and every per-view consumer instead of reverting to Material You, and the mini-player uses scheme roles instead of the raw accent; the search keyboard FAB label follows the accent background contrast and the search adapter rebinds on accent change (badges no longer stale); the dead "Preferred image size" setting is removed (it never affected any fetch); orphaned peek2 controls layout deleted | ✅ v1.1.38 |
| 23 | **Word-by-word lyrics overhaul (1.1.39)**: the provider race treats a line-synced arrival as provisional and keeps racing up to 4s for a word-by-word result (WbW always wins and stops the race instantly); word-level content delivered in the synced slot (BiniLyrics/YouLyPlus/Lyrically TTML + eLRC, Netease klyric) is auto-routed to the wordSynced slot at merge time so the WbW>line>plain ladder applies to every provider; songs with stored line-synced lyrics get ONE background WbW upgrade sweep per session (blocked after manual edits/provider picks, stored row replaced only when a real WbW version is found); Netease klyric is actually parsed now (dedicated [ms](dur)word line parser + sync classification — was rendered as plain junk text); the TTML parser skips un-handleable tags with their subtree instead of truncating every line after the first odd tag (p-under-body and nested divs now supported, deepest-first close ordering); TTML fractional seconds scale by digit count (.5 = 500ms, was 5ms); eLRC explicit word-end markers are honored (empty-text children contribute their timestamps instead of being dropped); synthesized last-word ends are capped so the karaoke fill no longer sweeps through instrumental gaps; LRC timestamps round instead of truncate; SyllableLrcConverter rounds and never emits 00:00.00 word stamps; karaoke word fill advances at display frame rate (frame-smoothed position drives updatePosition) and seeks push position while playing too; zero-duration line guards, crash guards on blank-syllable lines, negative-seek guard, unified inclusive word-end boundary, WbW provider attribution fixed, editor saves keep the provider | ✅ v1.1.39 |

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

- **CI builds only** (no local APKs, no local compiles — CI is the gate). Two workflows in `.github/workflows/`:
  - **Android CI** — triggers on every push (branch ≠ `stable`, non-doc paths) + `workflow_dispatch`. Debug APK artifact for smoke tests.
  - **Build & Release BoomingFlow APK** — triggers automatically on pushing a `v*` tag (and `workflow_dispatch`). Signs (`CN=BoomingFlow`) and publishes `BoomingFlow-<version>-github-arm64-v8a.apk` to the GitHub release with auto-generated notes.
- **Release procedure:** bump `versionMajor/Minor/Patch` + matching `versionCode` → push → tag `v1.x.y` → push the tag → the release workflow builds and publishes the APK automatically → verify (`aapt2 dump badging` = expected id/version, `apksigner verify` = `CN=BoomingFlow`).

## Known limitations (v1)

- Online queue items are not persisted across process death (same as external-open preview files).
- ~~In-app cover for online tracks falls back to Deezer/placeholder~~ **Fixed in 1.0.5** — `AudioCoverFetcher` now delegates http(s) cover URIs (the YT thumbnail stored on `UnindexedSong.uri`) to Coil's network fetcher; downloaded tracks read embedded art via TagLib.
- Spotify home parser keeps **playlist** items only (upstream `filterIsInstance<Playlist>()`); album/artist cards need `parseHomeItem` branches.
- Liked Songs loads pages of 50; the row subtitle uses `paging.total`.
- No stream disk cache (no `SimpleCache`); no queue-persisted re-resolution after restart.
- Spotify auth = cookie (`sp_dc`) → web-player TOTP token flow (not official OAuth).
- Downloads (1.1.0): Opus format saves the raw WebM/Opus byte stream with `.opus` extension (no remux — no FFmpeg in deps); tags are best-effort on non-MP4 containers. Sequential worker, no pause/resume UI, no WorkManager (long batches may be killed by the OS if the app is backgrounded). Downloaded-state badges / "downloaded songs" manager are v2.

## Hotfix log

- **1.1.24 (lyrics latency overhaul)**: lyrics took seconds to appear because the
  pipeline was fully sequential and single-shot — `fileLyrics` → `embeddedLyrics` →
  `storedLyrics(network)` in order, one provider after another (Lyrically alone chains
  up to 6 requests), one final UI emission after everything, and with
  "Word-by-word lyrics" enabled even stored line-synced lyrics were held back until
  the network upgrade attempt finished. Now: all providers race in parallel
  (`LyricsDownloadService.raceProviders`, `select`-based early-exit + straggler
  cancel + 15s global cap, partial merge kept on timeout); the three local sources
  are fetched in parallel and shown immediately; the network stage runs in the
  background and replaces lyrics in place only when it yields something better
  (word-synced > line-synced > plain); a completed sweep that found nothing is
  negative-cached for 30 min (`RealLyricsRepository`, bypassed by the editor's
  manual download); `CancellationException` is rethrown through the repository so
  skipping songs cancels the in-flight race instead of swallowing it.
  Behavior notes: local word-synced lyrics (file/embedded/Room) now skip the
  network stage entirely; classic (non-word-synced) mode still never downloads
  when a local synced result exists.

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
