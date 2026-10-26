package com.mardous.booming.data.remote.lyrics.api.paxsenix

import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.userAgent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared client for the lyrics.paxsenix.org endpoints (Spotify, YouTube
 * captions and NetEase providers). Handles the app + browser user-agent
 * fallback and unwraps the loose JSON/text responses those endpoints return.
 */
object PaxsenixClient {

    private const val BASE_URL = "https://lyrics.paxsenix.org"
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun request(
        client: HttpClient,
        path: String,
        params: List<Pair<String, String>> = emptyList()
    ): String? {
        for (userAgent in listOf(SIMPLE_USER_AGENT, FALLBACK_USER_AGENT)) {
            try {
                val response = client.get(BASE_URL + path) {
                    header(HttpHeaders.Accept, "application/json, text/plain, */*")
                    userAgent(userAgent)
                    timeout {
                        connectTimeoutMillis = 5000
                        socketTimeoutMillis = 10000
                        requestTimeoutMillis = 15000
                    }
                    for ((name, value) in params) parameter(name, value)
                }
                if (response.status == HttpStatusCode.OK) {
                    val text = response.bodyAsText()
                    if (text.isNotBlank() && text.trim() != "null") return text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the next user agent.
            }
        }
        return null
    }

    /**
     * Unwraps a lyrics response that may be a raw LRC body, a JSON string or
     * a JSON object carrying the content in one of several fields.
     */
    fun extractLyrics(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "null") return null
        if (trimmed.startsWith("[") && !trimmed.startsWith("[{")) return trimmed

        return try {
            val element = Json.parseToJsonElement(trimmed)
            when {
                element is JsonObject -> {
                    val ok = element["ok"]?.jsonPrimitive?.booleanOrNull
                    if (ok == false) {
                        null
                    } else {
                        listOf("syncedLyrics", "lyrics", "lrc", "content").firstNotNullOfOrNull { key ->
                            element[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        }
                    }
                }
                element is JsonPrimitive && element.isString -> element.content.takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (_: Exception) {
            trimmed.takeIf { it.isNotBlank() }
        }
    }

    /** First "id" field found in a search result (array, tracks.items, data or object). */
    fun extractFirstId(body: String): String? = runCatching {
        val element = Json.parseToJsonElement(body)
        when (element) {
            is JsonArray ->
                (element.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            is JsonObject -> {
                val tracksItems = (element["tracks"] as? JsonObject)?.get("items") as? JsonArray
                (tracksItems?.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: (element["data"] as? JsonArray)?.firstOrNull()
                        ?.let { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
                    ?: element["id"]?.jsonPrimitive?.contentOrNull
            }
            else -> null
        }
    }.getOrNull()
}
