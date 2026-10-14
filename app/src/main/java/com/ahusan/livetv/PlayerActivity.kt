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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

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
    private lateinit var infoNext: TextView
    private lateinit var infoStar: ImageView
    private lateinit var infoLogo: ImageView
    private lateinit var infoInitials: TextView

    private var player: ExoPlayer? = null
    private var index = 0
    private var triedHls = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable {
        info.animate().alpha(0f).setDuration(300).withEndAction { info.visibility = View.GONE }.start()
    }
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
        infoNext = findViewById(R.id.infoNext)
        infoStar = findViewById(R.id.infoStar)
        infoLogo = findViewById(R.id.infoLogo)
        infoInitials = findViewById(R.id.infoInitials)
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
            if (!triedHls && Streams.guessMime(channels[index].url) == null) {
                startCurrent(true)
                return
            }
            spinner.visibility = View.GONE
            errorText.text = "This channel isn't available right now\nPress OK to retry · ▲▼ to change channel"
            errorText.visibility = View.VISIBLE
            showInfo()
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
        p.setMediaSource(Streams.source(ch, forceHls))
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
        infoNumber.text = "CH ${index + 1}"
        infoName.text = ch.name
        infoGroup.text = ch.group
        infoStar.visibility = if (prefs.isFav(ch)) View.VISIBLE else View.GONE
        if (channels.size > 1) {
            val prev = channels[(index - 1).mod(channels.size)].name
            val next = channels[(index + 1).mod(channels.size)].name
            infoNext.text = "▲  $prev        ▼  $next"
        } else {
            infoNext.text = ""
        }
        showLogo(infoLogo, infoInitials, ch)

        info.animate().cancel()
        info.alpha = 1f
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4500)
    }

    private fun toggleFav() {
        val ch = channels[index]
        val added = prefs.toggleFav(ch)
        Toast.makeText(
            this,
            if (added) "Added to favourites" else "Removed from favourites",
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
