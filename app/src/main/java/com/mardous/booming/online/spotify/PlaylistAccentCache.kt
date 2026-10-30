package com.mardous.booming.online.spotify

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object PlaylistAccentCache {
    private const val FILE_NAME = "accents.json"
    private val json = Json { ignoreUnknownKeys = true }
    private var memory: MutableMap<String, Int>? = null

    private fun file(context: Context): File =
        File(File(context.filesDir, "playlist_cache").apply { mkdirs() }, FILE_NAME)

    private fun map(context: Context): MutableMap<String, Int> {
        memory?.let { return it }
        val loaded = runCatching {
            val f = file(context)
            if (f.exists()) json.decodeFromString<MutableMap<String, Int>>(f.readText())
            else mutableMapOf()
        }.getOrDefault(mutableMapOf())
        memory = loaded
        return loaded
    }

    fun get(context: Context, key: String): Int? = map(context)[key].takeIf { it != 0 }

    fun put(context: Context, key: String, accent: Int) {
        if (accent == 0) return
        map(context)[key] = accent
        runCatching { file(context).writeText(json.encodeToString(map(context))) }
    }
}
