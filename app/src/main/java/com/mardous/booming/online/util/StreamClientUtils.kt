package com.mardous.booming.online.util

import okhttp3.Request

/**
 * Shared utility for attaching the correct User-Agent, Origin, and Referer headers
 * to audio streaming requests based on the client query parameter (`c`) in GoogleVideo URLs
 * to prevent HTTP 403 Forbidden.
 */
object StreamClientUtils {

    const val USER_AGENT_ANDROID_VR =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    const val USER_AGENT_ANDROID =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"

    const val USER_AGENT_IOS =
        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"

    const val USER_AGENT_VISIONOS =
        "com.google.ios.youtube/1.2.1 (Apple Vision Pro; visionOS 1.2.1)"

    const val USER_AGENT_WEB_REMIX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    const val USER_AGENT_MWEB =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    const val USER_AGENT_TVHTML5 =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"

    const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
    const val REFERER_YOUTUBE_MUSIC = "https://music.youtube.com/"
    const val ORIGIN_YOUTUBE = "https://www.youtube.com"
    const val REFERER_YOUTUBE = "https://www.youtube.com/"
    const val ORIGIN_YOUTUBE_MOBILE = "https://m.youtube.com"
    const val REFERER_YOUTUBE_MOBILE = "https://m.youtube.com/"
    const val REFERER_YOUTUBE_TV = "https://www.youtube.com/tv"

    fun applyRequestProfile(builder: Request.Builder, request: Request): Request.Builder {
        val url = request.url
        val host = url.host

        // Only profile googlevideo and YouTube streaming endpoints
        if (!host.contains("googlevideo.com") && !host.contains("youtube.com") && !host.contains("googleusercontent.com")) {
            if (request.header("User-Agent").isNullOrBlank()) {
                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            }
            return builder
        }

        val clientParam = url.queryParameter("c")?.uppercase() ?: ""

        when {
            clientParam.startsWith("ANDROID_VR") -> {
                builder.header("User-Agent", USER_AGENT_ANDROID_VR)
                builder.removeHeader("Origin")
                builder.removeHeader("Referer")
            }
            clientParam.startsWith("ANDROID") -> {
                builder.header("User-Agent", USER_AGENT_ANDROID)
                builder.removeHeader("Origin")
                builder.removeHeader("Referer")
            }
            clientParam.startsWith("IOS") -> {
                builder.header("User-Agent", USER_AGENT_IOS)
                builder.removeHeader("Origin")
                builder.removeHeader("Referer")
            }
            clientParam.startsWith("VISIONOS") -> {
                builder.header("User-Agent", USER_AGENT_VISIONOS)
                builder.removeHeader("Origin")
                builder.removeHeader("Referer")
            }
            clientParam.startsWith("TVHTML5") -> {
                builder.header("User-Agent", USER_AGENT_TVHTML5)
                builder.header("Origin", ORIGIN_YOUTUBE)
                builder.header("Referer", REFERER_YOUTUBE_TV)
            }
            clientParam == "MWEB" -> {
                builder.header("User-Agent", USER_AGENT_MWEB)
                builder.header("Origin", ORIGIN_YOUTUBE_MOBILE)
                builder.header("Referer", REFERER_YOUTUBE_MOBILE)
            }
            clientParam == "WEB_REMIX" -> {
                builder.header("User-Agent", USER_AGENT_WEB_REMIX)
                builder.header("Origin", ORIGIN_YOUTUBE_MUSIC)
                builder.header("Referer", REFERER_YOUTUBE_MUSIC)
            }
            clientParam == "WEB" -> {
                builder.header("User-Agent", USER_AGENT_WEB)
                builder.header("Origin", ORIGIN_YOUTUBE)
                builder.header("Referer", REFERER_YOUTUBE)
            }
            else -> {
                // Unknown or missing 'c' googlevideo requests MUST NOT send Origin/Referer!
                builder.header("User-Agent", USER_AGENT_ANDROID_VR)
                builder.removeHeader("Origin")
                builder.removeHeader("Referer")
            }
        }

        return builder
    }
}
