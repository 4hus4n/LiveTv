package com.ahusan.livetv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.load

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var playerView: PlayerView
    private lateinit var spinner: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var info: View
    private lateinit var infoNumber: TextView
    private lateinit var infoName: TextView
    private lateinit var infoGroup: TextView
    private lateinit var infoStar: TextView
    private lateinit var infoLogo: ImageView

    private var player: ExoPlayer? = null
    private var index = 0
    private var triedHls = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val startPending = Runnable { startCurrent(false) }

    private val channels get() = Session.playList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)

        if (channels.isEmpty()) {
            finish()
            return
        }
        index = Session.index.coerceIn(0, channels.size - 1)

        playerView = findViewById(R.id.playerView)
        spinner = findViewById(R.id.spinner)
        errorText = findViewById(R.id.error)
        info = findViewById(R.id.info)
        infoNumber = findViewById(R.id.infoNumber)
        infoName = findViewById(R.id.infoName)
        infoGroup = findViewById(R.id.infoGroup)
        infoStar = findViewById(R.id.infoStar)
        infoLogo = findViewById(R.id.infoLogo)
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        val p = ExoPlayer.Builder(this).build()
        p.addListener(listener)
        playerView.player = p
        player = p
        showInfo()
        startCurrent(false)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (::playerView.isInitialized) playerView.player = null
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) errorText.visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }
            // Many IPTV links are HLS without ".m3u8" in the URL – retry as HLS once.
            if (!triedHls && guessMime(channels[index].url) == null) {
                startCurrent(true)
                return
            }
            spinner.visibility = View.GONE
            errorText.text = "Channel not available\n(${error.errorCodeName})"
            errorText.visibility = View.VISIBLE
            showInfo()
        }
    }

    private fun guessMime(url: String): String? {
        val u = url.lowercase().substringBefore('?')
        return when {
            u.endsWith(".m3u8") || u.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            u.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private fun startCurrent(forceHls: Boolean) {
        val p = player ?: return
        val ch = channels[index]
        triedHls = forceHls
        errorText.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        Session.index = index
        Session.lastPlayedKey = ch.key

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

        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(item.build())
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    private fun zap(delta: Int) {
        if (channels.size < 2) return
        index = (index + delta).mod(channels.size)
        player?.stop()
        errorText.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        showInfo()
        // Wait a moment so fast up/down presses don't start every channel on the way
        handler.removeCallbacks(startPending)
        handler.postDelayed(startPending, 350)
    }

    private fun showInfo() {
        val ch = channels[index]
        infoNumber.text = (index + 1).toString()
        infoName.text = ch.name
        infoGroup.text = ch.group
        infoStar.visibility = if (prefs.isFav(ch)) View.VISIBLE else View.GONE
        if (!ch.logo.isNullOrBlank()) {
            infoLogo.visibility = View.VISIBLE
            infoLogo.load(ch.logo)
        } else {
            infoLogo.visibility = View.GONE
        }
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun toggleFav() {
        val ch = channels[index]
        val added = prefs.toggleFav(ch)
        Toast.makeText(
            this,
            if (added) "★ Added to favourites" else "Removed from favourites",
            Toast.LENGTH_SHORT
        ).show()
        showInfo()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showInfo(); return true
            }
            KeyEvent.KEYCODE_MENU -> { toggleFav(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            toggleFav()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (!event.isCanceled) {
                if (errorText.visibility == View.VISIBLE) startCurrent(false) else showInfo()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
