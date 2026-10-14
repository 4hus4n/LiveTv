package com.ahusan.livetv

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/** Builds playable sources for channels; shared by the player and the live preview. */
@OptIn(UnstableApi::class)
object Streams {

    fun guessMime(url: String): String? {
        val u = url.lowercase().substringBefore('?')
        return when {
            u.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            u.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun source(ch: Channel, forceHls: Boolean): MediaSource {
        val props = HashMap(ch.headers)
        ch.referrer?.let { props["Referer"] = it }
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(props)

        val item = MediaItem.Builder().setUri(ch.url)
        val mime = if (forceHls) MimeTypes.APPLICATION_M3U8 else guessMime(ch.url)
        if (mime != null) item.setMimeType(mime)
        return DefaultMediaSourceFactory(dataSource).createMediaSource(item.build())
    }
}
