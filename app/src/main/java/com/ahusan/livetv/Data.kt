package com.ahusan.livetv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

const val DEFAULT_PLAYLIST = "https://hilaytv.xyz/play.m3u"
const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String?,
    val referrer: String?,
    val headers: Map<String, String>,
) {
    /** Stable id for favourites (stream URLs often carry changing tokens). */
    val key: String get() = "$group|$name"
}

/** Shared between the channel list and the player (avoids passing big lists in intents). */
object Session {
    var playList: List<Channel> = emptyList()
    var index: Int = 0
    var lastPlayedKey: String? = null
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("livetv", Context.MODE_PRIVATE)
    private val favs: MutableSet<String> = HashSet(sp.getStringSet("favs", emptySet()) ?: emptySet())

    var playlistUrl: String
        get() = sp.getString("playlist_url", null) ?: DEFAULT_PLAYLIST
        set(value) = sp.edit().putString("playlist_url", value).apply()

    var lastGroup: String?
        get() = sp.getString("last_group", null)
        set(value) = sp.edit().putString("last_group", value).apply()

    var livePreview: Boolean
        get() = sp.getBoolean("live_preview", true)
        set(value) = sp.edit().putBoolean("live_preview", value).apply()

    fun isFav(ch: Channel) = ch.key in favs

    fun hasFavs() = favs.isNotEmpty()

    /** Returns true if added, false if removed. */
    fun toggleFav(ch: Channel): Boolean {
        val added = if (ch.key in favs) { favs.remove(ch.key); false } else { favs.add(ch.key); true }
        sp.edit().putStringSet("favs", HashSet(favs)).apply()
        return added
    }
}

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var extGroup: String? = null
        var ua: String? = null
        var ref: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = titleOf(line).ifBlank { attrs["tvg-name"].orEmpty() }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    ua = attrs["user-agent"] ?: attrs["http-user-agent"]
                    ref = attrs["http-referrer"] ?: attrs["referrer"] ?: attrs["referer"]
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    extGroup = line.substring(8).trim().ifBlank { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val parts = line.substring(11).trim().split("=", limit = 2)
                    val value = parts.getOrElse(1) { "" }.trim()
                    when (parts[0].trim().lowercase()) {
                        "http-user-agent" -> ua = value
                        "http-referrer", "http-referer" -> ref = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    var url = line
                    val headers = LinkedHashMap<String, String>()
                    val pipe = url.indexOf('|')
                    if (pipe > 0) {
                        url.substring(pipe + 1).split('&').forEach { kv ->
                            val p = kv.split('=', limit = 2)
                            if (p.size == 2 && p[0].isNotBlank()) headers[p[0].trim()] = decode(p[1])
                        }
                        url = url.substring(0, pipe)
                    }
                    val headerUa = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }
                    if (headerUa != null) {
                        headers.remove(headerUa.key)
                        if (ua == null) ua = headerUa.value
                    }
                    val headerRef = headers.entries.firstOrNull {
                        it.key.equals("Referer", true) || it.key.equals("Referrer", true)
                    }
                    if (headerRef != null) {
                        headers.remove(headerRef.key)
                        if (ref == null) ref = headerRef.value
                    }

                    val finalName = name?.takeIf { it.isNotBlank() }
                        ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url }
                    out += Channel(finalName, url, logo, group ?: extGroup ?: "Other", ua, ref, headers)

                    name = null; logo = null; group = null; extGroup = null; ua = null; ref = null
                }
            }
        }
        return out
    }

    /** Text after the first comma that is not inside quotes. */
    private fun titleOf(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return ""
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}

class LoadResult(val channels: List<Channel>, val fromCache: Boolean, val error: String?)

object PlaylistRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun load(context: Context, url: String): LoadResult = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, "playlist.m3u")
        val cacheUrl = File(context.filesDir, "playlist.url")
        try {
            val request = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val list = M3uParser.parse(body)
                if (list.isEmpty()) throw IOException("No channels found in playlist")
                cache.writeText(body)
                cacheUrl.writeText(url)
                LoadResult(list, false, null)
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (cache.exists() && cacheUrl.exists() && cacheUrl.readText() == url) {
                LoadResult(M3uParser.parse(cache.readText()), true, msg)
            } else {
                LoadResult(emptyList(), false, msg)
            }
        }
    }
}
