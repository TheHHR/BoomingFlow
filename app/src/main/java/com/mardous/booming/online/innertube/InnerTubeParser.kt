package com.mardous.booming.online.data.innertube

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parses InnerTube API JSON responses into our clean data models.
 * Handles the deeply nested, inconsistent response structures from YouTube Music.
 */
object InnerTubeParser {

    private const val TAG = "InnerTubeParser"

    // ========== Search & Explore ==========

    fun parseSectionPage(response: JsonObject): HomeSection? {
        // 1. Try resolving header title from multiple known YouTube Music header schemas
        val headerTitle = response.path("header.musicHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("header.musicResponsiveHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("header.musicDetailHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("header.musicVisualHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("header.musicEditablePlaylistDetailHeaderRenderer.header.musicResponsiveHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicResponsiveHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicResponsiveHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.header.musicHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.header.musicHeaderRenderer.title.runs.0.text")?.asString
            ?: response.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.title")?.asString
            ?: response.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.title")?.asString

        // 2. Collect section list contents across all possible root locations
        val allSectionArrays = mutableListOf<JsonArray>()

        // Single column browse tabs
        response.path("contents.singleColumnBrowseResultsRenderer.tabs")?.asJsonArray?.forEach { tab ->
            tab.asJsonObject.path("tabRenderer.content.sectionListRenderer.contents")?.asJsonArray?.let {
                allSectionArrays.add(it)
            }
        }
        // Two column browse tabs and secondaryContents
        response.path("contents.twoColumnBrowseResultsRenderer.tabs")?.asJsonArray?.forEach { tab ->
            tab.asJsonObject.path("tabRenderer.content.sectionListRenderer.contents")?.asJsonArray?.let {
                allSectionArrays.add(it)
            }
        }
        response.path("contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents")?.asJsonArray?.let {
            allSectionArrays.add(it)
        }
        // Direct sectionListRenderer or continuation contents
        response.path("contents.sectionListRenderer.contents")?.asJsonArray?.let {
            allSectionArrays.add(it)
        }
        response.path("continuationContents.sectionListContinuation.contents")?.asJsonArray?.let {
            allSectionArrays.add(it)
        }
        response.path("continuationContents.musicPlaylistShelfContinuation.contents")?.asJsonArray?.let {
            allSectionArrays.add(it)
        }
        response.path("continuationContents.musicShelfContinuation.contents")?.asJsonArray?.let {
            allSectionArrays.add(it)
        }

        var inferredTitle: String? = headerTitle
        val items = mutableListOf<SearchItem>()

        fun processItemElement(itemElement: JsonElement) {
            if (!itemElement.isJsonObject) return
            val obj = itemElement.asJsonObject

            val twoRowRenderer = obj.getAsJsonObject("musicTwoRowItemRenderer")
            val listRenderer = obj.getAsJsonObject("musicResponsiveListItemRenderer")
            val multiRowRenderer = obj.getAsJsonObject("musicMultiRowListItemRenderer")
            val stationRenderer = obj.getAsJsonObject("compactStationRenderer")
                ?: obj.getAsJsonObject("musicNavigationButtonRenderer")

            when {
                twoRowRenderer != null -> {
                    parseTwoRowItem(twoRowRenderer)?.let { items.add(it) }
                }
                listRenderer != null -> {
                    parseSearchItem(obj)?.let { items.add(it) }
                }
                multiRowRenderer != null -> {
                    parseMultiRowListItem(multiRowRenderer)?.let { items.add(it) }
                }
                stationRenderer != null -> {
                    val titleStr = stationRenderer.path("title.runs.0.text")?.asString
                        ?: stationRenderer.path("buttonText.runs.0.text")?.asString
                    val browseId = stationRenderer.path("clickCommand.browseEndpoint.browseId")?.asString
                        ?: stationRenderer.path("navigationEndpoint.browseEndpoint.browseId")?.asString
                    val thumb = stationRenderer.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    if (!titleStr.isNullOrEmpty() && !browseId.isNullOrEmpty()) {
                        items.add(SearchItem.Playlist(OnlinePlaylist(
                            playlistId = browseId.removePrefix("VL"),
                            title = titleStr,
                            thumbnailUrl = getHighResThumbnailUrl(thumb)
                        )))
                    }
                }
                else -> {
                    // Try parsing as generic list/search item
                    parseSearchItem(obj)?.let { items.add(it) }
                }
            }
        }

        fun processSectionObject(sectionObj: JsonObject) {
            val gridRenderer = sectionObj.getAsJsonObject("gridRenderer")
            val musicShelfRenderer = sectionObj.getAsJsonObject("musicShelfRenderer")
            val musicPlaylistShelfRenderer = sectionObj.getAsJsonObject("musicPlaylistShelfRenderer")
            val musicCarouselShelfRenderer = sectionObj.getAsJsonObject("musicCarouselShelfRenderer")
            val itemSectionRenderer = sectionObj.getAsJsonObject("itemSectionRenderer")
            val cardShelf = sectionObj.getAsJsonObject("musicCardShelfRenderer")

            if (inferredTitle == null) {
                inferredTitle = gridRenderer?.path("header.gridHeaderRenderer.title.runs.0.text")?.asString
                    ?: musicShelfRenderer?.path("title.runs.0.text")?.asString
                    ?: musicCarouselShelfRenderer?.path("header.musicCarouselShelfBasicHeaderRenderer.title.runs.0.text")?.asString
            }

            gridRenderer?.getAsJsonArray("items")?.forEach { processItemElement(it) }
            musicShelfRenderer?.getAsJsonArray("contents")?.forEach { processItemElement(it) }
            musicPlaylistShelfRenderer?.getAsJsonArray("contents")?.forEach { processItemElement(it) }
            musicCarouselShelfRenderer?.getAsJsonArray("contents")?.forEach { processItemElement(it) }

            cardShelf?.let {
                val (topResult, cardItems) = parseCardShelf(it)
                topResult?.let { tr -> items.add(tr) }
                items.addAll(cardItems)
            }

            itemSectionRenderer?.getAsJsonArray("contents")?.forEach { inner ->
                if (inner.isJsonObject) {
                    processSectionObject(inner.asJsonObject)
                }
            }

            // Direct item check
            processItemElement(sectionObj)
        }

        allSectionArrays.forEach { sectionArray ->
            sectionArray.forEach { sectionElement ->
                if (sectionElement.isJsonObject) {
                    processSectionObject(sectionElement.asJsonObject)
                }
            }
        }

        if (items.isEmpty() && inferredTitle == null) return null

        return HomeSection(
            title = inferredTitle ?: "Section",
            items = items
        )
    }

    fun parseSearchResponse(json: JsonObject): SearchResult {
        val items = mutableListOf<SearchItem>()
        var continuation: String? = null

        try {
            // Initial search response
            val tabs = json.path("contents.tabbedSearchResultsRenderer.tabs")?.asJsonArray
            val tab = tabs?.firstOrNull()?.asJsonObject
            var contents = tab?.path("tabRenderer.content.sectionListRenderer.contents")?.asJsonArray
            if (contents == null) {
                contents = json.path("contents.sectionListRenderer.contents")?.asJsonArray
            }

            contents?.forEach { section ->
                val obj = section.asJsonObject
                val cardShelf = obj.getAsJsonObject("musicCardShelfRenderer")
                val shelf = obj.getAsJsonObject("musicShelfRenderer")
                    ?: obj.getAsJsonObject("itemSectionRenderer")
                    ?: obj.getAsJsonObject("gridRenderer")

                if (cardShelf != null) {
                    val (topResult, cardItems) = parseCardShelf(cardShelf)
                    topResult?.let { items.add(it) }
                    items.addAll(cardItems)
                } else if (shelf != null) {
                    val shelfTitle = shelf.path("title.runs.0.text")?.asString
                    if (!shelfTitle.isNullOrBlank()) {
                        items.add(SearchItem.Header(shelfTitle))
                    }
                    val shelfContents = shelf.getAsJsonArray("contents") ?: shelf.getAsJsonArray("items")
                    shelfContents?.forEach { item ->
                        val itemObj = item.asJsonObject
                        var parsed = parseSearchItem(itemObj)
                        if (parsed == null) {
                            val twoRowRenderer = itemObj.getAsJsonObject("musicTwoRowItemRenderer")
                            if (twoRowRenderer != null) {
                                parsed = parseTwoRowItem(twoRowRenderer)
                            }
                        }
                        parsed?.let { items.add(it) }
                    }
                    if (continuation == null) {
                        continuation = shelf.path("continuations.0.nextContinuationData.continuation")?.asString
                    }
                }
            }

            // Continuation response
            if (items.isEmpty()) {
                var contContents = json.path("continuationContents.musicShelfContinuation.contents")?.asJsonArray
                if (contContents == null) {
                    contContents = json.path("continuationContents.itemSectionContinuation.contents")?.asJsonArray
                }
                contContents?.forEach { item ->
                    val itemObj = item.asJsonObject
                    var parsed = parseSearchItem(itemObj)
                    if (parsed == null) {
                        val twoRowRenderer = itemObj.getAsJsonObject("musicTwoRowItemRenderer")
                        if (twoRowRenderer != null) {
                            parsed = parseTwoRowItem(twoRowRenderer)
                        }
                    }
                    parsed?.let { items.add(it) }
                }
            }

            if (continuation == null) {
                continuation = json.path("contents.tabbedSearchResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.continuations.0.nextContinuationData.continuation")?.asString
                    ?: json.path("contents.sectionListRenderer.continuations.0.nextContinuationData.continuation")?.asString
                    ?: json.path("continuationContents.musicShelfContinuation.continuations.0.nextContinuationData.continuation")?.asString
                    ?: json.path("continuationContents.itemSectionContinuation.continuations.0.nextContinuationData.continuation")?.asString
                    ?: json.path("continuationContents.sectionListContinuation.continuations.0.nextContinuationData.continuation")?.asString
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search response", e)
        }

        return SearchResult(items, continuation)
    }

    private fun parseCardShelf(cardShelf: JsonObject): Pair<SearchItem.TopResult?, List<SearchItem>> {
        val items = mutableListOf<SearchItem>()
        var topResult: SearchItem.TopResult? = null

        try {
            val title = cardShelf.path("title.runs.0.text")?.asString ?: ""
            val subtitleRuns = cardShelf.path("subtitle.runs")?.asJsonArray
            val subtitle = subtitleRuns?.joinToString("") { run ->
                run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
            } ?: ""

            val firstSub = subtitle.split(" • ").firstOrNull()?.trim() ?: ""

            var parsedArtistName = ""
            var parsedArtistId: String? = null
            subtitleRuns?.forEach { run ->
                val runObj = run.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val text = runObj.get("text")?.asString ?: ""
                val endpoint = runObj.path("navigationEndpoint.browseEndpoint")?.asJsonObject
                if (endpoint != null) {
                    val pt = endpoint.path("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType")?.asString
                    if (pt == "MUSIC_PAGE_TYPE_ARTIST" || pt == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                        if (parsedArtistId == null) {
                            parsedArtistId = endpoint.get("browseId")?.asString
                            parsedArtistName = text
                        }
                    }
                }
            }

            val thumbnail = cardShelf.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString

            val titleBrowse = cardShelf.path("title.runs.0.navigationEndpoint.browseEndpoint")?.asJsonObject
            val titleWatch = cardShelf.path("title.runs.0.navigationEndpoint.watchEndpoint")?.asJsonObject
            val playNav = cardShelf.path("buttons.0.musicPlayButtonRenderer.playNavigationEndpoint")?.asJsonObject
                ?: cardShelf.path("buttons.0.buttonRenderer.command")?.asJsonObject
            val thumbOverlayNav = cardShelf.path("thumbnail.musicThumbnailRenderer.thumbnailOverlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint")?.asJsonObject
            val onTapBrowse = cardShelf.path("onTap.navigationEndpoint.browseEndpoint")?.asJsonObject
                ?: cardShelf.path("onTap.browseEndpoint")?.asJsonObject
            val onTapWatch = cardShelf.path("onTap.navigationEndpoint.watchEndpoint")?.asJsonObject
                ?: cardShelf.path("onTap.watchEndpoint")?.asJsonObject

            val browseEndpoint = titleBrowse ?: onTapBrowse ?: playNav?.getAsJsonObject("browseEndpoint")
            val browseId = browseEndpoint?.get("browseId")?.asString ?: ""
            val pageType = browseEndpoint?.path("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType")?.asString ?: ""

            val watchEndpoint = titleWatch ?: onTapWatch ?: playNav?.getAsJsonObject("watchEndpoint")
                ?: thumbOverlayNav?.getAsJsonObject("watchEndpoint")
            var extractedVideoId = watchEndpoint?.get("videoId")?.asString

            // Also check first child in contents if top-level videoId was not found
            val childContents = cardShelf.getAsJsonArray("contents")
            if (extractedVideoId.isNullOrEmpty() && childContents != null && childContents.size() > 0) {
                val firstChildRenderer = childContents.get(0)?.asJsonObject?.getAsJsonObject("musicResponsiveListItemRenderer")
                val childWatchEndpoint = firstChildRenderer?.path("overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint.watchEndpoint")?.asJsonObject
                    ?: firstChildRenderer?.path("navigationEndpoint.watchEndpoint")?.asJsonObject
                    ?: firstChildRenderer?.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.watchEndpoint")?.asJsonObject
                extractedVideoId = childWatchEndpoint?.get("videoId")?.asString
            }

            var onlineSong: OnlineSong? = null
            var onlineAlbum: OnlineAlbum? = null
            var onlineArtist: OnlineArtist? = null
            var onlinePlaylist: OnlinePlaylist? = null
            var resolvedItemType = "Song"

            // Comprehensive Entity Type Classification
            val isArtist = pageType == "MUSIC_PAGE_TYPE_ARTIST" ||
                    pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" ||
                    browseId.startsWith("UC") ||
                    browseId.startsWith("FEmusic_artist") ||
                    firstSub.equals("Artist", ignoreCase = true) ||
                    subtitle.contains("Artist", ignoreCase = true) ||
                    subtitle.contains("subscriber", ignoreCase = true)

            val isAlbum = pageType == "MUSIC_PAGE_TYPE_ALBUM" ||
                    browseId.startsWith("MPRE") ||
                    firstSub.equals("Album", ignoreCase = true) ||
                    firstSub.equals("EP", ignoreCase = true) ||
                    firstSub.equals("Single", ignoreCase = true) ||
                    subtitle.contains("Album", ignoreCase = true) ||
                    subtitle.contains("Single", ignoreCase = true) ||
                    subtitle.contains("EP", ignoreCase = true)

            val isPlaylist = pageType == "MUSIC_PAGE_TYPE_PLAYLIST" ||
                    pageType == "MUSIC_PAGE_TYPE_RADIO" ||
                    browseId.startsWith("VL") ||
                    browseId.startsWith("PL") ||
                    browseId.startsWith("RD") ||
                    firstSub.equals("Playlist", ignoreCase = true) ||
                    firstSub.equals("Mix", ignoreCase = true) ||
                    firstSub.equals("Station", ignoreCase = true) ||
                    subtitle.contains("Playlist", ignoreCase = true) ||
                    subtitle.contains("Mix", ignoreCase = true) ||
                    subtitle.contains("Station", ignoreCase = true)

            val isVideo = firstSub.equals("Video", ignoreCase = true) || subtitle.contains("Video", ignoreCase = true)

            when {
                isArtist && browseId.isNotEmpty() -> {
                    val subscriberInfo = subtitle.split(" • ").firstOrNull { it.contains("subscriber", ignoreCase = true) }
                    onlineArtist = OnlineArtist(
                        browseId = browseId,
                        title = title,
                        thumbnailUrl = thumbnail,
                        subscriberCount = subscriberInfo ?: subtitle
                    )
                    resolvedItemType = "Artist"
                }
                isAlbum && browseId.isNotEmpty() -> {
                    val year = subtitle.split(" • ").lastOrNull()?.trim()?.toIntOrNull()
                    val artistName = if (parsedArtistName.isNotEmpty()) parsedArtistName else getCleanArtist(subtitle)
                    onlineAlbum = OnlineAlbum(
                        browseId = browseId,
                        title = title,
                        artists = listOf(OnlineArtistRef(name = artistName, id = parsedArtistId)),
                        year = year,
                        thumbnailUrl = thumbnail
                    )
                    resolvedItemType = if (firstSub.equals("EP", ignoreCase = true)) "EP" else if (firstSub.equals("Single", ignoreCase = true)) "Single" else "Album"
                }
                isPlaylist && browseId.isNotEmpty() -> {
                    onlinePlaylist = OnlinePlaylist(
                        playlistId = browseId.removePrefix("VL"),
                        title = title,
                        thumbnailUrl = thumbnail
                    )
                    resolvedItemType = "Playlist"
                }
                !extractedVideoId.isNullOrEmpty() -> {
                    val artistText = if (parsedArtistName.isNotEmpty()) parsedArtistName else getCleanArtist(subtitle)
                    onlineSong = OnlineSong(
                        videoId = extractedVideoId,
                        title = title,
                        artist = artistText,
                        artistId = parsedArtistId,
                        thumbnailUrl = thumbnail,
                        isExplicit = isExplicit(cardShelf)
                    )
                    resolvedItemType = if (isVideo) "Video" else "Song"
                }
                browseId.isNotEmpty() -> {
                    // Fallback to Artist if browseId exists with UC prefix
                    if (browseId.startsWith("UC")) {
                        onlineArtist = OnlineArtist(
                            browseId = browseId,
                            title = title,
                            thumbnailUrl = thumbnail,
                            subscriberCount = subtitle
                        )
                        resolvedItemType = "Artist"
                    } else {
                        val artistText = if (parsedArtistName.isNotEmpty()) parsedArtistName else getCleanArtist(subtitle)
                        onlineSong = OnlineSong(
                            videoId = browseId,
                            title = title,
                            artist = artistText,
                            artistId = parsedArtistId,
                            thumbnailUrl = thumbnail,
                            isExplicit = isExplicit(cardShelf)
                        )
                        resolvedItemType = "Song"
                    }
                }
            }

            if (title.isNotEmpty()) {
                topResult = SearchItem.TopResult(
                    title = title,
                    subtitle = subtitle,
                    itemType = resolvedItemType,
                    thumbnailUrl = thumbnail,
                    song = onlineSong,
                    album = onlineAlbum,
                    artist = onlineArtist,
                    playlist = onlinePlaylist
                )
            }

            val contents = cardShelf.getAsJsonArray("contents")
            if (contents != null && contents.size() > 0) {
                items.add(SearchItem.Header("MORE FROM YOUTUBE"))
                contents.forEach { child ->
                    val childObj = child.asJsonObject
                    parseSearchItem(childObj)?.let { items.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse musicCardShelfRenderer: ${e.message}")
        }

        return Pair(topResult, items)
    }

    private fun parseSearchItem(item: JsonObject): SearchItem? {
        val renderer = item.getAsJsonObject("musicResponsiveListItemRenderer") ?: return null

        return try {
            val flexColumns = renderer.getAsJsonArray("flexColumns")
            val title = flexColumns?.getOrNull(0)?.asJsonObject
                ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs.0.text")?.asString ?: return null

            val subtitleRuns = flexColumns.getOrNull(1)?.asJsonObject
                ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs")?.asJsonArray
                ?: if (flexColumns.size() == 1) {
                    val allRuns = flexColumns.getOrNull(0)?.asJsonObject
                        ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs")?.asJsonArray
                    if (allRuns != null && allRuns.size() > 2) {
                        JsonArray().apply {
                            for (i in 2 until allRuns.size()) {
                                add(allRuns[i])
                            }
                        }
                    } else null
                } else null

            // Detect inline badges or type indicators
            var detectedBadge: String? = null
            var detectedTypeText: String? = null

            val firstSubtitleRun = subtitleRuns?.getOrNull(0)?.asJsonObject?.get("text")?.asString?.trim()

            if (firstSubtitleRun != null) {
                if (firstSubtitleRun.equals("REMIX", ignoreCase = true) || firstSubtitleRun.contains("REMIX", ignoreCase = true)) {
                    detectedBadge = "REMIX"
                } else if (firstSubtitleRun.equals("Song", ignoreCase = true) || firstSubtitleRun.equals("Video", ignoreCase = true) || firstSubtitleRun.equals("EP", ignoreCase = true)) {
                    detectedTypeText = firstSubtitleRun
                }
            }
            if (detectedBadge == null && (title.contains("Remix", ignoreCase = true) || title.contains("Bass Boosted", ignoreCase = true))) {
                detectedBadge = "REMIX"
            }

            val overlay = renderer.path("overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint")?.asJsonObject

            val titleBrowseEndpoint = flexColumns.getOrNull(0)?.asJsonObject
                ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.browseEndpoint")?.asJsonObject
                ?: renderer.path("navigationEndpoint.browseEndpoint")?.asJsonObject

            val browseEndpoint = titleBrowseEndpoint
                ?: renderer.path("navigationEndpoint.browseEndpoint")?.asJsonObject

            val watchEndpoint = overlay?.getAsJsonObject("watchEndpoint")
                ?: renderer.path("navigationEndpoint.watchEndpoint")?.asJsonObject
                ?: flexColumns.getOrNull(0)?.asJsonObject
                    ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.watchEndpoint")?.asJsonObject

            // 1. First check browseEndpoint to detect Artist, Album, Playlist
            if (browseEndpoint != null) {
                val browseId = browseEndpoint.get("browseId")?.asString ?: ""
                val pageType = browseEndpoint.path("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType")?.asString ?: ""

                val subtitle = subtitleRuns?.joinToString("") { run ->
                    run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                } ?: ""
                val subParts = if (subtitle.isNotEmpty()) subtitle.split(" • ") else emptyList()
                val thumbnail = parseThumbnail(renderer)

                val isArtist = pageType == "MUSIC_PAGE_TYPE_ARTIST" || 
                        pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" || 
                        browseId.startsWith("UC") || 
                        browseId.startsWith("FEmusic_artist") ||
                        firstSubtitleRun?.equals("Artist", ignoreCase = true) == true ||
                        subtitle.contains("Artist", ignoreCase = true) ||
                        subtitle.contains("subscriber", ignoreCase = true)

                val isAlbum = pageType == "MUSIC_PAGE_TYPE_ALBUM" || 
                        browseId.startsWith("MPRE") ||
                        firstSubtitleRun?.equals("Album", ignoreCase = true) == true ||
                        firstSubtitleRun?.equals("EP", ignoreCase = true) == true ||
                        firstSubtitleRun?.equals("Single", ignoreCase = true) == true ||
                        subtitle.contains("Album", ignoreCase = true) ||
                        subtitle.contains("Single", ignoreCase = true) ||
                        subtitle.contains("EP", ignoreCase = true)

                val isPlaylist = pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || 
                        pageType == "MUSIC_PAGE_TYPE_RADIO" ||
                        pageType == "MUSIC_PAGE_TYPE_PODCAST_SHOW" ||
                        browseId.startsWith("VL") || 
                        browseId.startsWith("PL") ||
                        browseId.startsWith("RD") ||
                        browseId.startsWith("VLRD") ||
                        browseId.startsWith("OLAK") ||
                        browseId.startsWith("MPSP") ||
                        browseId.startsWith("MPED") ||
                        firstSubtitleRun?.equals("Playlist", ignoreCase = true) == true ||
                        firstSubtitleRun?.equals("Mix", ignoreCase = true) == true ||
                        firstSubtitleRun?.equals("Chart", ignoreCase = true) == true ||
                        firstSubtitleRun?.equals("Podcast", ignoreCase = true) == true ||
                        firstSubtitleRun?.equals("Station", ignoreCase = true) == true ||
                        subtitle.contains("Playlist", ignoreCase = true) ||
                        subtitle.contains("Mix", ignoreCase = true) ||
                        subtitle.contains("Chart", ignoreCase = true) ||
                        subtitle.contains("Podcast", ignoreCase = true) ||
                        subtitle.contains("Station", ignoreCase = true)

                when {
                    isArtist -> {
                        val subs = subParts.firstOrNull { it.contains("subscriber", ignoreCase = true) }
                        return SearchItem.Artist(OnlineArtist(
                            browseId = browseId,
                            title = title,
                            thumbnailUrl = thumbnail,
                            subscriberCount = subs ?: subtitle
                        ))
                    }
                    isAlbum -> {
                        val year = subParts.lastOrNull()?.toIntOrNull()
                        var artistName = ""
                        var artistId: String? = null
                        subtitleRuns?.forEach { run ->
                            val runObj = run.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                            val text = runObj.get("text")?.asString ?: ""
                            val endpoint = runObj.path("navigationEndpoint.browseEndpoint")?.asJsonObject
                            if (endpoint != null) {
                                val pt = endpoint.path("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType")?.asString
                                if (pt == "MUSIC_PAGE_TYPE_ARTIST" || pt == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                                    artistName = text
                                    artistId = endpoint.get("browseId")?.asString
                                }
                            }
                        }
                        if (artistName.isBlank()) {
                            artistName = subParts.firstOrNull { it.lowercase() != "album" && !(it.length == 4 && it.all { ch -> ch.isDigit() }) } ?: ""
                        }
                        return SearchItem.Album(OnlineAlbum(
                            browseId = browseId,
                            title = title,
                            artists = listOf(OnlineArtistRef(name = artistName, id = artistId)),
                            thumbnailUrl = thumbnail,
                            year = year
                        ))
                    }
                    isPlaylist || watchEndpoint == null -> {
                        return SearchItem.Playlist(OnlinePlaylist(
                            playlistId = browseId.removePrefix("VL"),
                            title = title,
                            thumbnailUrl = thumbnail,
                            songCount = subParts.lastOrNull()
                        ))
                    }
                    // If watchEndpoint != null and not explicitly artist/album/playlist, fall through to song parser below!
                }
            }

            // 2. Fall back to Song if watchEndpoint is non-null
            if (watchEndpoint != null) {
                val videoId = watchEndpoint.get("videoId")?.asString ?: return null
                var parsedArtist = ""
                var parsedArtistId: String? = null
                var parsedAlbumName: String? = null
                var parsedAlbumId: String? = null
                var durationText = ""

                subtitleRuns?.forEach { run ->
                    val runObj = run.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val text = runObj.get("text")?.asString ?: ""
                    val endpoint = runObj.path("navigationEndpoint.browseEndpoint")?.asJsonObject

                    if (endpoint != null) {
                        val bId = endpoint.get("browseId")?.asString
                        val pageType = endpoint.path("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType")?.asString

                        if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                            if (parsedArtistId == null) {
                                parsedArtistId = bId
                                parsedArtist = text
                            }
                        } else if (pageType == "MUSIC_PAGE_TYPE_ALBUM") {
                            parsedAlbumId = bId
                            parsedAlbumName = text
                        }
                    } else {
                        val trimmed = text.trim()
                        if (trimmed.contains(':') && trimmed.all { ch -> ch.isDigit() || ch == ':' }) {
                            durationText = trimmed
                        }
                    }
                }

                if (parsedArtist.isBlank()) {
                    val subtitleText = subtitleRuns?.joinToString("") { run ->
                        run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                    } ?: ""
                    parsedArtist = getCleanArtist(subtitleText)
                }
                if (durationText.isBlank()) {
                    val fallback = subtitleRuns?.joinToString("") { run ->
                        run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                    }?.split(" • ")?.lastOrNull()?.trim() ?: ""
                    if (fallback.contains(':') && fallback.all { ch -> ch.isDigit() || ch == ':' }) {
                        durationText = fallback
                    }
                }

                val thumbnail = parseThumbnail(renderer)

                return SearchItem.Song(
                    song = OnlineSong(
                        videoId = videoId,
                        title = title,
                        artist = parsedArtist.trim(),
                        artistId = parsedArtistId,
                        albumName = parsedAlbumName,
                        albumId = parsedAlbumId,
                        duration = durationText.trim().takeIf { it.isNotEmpty() },
                        durationMs = parseDuration(durationText.trim()),
                        thumbnailUrl = thumbnail,
                        isExplicit = isExplicit(renderer)
                    ),
                    badge = detectedBadge,
                    typeText = detectedTypeText
                )
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse search item: ${e.message}")
            null
        }
    }

    // ========== Player Parsing ==========

    fun parsePlayerResponse(json: JsonObject, parseStreams: Boolean = true): PlayerResult? {
        try {
            val playabilityStatus = json.getAsJsonObject("playabilityStatus")
            val status = playabilityStatus?.get("status")?.asString
            if (status != "OK") {
                Log.w(TAG, "Player status: $status — ${playabilityStatus?.get("reason")?.asString}")
                return null
            }

            val videoDetails = json.getAsJsonObject("videoDetails") ?: return null
            val streamingData = json.getAsJsonObject("streamingData") ?: return null

            val videoId = videoDetails.get("videoId")?.asString ?: return null
            val title = videoDetails.get("title")?.asString ?: "Unknown"
            val author = videoDetails.get("author")?.asString ?: "Unknown Artist"
            val durationMs = (videoDetails.get("lengthSeconds")?.asLong ?: 0) * 1000
            val thumbnail = videoDetails.path("thumbnail.thumbnails")?.asJsonArray
                ?.lastOrNull()?.asJsonObject?.get("url")?.asString

            val animatedThumbnailUrl = findAnimatedThumbnailUrl(json)

            val allFormats = mutableListOf<JsonElement>()
            if (parseStreams) {
                streamingData.getAsJsonArray("adaptiveFormats")?.let { allFormats.addAll(it) }
                streamingData.getAsJsonArray("formats")?.let { allFormats.addAll(it) }
            }

            // Pass 1 — rank candidates using ONLY plain JSON fields (no JavaScript execution).
            // Deciphering the signature and the 'n' throttling parameter each invoke YouTube's
            // base.js through a JS engine, costing tens of milliseconds per URL. Since this is a
            // winner-takes-all selection, doing that work for all 20-40 formats and then throwing
            // away all but one wasted the overwhelming majority of resolution time. Rank first,
            // then deobfuscate only the stream we actually keep (pass 2).
            val audioCandidates = mutableListOf<RawFormat>()
            val videoCandidates = mutableListOf<RawFormat>()

            allFormats.forEach { format ->
                val obj = format.asJsonObject
                val mimeType = obj.get("mimeType")?.asString ?: ""

                val isAudioOnly = mimeType.startsWith("audio/")
                val isVideo = mimeType.startsWith("video/")

                // Keep audio and video streams. We'll prioritize audio, but use video as a fallback if no audio exists.
                if (!isAudioOnly && !isVideo) {
                    return@forEach
                }

                val rawUrl = obj.get("url")?.asString
                val cipher = obj.get("signatureCipher")?.asString ?: obj.get("cipher")?.asString
                if (rawUrl == null && cipher == null) {
                    return@forEach
                }

                val candidate = RawFormat(
                    rawUrl = rawUrl,
                    cipher = cipher,
                    mimeType = mimeType,
                    bitrate = obj.get("bitrate")?.asInt ?: obj.get("averageBitrate")?.asInt ?: 0,
                    itag = obj.get("itag")?.asInt ?: 0,
                    contentLength = obj.get("contentLength")?.asLong,
                    audioQuality = obj.get("audioQuality")?.asString
                )

                if (isAudioOnly) audioCandidates.add(candidate) else videoCandidates.add(candidate)
            }

            // Rank audio candidate formats according to user quality settings:
            // High / Force Opus: Always OPUS first (~160k itag 251), fallback to AAC
            // Normal: Highest bitrate stream (OPUS 160k or AAC 128k/256k)
            // Low / Data Saver: Lowest bitrate stream (e.g. 50k Opus or 48k AAC)
            val (prefQuality, prefDataSaver, prefForceOpus) = NewPipeStreamExtractor.getPreferencesSnapshot()
            val isDataSaver = prefDataSaver || prefQuality.equals("Data Saver", ignoreCase = true) || prefQuality.equals("Low", ignoreCase = true)
            val preferOpus = !isDataSaver && (prefForceOpus || !prefQuality.equals("AAC Only", ignoreCase = true))

            val rankedAudio = when {
                isDataSaver -> {
                    audioCandidates.sortedWith(
                        compareByDescending<RawFormat> { if (it.rawUrl != null) 1 else 0 }
                            .thenBy<RawFormat> { it.bitrate }
                            .thenByDescending {
                                val isOpus = it.mimeType.contains("opus", ignoreCase = true) || it.mimeType.contains("webm", ignoreCase = true) || it.itag in listOf(251, 250, 249)
                                if (isOpus) 2 else 1
                            }
                    )
                }
                preferOpus -> {
                    audioCandidates.sortedWith(
                        compareByDescending<RawFormat> {
                            val isOpus160 = it.itag == 251 || (it.mimeType.contains("opus", ignoreCase = true) && it.bitrate >= 140000)
                            val isAac256 = it.itag == 141 || ((it.mimeType.contains("mp4a", ignoreCase = true) || it.mimeType.contains("m4a", ignoreCase = true) || it.mimeType.contains("aac", ignoreCase = true)) && it.bitrate >= 200000)
                            val isAac128 = it.itag == 140 || ((it.mimeType.contains("mp4a", ignoreCase = true) || it.mimeType.contains("m4a", ignoreCase = true) || it.mimeType.contains("aac", ignoreCase = true)) && it.bitrate >= 120000)
                            val isOpus = it.mimeType.contains("opus", ignoreCase = true) || it.mimeType.contains("webm", ignoreCase = true) || it.itag in listOf(251, 250, 249)
                            val directBonus = if (it.rawUrl != null) 10 else 0
                            val codecScore = when {
                                isOpus160 -> 5
                                isAac256  -> 4
                                isAac128  -> 3
                                isOpus    -> 2
                                else      -> 1
                            }
                            directBonus + codecScore
                        }.thenByDescending { it.bitrate }
                    )
                }
                else -> {
                    // AAC Prioritization: AAC 256k > AAC 128k > Opus > Others
                    audioCandidates.sortedWith(
                        compareByDescending<RawFormat> {
                            val isAac256 = it.itag == 141 || (it.mimeType.contains("mp4a", ignoreCase = true) && it.bitrate >= 200000)
                            val isAac = it.mimeType.contains("mp4a", ignoreCase = true) || it.mimeType.contains("m4a", ignoreCase = true) || it.mimeType.contains("aac", ignoreCase = true) || it.itag in listOf(141, 140, 139)
                            val isOpus160 = it.itag == 251 || (it.mimeType.contains("opus", ignoreCase = true) && it.bitrate >= 140000)
                            val isOpus = it.mimeType.contains("opus", ignoreCase = true) || it.mimeType.contains("webm", ignoreCase = true) || it.itag in listOf(251, 250, 249)
                            val directBonus = if (it.rawUrl != null) 10 else 0
                            val codecScore = when {
                                isAac256  -> 5
                                isAac     -> 4
                                isOpus160 -> 3
                                isOpus    -> 2
                                else      -> 1
                            }
                            directBonus + codecScore
                        }.thenByDescending { it.bitrate }
                    )
                }
            }
            val rankedVideo = videoCandidates.sortedByDescending { it.bitrate }
            val ranked = rankedAudio + rankedVideo

            com.mardous.booming.online.util.ConsoleLogger.format("=== Formats for $videoId (preferOpus=$preferOpus, candidates=${rankedAudio.size}) ===")
            rankedAudio.take(5).forEach { c ->
                val codec = if (c.mimeType.contains("opus", ignoreCase = true) || c.mimeType.contains("webm", ignoreCase = true)) "OPUS" else "AAC"
                val direct = if (c.rawUrl != null) "DIRECT" else "CIPHER"
                com.mardous.booming.online.util.ConsoleLogger.format(" -> itag=${c.itag} | $codec | ${c.bitrate / 1000} kbps | $direct")
            }

            // Pass 2 — resolve the winner. Normally this is exactly one JS round-trip. We walk
            // further down the ranking only if deobfuscation genuinely fails for the top pick.
            // If deobfuscation function parsing fails once, skip remaining ciphered candidates immediately.
            var finalStream: StreamData? = null
            var cipherDeobfuscationUnavailable = false

            for (candidate in ranked) {
                if (candidate.rawUrl == null && cipherDeobfuscationUnavailable) {
                    continue
                }

                val resolvedUrl = resolveFormatUrl(videoId, candidate) { isDeobfuscationError ->
                    if (isDeobfuscationError) {
                        cipherDeobfuscationUnavailable = true
                        InnerTubeClient.markStreamClientFailed(videoId, "WEB_REMIX")
                    }
                } ?: continue

                finalStream = StreamData(
                    url = resolvedUrl,
                    mimeType = candidate.mimeType,
                    bitrate = candidate.bitrate,
                    contentLength = candidate.contentLength,
                    audioQuality = candidate.audioQuality
                )
                break
            }

            if (finalStream != null) {
                val codec = if (finalStream.mimeType.contains("opus", ignoreCase = true) || finalStream.mimeType.contains("webm", ignoreCase = true)) "OPUS" else "AAC"
                val kbps = if (finalStream.bitrate >= 10000) finalStream.bitrate / 1000 else if (codec == "OPUS") 160 else 128
                com.mardous.booming.online.util.ConsoleLogger.stream("Picked stream for $videoId: $codec • $kbps kbps (${finalStream.mimeType})")
            }

            // Audio-only stream is preferred; allow muxed video/audio stream as fallback if no audio-only exists
            val bestStream = finalStream
            val streams = mutableListOf<StreamData>()
            finalStream?.let { streams.add(it) }

            Log.d(TAG, "Parsed player: $videoId — ${streams.size} audio streams from ${allFormats.size} total formats")
            com.mardous.booming.online.util.ConsoleLogger.d(TAG, "Parsed player: $videoId — ${streams.size} audio streams from ${allFormats.size} total formats")

            val playbackTracking = json.getAsJsonObject("playbackTracking")

            return PlayerResult(
                videoId = videoId,
                title = title,
                artist = author,
                thumbnailUrl = thumbnail,
                animatedThumbnailUrl = animatedThumbnailUrl,
                durationMs = durationMs,
                streams = streams,
                playbackUrl = bestStream?.url,
                watchtimeUrl = playbackTracking?.path("videostatsWatchtimeUrl.baseUrl")?.asString,
                likesCount = null
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error parsing player response", e)
            return null
        }
    }

    /**
     * A stream format as it appears in the player response, before any JavaScript-backed
     * deobfuscation has run. Holding the raw shape lets us rank formats cheaply and pay the
     * JS cost only for the one we keep.
     */
    private data class RawFormat(
        val rawUrl: String?,
        val cipher: String?,
        val mimeType: String,
        val bitrate: Int,
        val itag: Int = 0,
        val contentLength: Long?,
        val audioQuality: String?
    )

    /**
     * Turns a raw format into a playable URL: deciphers the signature when the direct URL is
     * withheld, then unthrottles via the 'n' parameter. Both steps execute YouTube's base.js,
     * so this is only ever called for a format we intend to play. Returns null if the format
     * cannot be resolved, letting the caller try the next-best candidate.
     */
    private fun resolveFormatUrl(
        videoId: String,
        format: RawFormat,
        onDeobfuscationError: ((Boolean) -> Unit)? = null
    ): String? {
        // Direct un-ciphered URL (ANDROID_VR, IOS, etc.): return immediately in 0ms!
        if (!format.rawUrl.isNullOrEmpty()) {
            return format.rawUrl
        }

        val cipher = format.cipher ?: return null
        val url: String = try {
            val params = parseQueryString(cipher)
            val streamUrl = params["url"] ?: return null
            val signature = params["s"] ?: return null
            val sp = params["sp"] ?: "sig"
            val deobfuscatedSig = org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature)
            val encodedSig = java.net.URLEncoder.encode(deobfuscatedSig, "UTF-8")
            val separator = if (streamUrl.contains("?")) "&" else "?"
            "$streamUrl$separator$sp=$encodedSig"
        } catch (e: Throwable) {
            val isDeobfuscationError = e.message?.contains("deobfuscation", ignoreCase = true) == true ||
                e.javaClass.simpleName == "ParsingException"
            if (isDeobfuscationError) {
                com.mardous.booming.online.util.ConsoleLogger.client("Cipher deobfuscation parser unavailable for $videoId (${e.message}). Skipping ciphered formats -> immediate extractor fallback.")
                onDeobfuscationError?.invoke(true)
            } else {
                com.mardous.booming.online.util.ConsoleLogger.e(TAG, "Cipher decryption failed for $videoId (itag=${format.itag}): ${e.javaClass.simpleName} - ${e.message}")
            }
            return null
        }

        // Unthrottle ciphered URL. If unthrottling fails, still return the deciphered stream.
        return try {
            org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                .getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to deobfuscate throttling parameter", e)
            url
        }
    }

    // ========== Browse / Home Feed Parsing ==========

    fun parseHomePage(json: JsonObject): HomePage {
        val sections = mutableListOf<HomeSection>()

        try {
            // 1. Parse from modern onResponseReceivedActions (Standard format for browse continuations)
            var actionContinuation: String? = null
            val actions = json.getAsJsonArray("onResponseReceivedActions")
            if (actions != null && actions.size() > 0) {
                for (i in 0 until actions.size()) {
                    val action = actions.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val items = action.path("appendContinuationItemsAction.continuationItems")?.asJsonArray
                        ?: action.path("reloadContinuationItemsCommand.continuationItems")?.asJsonArray
                    if (items != null) {
                        items.forEach { item ->
                            val itemObj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                            sections.addAll(parseHomeSections(itemObj))
                        }
                        if (actionContinuation.isNullOrBlank()) {
                            actionContinuation = YouTubeMusic.extractContinuationToken(contents = items)
                        }
                    }
                }
            }

            // 2. Parse top-level contents across all tabs (Standard initial home feed format)
            val allSectionArrays = mutableListOf<JsonArray>()

            // Single column browse tabs
            json.path("contents.singleColumnBrowseResultsRenderer.tabs")?.asJsonArray?.forEach { tab ->
                tab.asJsonObject.path("tabRenderer.content.sectionListRenderer.contents")?.asJsonArray?.let {
                    allSectionArrays.add(it)
                }
            }
            // Two column browse tabs
            json.path("contents.twoColumnBrowseResultsRenderer.tabs")?.asJsonArray?.forEach { tab ->
                tab.asJsonObject.path("tabRenderer.content.sectionListRenderer.contents")?.asJsonArray?.let {
                    allSectionArrays.add(it)
                }
            }
            // Secondary contents and direct section list contents
            json.path("contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents")?.asJsonArray?.let {
                allSectionArrays.add(it)
            }
            json.path("contents.singleColumnBrowseResultsRenderer.contents.0.sectionListRenderer.contents")?.asJsonArray?.let {
                allSectionArrays.add(it)
            }
            json.path("contents.sectionListRenderer.contents")?.asJsonArray?.let {
                allSectionArrays.add(it)
            }

            var lastContentArray: JsonArray? = null
            allSectionArrays.forEach { sectionArray ->
                lastContentArray = sectionArray
                sectionArray.forEach { sectionElement ->
                    val sectionObj = sectionElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    sections.addAll(parseHomeSections(sectionObj))
                }
            }

            // 3. Legacy continuationContents format
            val contSections = json.path("continuationContents.sectionListContinuation.contents")?.asJsonArray
            contSections?.forEach { sectionElement ->
                val sectionObj = sectionElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                sections.addAll(parseHomeSections(sectionObj))
            }

            // 4. Extract next continuation token using robust multi-schema resolver
            val continuation = actionContinuation
                ?: YouTubeMusic.extractContinuationToken(contents = lastContentArray, response = json)
                ?: json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.continuations.0.nextContinuationData.continuation")?.asString
                ?: json.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.continuations.0.nextContinuationData.continuation")?.asString
                ?: json.path("continuationContents.sectionListContinuation.continuations.0.nextContinuationData.continuation")?.asString

            // 5. Extract Moods / Categories from Header Chips
            val moods = mutableListOf<String>()
            var chips = json.path(
                "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.header.chipCloudRenderer.chips"
            )?.asJsonArray
            if (chips == null) {
                chips = json.path(
                    "contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.header.chipCloudRenderer.chips"
                )?.asJsonArray
            }
            if (chips == null) {
                chips = json.path("header.chipCloudRenderer.chips")?.asJsonArray
            }
            chips?.forEach { chip ->
                val text = chip.asJsonObject.path("chipCloudChipRenderer.text.runs.0.text")?.asString
                if (!text.isNullOrBlank()) {
                    moods.add(text)
                }
            }

            // Deduplicate sections by normalized title to prevent repeated shelves while preserving order
            val distinctSections = mutableListOf<HomeSection>()
            val seenTitles = mutableSetOf<String>()
            for (sec in sections) {
                val normalizedTitle = sec.title.trim().lowercase()
                if (sec.items.isNotEmpty() && seenTitles.add(normalizedTitle)) {
                    distinctSections.add(sec)
                }
            }

            return HomePage(distinctSections, continuation, moods)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing home page", e)
        }

        return HomePage(sections)
    }

    fun parseMoodsAndGenresPage(json: JsonObject): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()
        try {
            val contents = json.path(
                "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents"
            )?.asJsonArray

            contents?.forEach { section ->
                val gridRenderer = section.asJsonObject.getAsJsonObject("gridRenderer")
                val carouselRenderer = section.asJsonObject.getAsJsonObject("musicCarouselShelfRenderer")
                
                val title = section.asJsonObject.path("gridRenderer.header.gridHeaderRenderer.title.runs.0.text")?.asString
                    ?: section.asJsonObject.path("musicCarouselShelfRenderer.header.musicCarouselShelfBasicHeaderRenderer.title.runs.0.text")?.asString
                    ?: "Categories"
                
                val items = mutableListOf<SearchItem>()
                val renderItems = gridRenderer?.getAsJsonArray("items")
                    ?: carouselRenderer?.getAsJsonArray("contents")
                
                renderItems?.forEach { item ->
                    val buttonRenderer = item.asJsonObject.getAsJsonObject("musicNavigationButtonRenderer")
                    if (buttonRenderer != null) {
                        val text = buttonRenderer.path("buttonText.runs.0.text")?.asString ?: return@forEach
                        val browseId = buttonRenderer.path("clickCommand.browseEndpoint.browseId")?.asString ?: return@forEach
                        val params = buttonRenderer.path("clickCommand.browseEndpoint.params")?.asString
                        
                        var thumbnail: String? = null
                        var colorVal: Long? = null
                        
                        // Primary color source: solid.leftStripeColor (ARGB u32)
                        // This is the main field returned by WEB_REMIX for mood/genre buttons
                        colorVal = buttonRenderer.path("solid.leftStripeColor")?.asLong
                        
                        // Fallback: solidColorBackgroundAndImageRenderer (some client versions)
                        val solidColorBgAndImage = buttonRenderer.getAsJsonObject("solidColorBackgroundAndImageRenderer")
                        if (solidColorBgAndImage != null) {
                            thumbnail = solidColorBgAndImage.path("image.musicThumbnailRenderer.thumbnail.thumbnails")
                                ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                                ?: solidColorBgAndImage.path("image.thumbnail.thumbnails")
                                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                                ?: solidColorBgAndImage.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                            
                            if (colorVal == null) {
                                colorVal = solidColorBgAndImage.path("color.color")?.asLong
                                    ?: solidColorBgAndImage.path("solidColor.color")?.asLong
                                    ?: solidColorBgAndImage.path("solidColor")?.asLong
                            }
                        }
                        // Fallback thumbnail paths
                        if (thumbnail == null) {
                            thumbnail = buttonRenderer.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                                ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                                ?: buttonRenderer.path("thumbnail.thumbnails")
                                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                                ?: buttonRenderer.path("icon.thumbnails")
                                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                        }
                        
                        items.add(SearchItem.Playlist(
                            OnlinePlaylist(
                                playlistId = "GENRE::$browseId::${params ?: ""}",
                                title = text,
                                thumbnailUrl = getHighResThumbnailUrl(thumbnail),
                                color = colorVal
                            )
                        ))
                    }
                }
                
                if (items.isNotEmpty()) {
                    sections.add(HomeSection(title, items))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing moods and genres page", e)
        }
        return sections
    }

    fun parseHomeSections(section: JsonObject): List<HomeSection> {
        val results = mutableListOf<HomeSection>()
        val itemSection = section.getAsJsonObject("itemSectionRenderer")
        if (itemSection != null) {
            val innerContents = itemSection.getAsJsonArray("contents")
            var handledAsShelves = false
            if (innerContents != null && innerContents.size() > 0) {
                for (i in 0 until innerContents.size()) {
                    val innerObj = innerContents.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    if (innerObj.has("musicCarouselShelfRenderer") ||
                        innerObj.has("musicShelfRenderer") ||
                        innerObj.has("gridRenderer") ||
                        innerObj.has("musicImmersiveCarouselShelfRenderer") ||
                        innerObj.has("musicCardShelfRenderer")
                    ) {
                        parseSingleHomeSection(innerObj)?.let { results.add(it) }
                        handledAsShelves = true
                    }
                }
            }
            if (!handledAsShelves) {
                parseSingleHomeSection(section)?.let { results.add(it) }
            }
        } else {
            parseSingleHomeSection(section)?.let { results.add(it) }
        }
        return results
    }

    private fun parseSingleHomeSection(target: JsonObject): HomeSection? {
        val carousel = target.getAsJsonObject("musicCarouselShelfRenderer") 
            ?: target.getAsJsonObject("musicImmersiveCarouselShelfRenderer")
        val shelf = target.getAsJsonObject("musicShelfRenderer")
        val grid = target.getAsJsonObject("gridRenderer")
        val cardShelf = target.getAsJsonObject("musicCardShelfRenderer")
        val itemSection = target.getAsJsonObject("itemSectionRenderer")

        if (carousel == null && shelf == null && grid == null && cardShelf == null && itemSection == null) return null

        val header = carousel?.path("header.musicCarouselShelfBasicHeaderRenderer.title.runs.0.text")?.asString 
            ?: carousel?.path("header.musicImmersiveCarouselShelfHeaderRenderer.title.runs.0.text")?.asString
            ?: shelf?.path("title.runs.0.text")?.asString
            ?: shelf?.path("header.musicShelfHeaderBasicRenderer.title.runs.0.text")?.asString
            ?: grid?.path("header.gridHeaderRenderer.title.runs.0.text")?.asString
            ?: cardShelf?.path("header.musicCardShelfHeaderBasicRenderer.title.runs.0.text")?.asString
            ?: cardShelf?.path("title.runs.0.text")?.asString
            ?: itemSection?.path("header.itemSectionHeaderRenderer.title.runs.0.text")?.asString
            ?: "Featured"

        val contents = carousel?.getAsJsonArray("contents")
            ?: shelf?.getAsJsonArray("contents")
            ?: grid?.getAsJsonArray("items")
            ?: cardShelf?.getAsJsonArray("contents")
            ?: itemSection?.getAsJsonArray("contents")

        val items = ArrayList<SearchItem>(contents?.size() ?: 16)

        contents?.forEach { content ->
            val obj = content.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val twoRowRenderer = obj.getAsJsonObject("musicTwoRowItemRenderer")
            val listRenderer = obj.getAsJsonObject("musicResponsiveListItemRenderer")
            val multiRowRenderer = obj.getAsJsonObject("musicMultiRowListItemRenderer")

            if (twoRowRenderer != null) {
                parseTwoRowItem(twoRowRenderer)?.let { items.add(it) }
            } else if (listRenderer != null) {
                parseSearchItem(obj)?.let { items.add(it) }
            } else if (multiRowRenderer != null) {
                parseMultiRowListItem(multiRowRenderer)?.let { items.add(it) }
            } else {
                val innerTwoRow = obj.path("compactStationRenderer")?.asJsonObject
                    ?: obj.path("musicNavigationButtonRenderer")?.asJsonObject
                if (innerTwoRow != null) {
                    val titleStr = innerTwoRow.path("title.runs.0.text")?.asString
                        ?: innerTwoRow.path("buttonText.runs.0.text")?.asString
                    val browseId = innerTwoRow.path("clickCommand.browseEndpoint.browseId")?.asString
                        ?: innerTwoRow.path("navigationEndpoint.browseEndpoint.browseId")?.asString
                    val thumb = innerTwoRow.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    if (!titleStr.isNullOrEmpty() && !browseId.isNullOrEmpty()) {
                        items.add(SearchItem.Playlist(OnlinePlaylist(playlistId = browseId, title = titleStr, thumbnailUrl = getHighResThumbnailUrl(thumb))))
                    }
                }
            }
        }

        if (items.isEmpty()) return null

        val browseEndpoint = carousel?.path("header.musicCarouselShelfBasicHeaderRenderer.moreContentButton.buttonRenderer.navigationEndpoint.browseEndpoint.browseId")?.asString
            ?: shelf?.path("title.runs.0.navigationEndpoint.browseEndpoint.browseId")?.asString
            ?: shelf?.path("header.musicShelfHeaderBasicRenderer.moreContentButton.buttonRenderer.navigationEndpoint.browseEndpoint.browseId")?.asString
            ?: grid?.path("header.gridHeaderRenderer.viewAllButton.buttonRenderer.navigationEndpoint.browseEndpoint.browseId")?.asString

        val params = carousel?.path("header.musicCarouselShelfBasicHeaderRenderer.moreContentButton.buttonRenderer.navigationEndpoint.browseEndpoint.params")?.asString
            ?: shelf?.path("title.runs.0.navigationEndpoint.browseEndpoint.params")?.asString
            ?: shelf?.path("header.musicShelfHeaderBasicRenderer.moreContentButton.buttonRenderer.navigationEndpoint.browseEndpoint.params")?.asString
            ?: grid?.path("header.gridHeaderRenderer.viewAllButton.buttonRenderer.navigationEndpoint.browseEndpoint.params")?.asString

        return HomeSection(
            title = header,
            items = items.distinctBy { it.stableKey },
            browseEndpoint = browseEndpoint,
            params = params
        )
    }

    private fun parseTwoRowItem(renderer: JsonObject): SearchItem? {
        try {
            val title = renderer.extractFirstRunText("title")
                ?: renderer.path("title.runs.0.text")?.asString ?: return null
            val subtitle = renderer.extractRunsText("subtitle") ?: ""
            val subParts = if (subtitle.isNotEmpty()) subtitle.split(" • ") else emptyList()

            val thumbnailsArr = renderer.path("thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails")?.asJsonArray
            val thumbLast = thumbnailsArr?.lastOrNull()?.asJsonObject
            val thumbWidth = thumbLast?.get("width")?.asInt
            val thumbHeight = thumbLast?.get("height")?.asInt
            val thumbnail = thumbLast?.get("url")?.asString

            val isVideoAspect = (thumbWidth != null && thumbHeight != null && thumbWidth > thumbHeight * 1.25f) ||
                renderer.path("aspectRatio")?.asString?.contains("16_9", ignoreCase = true) == true ||
                renderer.path("thumbnailRenderer.musicThumbnailRenderer.thumbnailCrop")?.asString?.contains("16_9", ignoreCase = true) == true ||
                thumbnail?.contains("ytimg.com/vi/") == true ||
                thumbnail?.contains("i.ytimg.com/vi/") == true

            val navEndpoint = renderer.path("navigationEndpoint")?.asJsonObject
                ?: renderer.path("onTap.navigationEndpoint")?.asJsonObject
                ?: renderer.path("clickCommand.navigationEndpoint")?.asJsonObject
                ?: renderer.path("onTap")?.asJsonObject
                ?: renderer.path("clickCommand")?.asJsonObject

            val browseEndpoint = navEndpoint?.getAsJsonObject("browseEndpoint")
                ?: renderer.path("title.runs.0.navigationEndpoint.browseEndpoint")?.asJsonObject
            val watchEndpoint = navEndpoint?.getAsJsonObject("watchEndpoint")
            val watchPlaylistEndpoint = navEndpoint?.getAsJsonObject("watchPlaylistEndpoint")

            return when {
                watchPlaylistEndpoint != null -> {
                    val playlistId = watchPlaylistEndpoint.get("playlistId")?.asString ?: return null
                    SearchItem.Playlist(OnlinePlaylist(
                        playlistId = playlistId.removePrefix("VL"),
                        title = title,
                        thumbnailUrl = thumbnail,
                        songCount = subParts.lastOrNull()
                    ))
                }
                watchEndpoint != null -> {
                    val videoId = watchEndpoint.get("videoId")?.asString
                    val playlistId = watchEndpoint.get("playlistId")?.asString

                    if (videoId != null) {
                        val subtitleRuns = renderer.path("subtitle.runs")?.asJsonArray
                        var parsedArtist = ""
                        var parsedArtistId: String? = null

                        subtitleRuns?.forEach { run ->
                            val runObj = run.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                            val text = runObj.get("text")?.asString ?: ""
                            val endpoint = runObj.path("navigationEndpoint.browseEndpoint")?.asJsonObject

                            if (endpoint != null) {
                                val pageType = endpoint.path("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType")?.asString
                                if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                                    if (parsedArtistId == null) {
                                        parsedArtistId = endpoint.get("browseId")?.asString
                                        parsedArtist = text
                                    }
                                }
                            }
                        }

                        if (parsedArtist.isBlank()) {
                            parsedArtist = getCleanArtist(subtitle)
                        }

                        val parsedDuration = subParts.firstOrNull { it.contains(':') && it.all { ch -> ch.isDigit() || ch == ':' } }

                        SearchItem.Song(OnlineSong(
                            videoId = videoId,
                            title = title,
                            artist = parsedArtist,
                            artistId = parsedArtistId,
                            thumbnailUrl = thumbnail,
                            duration = parsedDuration,
                            isExplicit = isExplicit(renderer),
                            isVideo = isVideoAspect,
                            thumbnailRatio = if (isVideoAspect) 16f / 9f else 1f
                        ))
                    } else if (playlistId != null) {
                        SearchItem.Playlist(OnlinePlaylist(
                            playlistId = playlistId.removePrefix("VL"),
                            title = title,
                            thumbnailUrl = thumbnail,
                            songCount = subParts.lastOrNull()
                        ))
                    } else {
                        null
                    }
                }
                browseEndpoint != null -> {
                    val browseId = browseEndpoint.get("browseId")?.asString ?: return null
                    val pageType = browseEndpoint.path(
                        "browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType"
                    )?.asString ?: ""

                    val isArtist = pageType == "MUSIC_PAGE_TYPE_ARTIST" ||
                            pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" ||
                            browseId.startsWith("UC") ||
                            browseId.startsWith("FEmusic_artist") ||
                            subtitle.contains("Artist", ignoreCase = true) ||
                            subtitle.contains("subscriber", ignoreCase = true)

                    val isAlbum = pageType == "MUSIC_PAGE_TYPE_ALBUM" ||
                            browseId.startsWith("MPRE") ||
                            browseId.startsWith("VLMPRE") ||
                            subtitle.contains("Album", ignoreCase = true) ||
                            subtitle.contains("Single", ignoreCase = true) ||
                            subtitle.contains("EP", ignoreCase = true)

                    val isPlaylist = pageType == "MUSIC_PAGE_TYPE_PLAYLIST" ||
                            pageType == "MUSIC_PAGE_TYPE_RADIO" ||
                            pageType == "MUSIC_PAGE_TYPE_PODCAST_SHOW" ||
                            browseId.startsWith("VL") ||
                            browseId.startsWith("PL") ||
                            browseId.startsWith("RD") ||
                            browseId.startsWith("VLRD") ||
                            browseId.startsWith("OLAK") ||
                            browseId.startsWith("MPSP") ||
                            browseId.startsWith("MPED") ||
                            subtitle.contains("Playlist", ignoreCase = true) ||
                            subtitle.contains("Mix", ignoreCase = true) ||
                            subtitle.contains("Station", ignoreCase = true) ||
                            subtitle.contains("Radio", ignoreCase = true)

                    when {
                        isArtist -> {
                            SearchItem.Artist(OnlineArtist(
                                browseId = browseId,
                                title = title,
                                thumbnailUrl = thumbnail,
                                subscriberCount = subtitle
                            ))
                        }
                        isAlbum -> {
                            val subtitleRuns = renderer.path("subtitle.runs")?.asJsonArray
                            var artistName = ""
                            var artistId: String? = null
                            subtitleRuns?.forEach { run ->
                                val runObj = run.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                                val text = runObj.get("text")?.asString ?: ""
                                val endpoint = runObj.path("navigationEndpoint.browseEndpoint")?.asJsonObject
                                if (endpoint != null) {
                                    val pt = endpoint.path("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType")?.asString
                                    if (pt == "MUSIC_PAGE_TYPE_ARTIST" || pt == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                                        artistName = text
                                        artistId = endpoint.get("browseId")?.asString
                                    }
                                }
                            }
                            if (artistName.isBlank()) {
                                artistName = if (subParts.size >= 2) {
                                    subParts.firstOrNull { it.lowercase() != "album" && !(it.length == 4 && it.all { ch -> ch.isDigit() }) } ?: ""
                                } else {
                                    subtitle
                                }
                            }
                            SearchItem.Album(OnlineAlbum(
                                browseId = browseId,
                                title = title,
                                artists = listOf(OnlineArtistRef(name = artistName, id = artistId)),
                                thumbnailUrl = thumbnail,
                                year = subParts.lastOrNull()?.toIntOrNull()
                            ))
                        }
                        isPlaylist -> {
                            SearchItem.Playlist(OnlinePlaylist(
                                playlistId = browseId.removePrefix("VL"),
                                title = title,
                                thumbnailUrl = thumbnail,
                                songCount = subParts.lastOrNull()
                            ))
                        }
                        else -> {
                            // Resilient fallback: treat unknown browse endpoint as a playlist so it remains clickable and browseable
                            SearchItem.Playlist(OnlinePlaylist(
                                playlistId = browseId.removePrefix("VL"),
                                title = title,
                                thumbnailUrl = thumbnail,
                                songCount = subParts.lastOrNull()
                            ))
                        }
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse two-row item: ${e.message}")
            return null
        }
    }

    private fun parseMultiRowListItem(renderer: JsonObject): SearchItem? {
        try {
            val title = renderer.path("title.runs.0.text")?.asString ?: return null
            val subtitle = renderer.path("subtitle.runs")?.asJsonArray
                ?.joinToString("") { run ->
                    run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                } ?: ""
            val thumbnail = renderer.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                ?: renderer.path("thumbnail.thumbnail.thumbnails")
                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString

            val onTap = renderer.path("onTap")?.asJsonObject
                ?: renderer.path("navigationEndpoint")?.asJsonObject
                ?: renderer.path("clickCommand")?.asJsonObject

            val watchEndpoint = onTap?.getAsJsonObject("watchEndpoint")
                ?: onTap?.getAsJsonObject("watchPlaylistEndpoint")
            val browseEndpoint = onTap?.getAsJsonObject("browseEndpoint")

            return when {
                watchEndpoint != null -> {
                    val videoId = watchEndpoint.get("videoId")?.asString ?: return null
                    val highRes = getHighResThumbnailUrl(thumbnail)
                    val isVideo = highRes?.contains("ytimg.com/vi/") == true || highRes?.contains("i.ytimg.com/vi/") == true
                    SearchItem.Song(
                        OnlineSong(
                            videoId = videoId,
                            title = title,
                            artist = subtitle.ifBlank { "Podcast" },
                            thumbnailUrl = highRes,
                            isVideo = isVideo,
                            thumbnailRatio = if (isVideo) 16f / 9f else 1f
                        )
                    )
                }
                browseEndpoint != null -> {
                    val browseId = browseEndpoint.get("browseId")?.asString ?: return null
                    SearchItem.Playlist(
                        OnlinePlaylist(
                            playlistId = browseId,
                            title = title,
                            thumbnailUrl = getHighResThumbnailUrl(thumbnail)
                        )
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse multi-row list item: ${e.message}")
            return null
        }
    }

    // ========== Album Page Parsing ==========

    fun parseAlbumPage(json: JsonObject, browseId: String): AlbumPage? {
        try {
            val header = json.path(
                "contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicResponsiveHeaderRenderer"
            )?.takeIf { it.isJsonObject }?.asJsonObject
                ?: json.path(
                    "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicResponsiveHeaderRenderer"
                )?.takeIf { it.isJsonObject }?.asJsonObject
                ?: json.path("header.musicResponsiveHeaderRenderer")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: json.path("header.musicDetailHeaderRenderer")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: json.path("header.musicVisualHeaderRenderer")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: json.path("header.musicHeaderRenderer")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return null

            val title = header.path("title.runs.0.text")?.asString
                ?: header.path("title.simpleText")?.asString
                ?: json.path("microformat.microformatDataRenderer.title")?.asString
                ?: return null

            val artists = (header.path("straplineTextOne.runs")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: header.path("subtitle.runs")?.takeIf { it.isJsonArray }?.asJsonArray)
                ?.mapNotNull { run ->
                    val obj = run.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val text = obj.get("text")?.asString ?: return@mapNotNull null
                    val id = obj.path("navigationEndpoint.browseEndpoint.browseId")?.asString
                    if (id != null && (id.startsWith("UC") || id.startsWith("FE"))) {
                        OnlineArtistRef(name = text, id = id)
                    } else if (text.isNotBlank() && !text.equals("Album", ignoreCase = true) && !text.equals("Single", ignoreCase = true) && !text.equals("EP", ignoreCase = true)) {
                        OnlineArtistRef(name = text, id = id)
                    } else null
                }?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(
                    header.path("straplineTextOne.runs.0.text")?.asString?.let {
                        OnlineArtistRef(
                            name = it,
                            id = header.path("straplineTextOne.runs.0.navigationEndpoint.browseEndpoint.browseId")?.asString
                        )
                    }
                )

            val year = header.path("subtitle.runs")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString?.trim() }
                ?.lastOrNull { it.length == 4 && it.all { c -> c.isDigit() } }
                ?.toIntOrNull()

            val thumbnail = getHighResThumbnailUrl(
                header.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")?.takeIf { it.isJsonArray }
                    ?.asJsonArray?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("url")?.asString
                    ?: header.path("thumbnail.croppedSquareThumbnailRenderer.thumbnail.thumbnails")?.takeIf { it.isJsonArray }
                        ?.asJsonArray?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("url")?.asString
                    ?: header.path("foregroundThumbnail.musicThumbnailRenderer.thumbnail.thumbnails")?.takeIf { it.isJsonArray }
                        ?.asJsonArray?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("url")?.asString
                    ?: json.path("microformat.microformatDataRenderer.thumbnail.thumbnails")?.takeIf { it.isJsonArray }
                        ?.asJsonArray?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("url")?.asString
            )

            val playlistId = json.path("microformat.microformatDataRenderer.urlCanonical")
                ?.asString?.substringAfterLast("=")
                ?: header.path("buttons")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.path("menuRenderer.items")?.takeIf { it.isJsonArray }?.asJsonArray }
                    ?.flatten()
                    ?.firstNotNullOfOrNull {
                        it.takeIf { it.isJsonObject }?.asJsonObject
                            ?.path("menuNavigationItemRenderer.navigationEndpoint.watchPlaylistEndpoint.playlistId")?.asString
                    }
                ?: ""

            // Parse songs
            val songContents = json.path(
                "contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents.0.musicShelfRenderer.contents"
            )?.takeIf { it.isJsonArray }?.asJsonArray
                ?: json.path(
                    "contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents.0.musicPlaylistShelfRenderer.contents"
                )?.takeIf { it.isJsonArray }?.asJsonArray
                ?: json.path(
                    "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicShelfRenderer.contents"
                )?.takeIf { it.isJsonArray }?.asJsonArray
                ?: json.path(
                    "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicPlaylistShelfRenderer.contents"
                )?.takeIf { it.isJsonArray }?.asJsonArray

            val songs = songContents?.mapNotNull { item ->
                item.takeIf { it.isJsonObject }?.asJsonObject?.let { parseAlbumSong(it, thumbnail) }
            }?.map { song ->
                if (song.artist.isBlank()) {
                    song.copy(artist = artists.firstOrNull()?.name ?: "Unknown Artist")
                } else song
            } ?: emptyList()

            return AlbumPage(
                album = OnlineAlbum(
                    browseId = browseId,
                    playlistId = playlistId,
                    title = title,
                    artists = artists,
                    year = year,
                    thumbnailUrl = thumbnail
                ),
                songs = songs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing album page", e)
            return null
        }
    }

    private fun parseAlbumSong(item: JsonObject, fallbackThumbnail: String?): OnlineSong? {
        val renderer = item.getAsJsonObject("musicResponsiveListItemRenderer") ?: return null
        try {
            val flexColumns = renderer.getAsJsonArray("flexColumns")
            val title = flexColumns?.getOrNull(0)?.asJsonObject
                ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs.0.text")?.asString ?: return null

            val videoId = renderer.path("overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint.watchEndpoint.videoId")?.asString
                ?: flexColumns.getOrNull(0)?.asJsonObject
                    ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.watchEndpoint.videoId")?.asString
                ?: return null

            val subtitleRuns = flexColumns.getOrNull(1)?.asJsonObject
                ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs")?.asJsonArray
            val artist = subtitleRuns?.joinToString("") { it.asJsonObject.get("text")?.asString ?: "" } ?: ""

            val fixedColumns = renderer.getAsJsonArray("fixedColumns")
            val duration = fixedColumns?.getOrNull(0)?.asJsonObject
                ?.path("musicResponsiveListItemFixedColumnRenderer.text.runs.0.text")?.asString

            val thumbnail = parseThumbnail(renderer) ?: fallbackThumbnail

            return OnlineSong(
                videoId = videoId,
                title = title,
                artist = artist.trim(),
                duration = duration,
                durationMs = parseDuration(duration),
                thumbnailUrl = thumbnail,
                isExplicit = isExplicit(renderer)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse album song: ${e.message}")
            return null
        }
    }

    // ========== Account & Library Parsing ==========

    fun parseAccountProfile(json: JsonObject): UserProfile? {
        try {
            val header = json.path("actions.0.openPopupAction.popup.multiPageMenuRenderer.header.activeAccountHeaderRenderer")?.asJsonObject
                ?: return null

            val name = header.path("accountName.runs.0.text")?.asString 
                ?: header.path("accountName.simpleText")?.asString
                ?: "Unknown User"
            val handle = header.path("channelHandle.runs.0.text")?.asString
                ?: header.path("channelHandle.simpleText")?.asString
            val email = header.path("email.runs.0.text")?.asString
                ?: header.path("email.simpleText")?.asString
            val avatarUrl = header.path("accountPhoto.thumbnails")?.asJsonArray
                ?.lastOrNull()?.asJsonObject?.get("url")?.asString

            return UserProfile(
                name = name,
                handle = handle,
                email = email,
                avatarUrl = getHighResThumbnailUrl(avatarUrl)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing account profile", e)
            return null
        }
    }

    fun parseHistory(json: JsonObject): List<OnlineSong> {
        val songs = mutableListOf<OnlineSong>()
        try {
            val sections = json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents")?.asJsonArray
            sections?.forEach { section ->
                val shelf = section.asJsonObject.getAsJsonObject("musicShelfRenderer")
                shelf?.getAsJsonArray("contents")?.forEach { item ->
                    val searchItem = parseSearchItem(item.asJsonObject)
                    if (searchItem is SearchItem.Song) {
                        songs.add(searchItem.song)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing history", e)
        }
        return songs
    }

    /**
     * Parse lyrics from a browse response for a lyrics browseId (MPLYt...).
     * Returns the plain text lyrics string.
     */
    fun parseLyrics(json: JsonObject): String {
        try {
            // Primary path: musicDescriptionShelfRenderer
            val description = json.path("contents.sectionListRenderer.contents.0.musicDescriptionShelfRenderer.description.runs")?.asJsonArray
            if (description != null && description.size() > 0) {
                return description.joinToString("") { run ->
                    run.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                }
            }

            // Fallback: Try plain text description
            val plainText = json.path("contents.sectionListRenderer.contents.0.musicDescriptionShelfRenderer.description.simpleText")?.asString
            if (!plainText.isNullOrEmpty()) return plainText

            // Ultimate fallback: Search for any text content
            val sections = json.path("contents.sectionListRenderer.contents")?.asJsonArray
            sections?.forEach { section ->
                val renderer = section.asJsonObject
                val messageRenderer = renderer.getAsJsonObject("messageRenderer")
                if (messageRenderer != null) {
                    val text = messageRenderer.path("text.runs.0.text")?.asString
                        ?: messageRenderer.path("text.simpleText")?.asString
                    if (!text.isNullOrEmpty()) return text
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing lyrics", e)
        }
        throw IllegalStateException("No lyrics content found in response")
    }



    fun parseLibraryPlaylists(json: JsonObject): List<OnlinePlaylist> {
        val playlists = mutableListOf<OnlinePlaylist>()
        try {
            val items = json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.musicGridRenderer.items")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.musicGridRenderer.items")?.asJsonArray
                ?: json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicShelfRenderer.contents")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.musicShelfRenderer.contents")?.asJsonArray

            if (items != null) {
                return parseLibraryPlaylistsList(items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing library playlists", e)
        }
        return playlists
    }

    fun parseLibraryPlaylistsList(items: JsonArray): List<OnlinePlaylist> {
        val playlists = mutableListOf<OnlinePlaylist>()
        items.forEach { itemObj ->
            val item = itemObj.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (item.has("musicTwoRowItemRenderer")) {
                val twoRow = item.getAsJsonObject("musicTwoRowItemRenderer")
                try {
                    val title = twoRow.path("title.runs.0.text")?.asString ?: return@forEach
                    val thumbnail = twoRow.path("thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    val browseId = twoRow.path("navigationEndpoint.browseEndpoint.browseId")?.asString ?: return@forEach
                    val runs = twoRow.path("subtitle.runs")?.takeIf { it.isJsonArray }?.asJsonArray
                    val songCountText = runs?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString }
                        ?.findLast { text ->
                            text.any { it.isDigit() } &&
                            !text.contains("view", ignoreCase = true) &&
                            !text.contains("hour", ignoreCase = true) &&
                            !text.contains("minute", ignoreCase = true) &&
                            !text.contains("sec", ignoreCase = true)
                        } ?: ""
                    playlists.add(OnlinePlaylist(
                        playlistId = browseId.removePrefix("VL"),
                        title = title,
                        thumbnailUrl = thumbnail,
                        songCount = songCountText.takeIf { it.isNotBlank() }
                    ))
                } catch (_: Exception) {}
            } else if (item.has("musicResponsiveListItemRenderer")) {
                val listItem = item.getAsJsonObject("musicResponsiveListItemRenderer")
                try {
                    val title = listItem.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.text")?.asString ?: return@forEach
                    val browseId = listItem.path("navigationEndpoint.browseEndpoint.browseId")?.asString
                        ?: listItem.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.browseEndpoint.browseId")?.asString ?: return@forEach
                    val thumbnail = listItem.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    val runs = listItem.path("flexColumns.1.musicResponsiveListItemFlexColumnRenderer.text.runs")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?: listItem.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs")?.takeIf { it.isJsonArray }?.asJsonArray
                    val songCountText = runs?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString }
                        ?.findLast { text ->
                            text.any { it.isDigit() } &&
                            !text.contains("view", ignoreCase = true) &&
                            !text.contains("hour", ignoreCase = true) &&
                            !text.contains("minute", ignoreCase = true) &&
                            !text.contains("sec", ignoreCase = true)
                        } ?: ""
                    playlists.add(OnlinePlaylist(
                        playlistId = browseId.removePrefix("VL"),
                        title = title,
                        thumbnailUrl = thumbnail,
                        songCount = songCountText.takeIf { it.isNotBlank() }
                    ))
                } catch (_: Exception) {}
            }
        }
        return playlists
    }

    fun parseLibraryArtists(json: JsonObject): List<OnlineArtist> {
        val artists = mutableListOf<OnlineArtist>()
        try {
            val items = json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.musicGridRenderer.items")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.musicGridRenderer.items")?.asJsonArray
                ?: json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicShelfRenderer.contents")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.musicShelfRenderer.contents")?.asJsonArray

            if (items != null) {
                return parseLibraryArtistsList(items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing library artists", e)
        }
        return artists
    }

    fun parseLibraryArtistsList(items: JsonArray): List<OnlineArtist> {
        val artists = mutableListOf<OnlineArtist>()
        items.forEach { itemObj ->
            val item = itemObj.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (item.has("musicTwoRowItemRenderer")) {
                val twoRow = item.getAsJsonObject("musicTwoRowItemRenderer")
                try {
                    val title = twoRow.path("title.runs.0.text")?.asString ?: return@forEach
                    val thumbnail = twoRow.path("thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    val browseId = twoRow.path("navigationEndpoint.browseEndpoint.browseId")?.asString ?: return@forEach
                    val subtitle = twoRow.path("subtitle.runs")?.asJsonArray
                        ?.joinToString("") { run ->
                            run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                        } ?: ""
                    artists.add(OnlineArtist(
                        browseId = browseId,
                        title = title,
                        thumbnailUrl = thumbnail,
                        subscriberCount = subtitle
                    ))
                } catch (_: Exception) {}
            } else if (item.has("musicResponsiveListItemRenderer")) {
                val listItem = item.getAsJsonObject("musicResponsiveListItemRenderer")
                try {
                    val title = listItem.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.text")?.asString ?: return@forEach
                    val browseId = listItem.path("navigationEndpoint.browseEndpoint.browseId")?.asString
                        ?: listItem.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.browseEndpoint.browseId")?.asString ?: return@forEach
                    val thumbnail = listItem.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    artists.add(OnlineArtist(
                        browseId = browseId,
                        title = title,
                        thumbnailUrl = thumbnail,
                        subscriberCount = ""
                    ))
                } catch (_: Exception) {}
            }
        }
        return artists
    }

    fun parseLibraryAlbums(json: JsonObject): List<OnlineAlbum> {
        val albums = mutableListOf<OnlineAlbum>()
        try {
            val items = json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.gridRenderer.items")?.asJsonArray
                ?: json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.musicGridRenderer.items")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.musicGridRenderer.items")?.asJsonArray
                ?: json.path("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicShelfRenderer.contents")?.asJsonArray
                ?: json.path("contents.sectionListRenderer.contents.0.musicShelfRenderer.contents")?.asJsonArray

            if (items != null) {
                return parseLibraryAlbumsList(items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing library albums", e)
        }
        return albums
    }

    fun parseLibraryAlbumsList(items: JsonArray): List<OnlineAlbum> {
        val albums = mutableListOf<OnlineAlbum>()
        items.forEach { itemObj ->
            val item = itemObj.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (item.has("musicTwoRowItemRenderer")) {
                val twoRow = item.getAsJsonObject("musicTwoRowItemRenderer")
                try {
                    val title = twoRow.path("title.runs.0.text")?.asString ?: return@forEach
                    val thumbnail = twoRow.path("thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    val browseId = twoRow.path("navigationEndpoint.browseEndpoint.browseId")?.asString ?: return@forEach
                    val subtitle = twoRow.path("subtitle.runs")?.asJsonArray
                        ?.joinToString("") { run ->
                            run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                        } ?: ""
                    albums.add(OnlineAlbum(
                        browseId = browseId,
                        title = title,
                        artists = listOf(OnlineArtistRef(subtitle.split(" • ").drop(1).firstOrNull() ?: subtitle.split(" • ").firstOrNull() ?: "")),
                        thumbnailUrl = thumbnail,
                        year = subtitle.split(" • ").lastOrNull()?.toIntOrNull()
                    ))
                } catch (_: Exception) {}
            } else if (item.has("musicResponsiveListItemRenderer")) {
                val listItem = item.getAsJsonObject("musicResponsiveListItemRenderer")
                try {
                    val title = listItem.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.text")?.asString ?: return@forEach
                    val browseId = listItem.path("navigationEndpoint.browseEndpoint.browseId")?.asString
                        ?: listItem.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.browseEndpoint.browseId")?.asString ?: return@forEach
                    val thumbnail = listItem.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                    albums.add(OnlineAlbum(
                        browseId = browseId,
                        title = title,
                        artists = emptyList(),
                        thumbnailUrl = thumbnail,
                        year = null
                    ))
                } catch (_: Exception) {}
            }
        }
        return albums
    }

    fun extractLibraryContinuation(json: JsonObject): String? {
        val prefix = "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0."
        val prefix2 = "contents.sectionListRenderer.contents.0."
        
        return json.path(prefix + "gridRenderer.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path(prefix + "musicGridRenderer.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path(prefix + "musicShelfRenderer.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path(prefix2 + "gridRenderer.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path(prefix2 + "musicGridRenderer.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path(prefix2 + "musicShelfRenderer.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path("contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.gridRenderer.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path("contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents.0.musicPlaylistShelfRenderer.continuations.0.nextContinuationData.continuation")?.asString
    }

    fun extractContinuationItemsAndNextToken(json: JsonObject): Pair<JsonArray?, String?> {
        val continuationContents = json.getAsJsonObject("continuationContents")
        if (continuationContents != null) {
            val target = continuationContents.getAsJsonObject("gridContinuation")
                ?: continuationContents.getAsJsonObject("musicGridContinuation")
                ?: continuationContents.getAsJsonObject("musicShelfContinuation")
                ?: continuationContents.getAsJsonObject("musicPlaylistShelfContinuation")

            if (target != null) {
                val items = target.getAsJsonArray("items") ?: target.getAsJsonArray("contents")
                val nextToken = target.getAsJsonArray("continuations")
                    ?.getOrNull(0)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.getAsJsonObject("nextContinuationData")
                    ?.get("continuation")?.asString
                return Pair(items, nextToken)
            }
        }
        
        // Fallback for non-standard continuation wrappers
        val gridItems = json.path("continuationContents.gridContinuation.items")?.asJsonArray
            ?: json.path("continuationContents.musicGridContinuation.items")?.asJsonArray
            ?: json.path("continuationContents.musicShelfContinuation.contents")?.asJsonArray
            ?: json.path("continuationContents.musicPlaylistShelfContinuation.contents")?.asJsonArray
            ?: json.path("continuationContents.musicPlaylistShelfContinuation.items")?.asJsonArray
            
        val nextToken = json.path("continuationContents.gridContinuation.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path("continuationContents.musicGridContinuation.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path("continuationContents.musicShelfContinuation.continuations.0.nextContinuationData.continuation")?.asString
            ?: json.path("continuationContents.musicPlaylistShelfContinuation.continuations.0.nextContinuationData.continuation")?.asString
            
        return Pair(gridItems, nextToken)
    }

    // ========== Utility ==========

    private fun parseThumbnail(renderer: JsonObject): String? {
        val url = renderer.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
            ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
        return getHighResThumbnailUrl(url)
    }

    // Statically cached SharedPreferences data saver check to avoid thousands of IPC/disk reads per parse cycle
    private var cachedDataSaver: Boolean? = null
    private var lastDataSaverCheck: Long = 0L

    val isDataSaver: Boolean
        get() {
            val now = System.currentTimeMillis()
            if (now - lastDataSaverCheck > 3000L || cachedDataSaver == null) {
                lastDataSaverCheck = now
                cachedDataSaver = try {
                    NewPipeStreamExtractor.getAppContext()
                        ?.getSharedPreferences("AppSettings", android.content.Context.MODE_PRIVATE)
                        ?.getBoolean("data_saver", false)
                } catch (_: Exception) {
                    false
                }
            }
            return cachedDataSaver ?: false
        }

    /**
     * Replaces YouTube's low-resolution url parameters with high-resolution parameters (1080p)
     * Highly optimized zero-regex implementation using fast linear index scanning.
     */
    fun getHighResThumbnailUrl(url: String?): String? {
        if (url == null) return null

        var finalUrl = url
        if (finalUrl.startsWith("//")) {
            finalUrl = "https:$finalUrl"
        }

        val dataSaver = isDataSaver
        val targetRes = if (dataSaver) "=w540-h540" else "=w1080-h1080"

        // YouTube music album covers usually have a pattern like: =w120-h120-l90-rj
        val wIdx = finalUrl.indexOf("=w")
        if (wIdx != -1) {
            val hIdx = finalUrl.indexOf("-h", wIdx + 2)
            if (hIdx != -1) {
                var endIdx = hIdx + 2
                while (endIdx < finalUrl.length && finalUrl[endIdx].isDigit()) {
                    endIdx++
                }
                val before = finalUrl.substring(0, wIdx)
                val after = finalUrl.substring(endIdx)
                return "$before$targetRes$after"
            }
        }
        
        // Account avatars usually have a pattern like: =s88-c-k-c0x00...
        val sIdx = finalUrl.indexOf("=s")
        if (sIdx != -1) {
            var endIdx = sIdx + 2
            while (endIdx < finalUrl.length && finalUrl[endIdx].isDigit()) {
                endIdx++
            }
            val targetSquareRes = if (dataSaver) "=s540" else "=s1080"
            val before = finalUrl.substring(0, sIdx)
            val after = finalUrl.substring(endIdx)
            return "$before$targetSquareRes$after"
        }

        // For ytimg.com video thumbnails, maxresdefault.jpg often 404s if the video isn't 720p+.
        // hqdefault.jpg (480x360) is guaranteed to exist.
        val targetHqRes = "hqdefault.jpg"
        if (finalUrl.contains("sqdefault.jpg")) return finalUrl.replace("sqdefault.jpg", targetHqRes)
        if (finalUrl.contains("mqdefault.jpg")) return finalUrl.replace("mqdefault.jpg", targetHqRes)

        return finalUrl
    }

    /** Parse duration string like "3:45" to milliseconds with zero-allocation index scanning */
    private fun parseDuration(duration: String?): Long {
        if (duration == null) return 0
        val colon1 = duration.indexOf(':')
        if (colon1 == -1) return 0
        val colon2 = duration.indexOf(':', colon1 + 1)
        return try {
            if (colon2 == -1) {
                val m = duration.substring(0, colon1).trim().toLong()
                val s = duration.substring(colon1 + 1).trim().toLong()
                (m * 60 + s) * 1000
            } else {
                val h = duration.substring(0, colon1).trim().toLong()
                val m = duration.substring(colon1 + 1, colon2).trim().toLong()
                val s = duration.substring(colon2 + 1).trim().toLong()
                (h * 3600 + m * 60 + s) * 1000
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun findAnimatedThumbnailUrl(element: JsonElement): String? {
        if (element.isJsonArray) {
            for (item in element.asJsonArray) {
                val result = findAnimatedThumbnailUrl(item)
                if (result != null) return result
            }
        } else if (element.isJsonObject) {
            val obj = element.asJsonObject
            
            // Specifically look for YouTube Music's animated renderer
            if (obj.has("musicAnimatedThumbnailRenderer")) {
                val animatedRenderer = obj.getAsJsonObject("musicAnimatedThumbnailRenderer")
                val thumbnails = animatedRenderer?.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
                if (thumbnails != null && thumbnails.size() > 0) {
                    return thumbnails.get(0).asJsonObject.get("url")?.asString
                }
            }

            // Look for any thumbnail that is an mp4 or webp
            if (obj.has("thumbnails")) {
                val arr = obj.getAsJsonArray("thumbnails")
                for (i in 0 until arr.size()) {
                    val url = arr.get(i).asJsonObject.get("url")?.asString
                    if (url != null && (url.contains(".mp4") || url.contains("sqp="))) {
                        return url
                    }
                }
            }

            for ((_, value) in obj.entrySet()) {
                val result = findAnimatedThumbnailUrl(value)
                if (result != null) return result
            }
        }
        return null
    }

    private fun getCleanArtist(subtitleText: String): String {
        val ignoredTypes = setOf("song", "video", "single", "ep", "album", "artist", "playlist")
        val parts = subtitleText.split(" • ", "   ").map { it.trim() }
        val cleanParts = parts.filter { it.lowercase() !in ignoredTypes && it.isNotEmpty() && !it.matches(Regex("(\\d+:)?\\d+:\\d+")) }
        return cleanParts.firstOrNull() ?: "Unknown Artist"
    }

    private fun parseQueryString(query: String): Map<String, String> {
        return try {
            query.split("&").mapNotNull { pairStr ->
                val pair = pairStr.split("=", limit = 2)
                if (pair.size == 2) {
                    val key = java.net.URLDecoder.decode(pair[0], "UTF-8")
                    val value = java.net.URLDecoder.decode(pair[1], "UTF-8")
                    key to value
                } else null
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }
}

// ========== JSON Navigation Extension ==========

// High-performance statically allocated cache to eliminate thousands of string splits and GC sweeps per parse cycle
private val jsonPathCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

fun JsonElement?.path(path: String): JsonElement? {
    if (this == null) return null
    var current: JsonElement? = this
    
    // Atomically fetch pre-digested path fragments (Instant retrieval, 0 garbage generated)
    val keys = jsonPathCache.computeIfAbsent(path) { it.split(".") }
    
    for (key in keys) {
        if (current == null) return null
        current = when {
            current.isJsonObject -> current.asJsonObject.get(key)
            current.isJsonArray -> {
                val index = key.toIntOrNull()
                if (index != null) current.asJsonArray.getOrNull(index)
                else null
            }
            else -> null
        }
    }
    return current
}

/** Safe getOrNull for JsonArray */
fun JsonArray.getOrNull(index: Int): JsonElement? {
    return if (index in 0 until size()) get(index) else null
}

fun isExplicit(renderer: JsonObject?): Boolean {
    if (renderer == null) return false
    val badges = renderer.getAsJsonArray("badges") ?: renderer.path("badges")?.asJsonArray ?: return false
    for (i in 0 until badges.size()) {
        val badge = badges.get(i)
        if (!badge.isJsonObject) continue
        val badgeObj = badge.asJsonObject
        val inlineBadge = badgeObj.getAsJsonObject("musicInlineBadgeRenderer")
        val metaBadge = badgeObj.getAsJsonObject("metadataBadgeRenderer")
        val target = inlineBadge ?: metaBadge ?: continue

        val iconType = target.getAsJsonObject("icon")?.get("iconType")?.asString
        if (iconType != null && iconType.contains("EXPLICIT", ignoreCase = true)) {
            return true
        }

        val label = target.getAsJsonObject("accessibility")
            ?.getAsJsonObject("accessibilityData")
            ?.get("label")?.asString
            ?: target.getAsJsonObject("accessibilityData")
                ?.get("label")?.asString
        if (label != null && label.contains("explicit", ignoreCase = true)) {
            return true
        }
    }
    return false
}

/**
 * Fast-path zero-regex CDN URL resizer using direct index scanning.
 * Eliminates thousands of Regex Matcher allocations during list scroll and parsing.
 */
fun String.resize(width: Int? = null, height: Int? = null): String {
    if (width == null && height == null) return this
    val isGoogleCdn = contains("googleusercontent.com") || contains("ggpht.com")
    if (!isGoogleCdn) return this

    val w = width ?: height!!
    val h = height ?: width!!

    // Fast check for "=w" param
    val wIdx = indexOf("=w")
    if (wIdx != -1) {
        val base = substring(0, wIdx)
        return "$base=w$w-h$h-p-l90-rj"
    }

    // Fast check for "=s" param
    val sIdx = indexOf("=s")
    if (sIdx != -1) {
        val size = maxOf(w, h)
        val base = substring(0, sIdx)
        var endOfDigits = sIdx + 2
        while (endOfDigits < length && this[endOfDigits].isDigit()) {
            endOfDigits++
        }
        val remainder = if (endOfDigits < length) {
            val after = substring(endOfDigits)
            val brokenIdx = after.indexOf("-c-k-c0x")
            if (brokenIdx != -1) {
                var brokenEnd = brokenIdx + 8
                while (brokenEnd < after.length && after[brokenEnd].isLetterOrDigit()) {
                    brokenEnd++
                }
                after.substring(0, brokenIdx) + after.substring(brokenEnd)
            } else {
                after
            }
        } else ""
        return "$base=s$size$remainder"
    }

    // Path-based /wXXX-hXXX/ check without Regex
    val wSlash = indexOf("/w")
    if (wSlash != -1) {
        val dashH = indexOf("-h", wSlash + 2)
        if (dashH != -1) {
            val slashAfter = indexOf('/', dashH + 2)
            if (slashAfter != -1) {
                val before = substring(0, wSlash + 1)
                val after = substring(slashAfter)
                return "${before}w$w-h$h$after"
            }
        }
    }

    return this
}

// ========== Fast-Path Direct JSON Node Extractors ==========

fun JsonObject?.extractFirstRunText(fieldName: String = "title"): String? {
    if (this == null) return null
    val container = getAsJsonObject(fieldName) ?: return null
    val runs = container.getAsJsonArray("runs") ?: return container.get("simpleText")?.asString
    if (runs.isEmpty) return null
    return runs.get(0).takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString
}

fun JsonObject?.extractRunsText(fieldName: String = "title"): String? {
    if (this == null) return null
    val container = getAsJsonObject(fieldName) ?: return null
    val runs = container.getAsJsonArray("runs") ?: return container.get("simpleText")?.asString
    if (runs.isEmpty) return null
    if (runs.size() == 1) {
        return runs.get(0).takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString
    }
    val sb = StringBuilder()
    for (i in 0 until runs.size()) {
        val run = runs.get(i)
        if (run.isJsonObject) {
            run.asJsonObject.get("text")?.asString?.let { sb.append(it) }
        }
    }
    return sb.takeIf { it.isNotEmpty() }?.toString()
}

fun JsonObject?.extractThumbnailUrl(): String? {
    if (this == null) return null
    val thumbContainer = getAsJsonObject("thumbnail") ?: return null
    val inner = thumbContainer.getAsJsonObject("musicThumbnailRenderer") ?: thumbContainer
    val thumbnails = inner.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
        ?: inner.getAsJsonArray("thumbnails")
        ?: return null
    val last = thumbnails.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
    return last?.get("url")?.asString
}

fun JsonObject?.extractBrowseId(): String? {
    if (this == null) return null
    val ep = getAsJsonObject("navigationEndpoint")
        ?: getAsJsonObject("clickCommand")
        ?: getAsJsonObject("browseEndpoint")
        ?: return null
    val browseEp = ep.getAsJsonObject("browseEndpoint") ?: ep
    return browseEp.get("browseId")?.asString
}

fun JsonObject?.extractVideoId(): String? {
    if (this == null) return null
    val ep = getAsJsonObject("navigationEndpoint")
        ?: getAsJsonObject("clickCommand")
        ?: getAsJsonObject("watchEndpoint")
        ?: return null
    val watchEp = ep.getAsJsonObject("watchEndpoint") ?: ep
    return watchEp.get("videoId")?.asString
}
