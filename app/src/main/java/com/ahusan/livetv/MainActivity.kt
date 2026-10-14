package com.ahusan.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var groupsView: RecyclerView
    private lateinit var channelsView: RecyclerView
    private lateinit var groupTitle: TextView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    // Hero
    private lateinit var heroLive: TextView
    private lateinit var heroGroup: TextView
    private lateinit var heroName: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroInitials: TextView
    private lateinit var heroStar: ImageView
    private lateinit var previewBox: View
    private lateinit var previewView: PlayerView

    private var allChannels: List<Channel> = emptyList()
    private var currentGroup = GROUP_ALL
    private var loadedUrl: String? = null
    private var returningFromPlayer = false

    // Live preview
    private val handler = Handler(Looper.getMainLooper())
    private var preview: ExoPlayer? = null
    private var previewChannel: Channel? = null
    private var previewTriedHls = false
    private val previewRunnable = Runnable { startPreview(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        groupTitle = findViewById(R.id.groupTitle)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        heroLive = findViewById(R.id.heroLive)
        heroGroup = findViewById(R.id.heroGroup)
        heroName = findViewById(R.id.heroName)
        heroMeta = findViewById(R.id.heroMeta)
        heroLogo = findViewById(R.id.heroLogo)
        heroInitials = findViewById(R.id.heroInitials)
        heroStar = findViewById(R.id.heroStar)
        previewBox = findViewById(R.id.previewBox)
        previewView = findViewById(R.id.preview)
        previewBox.clipToOutline = true
        heroLive.visibility = View.INVISIBLE
        heroGroup.visibility = View.INVISIBLE

        groupAdapter = GroupAdapter { g ->
            groupAdapter.select(g)
            showGroup(g)
        }
        groupsView.layoutManager = LinearLayoutManager(this)
        groupsView.adapter = groupAdapter
        groupsView.itemAnimator = null

        channelAdapter = ChannelAdapter(prefs, ::play, ::toggleFav, ::onChannelFocused)
        channelsView.layoutManager = GridLayoutManager(this, 4)
        channelsView.adapter = channelAdapter
        channelsView.itemAnimator = null

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { load() }

        load()
    }

    override fun onResume() {
        super.onResume()
        if (loadedUrl != null && loadedUrl != prefs.playlistUrl) {
            load()
            return
        }
        if (returningFromPlayer) {
            returningFromPlayer = false
            // Favourites may have changed in the player
            if (currentGroup == GROUP_FAV) showGroup(GROUP_FAV) else channelAdapter.notifyDataSetChanged()
            focusChannel(Session.lastPlayedKey)
        }
    }

    override fun onStop() {
        super.onStop()
        releasePreview()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            if (focused != null && focused.parent === channelsView) {
                val pos = channelsView.getChildAdapterPosition(focused)
                if (pos != RecyclerView.NO_POSITION) {
                    toggleFav(pos)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Playlist ----------

    private fun load() {
        val url = prefs.playlistUrl
        releasePreview()
        loading.visibility = View.VISIBLE
        status.text = "Loading playlist…"
        lifecycleScope.launch {
            val result = PlaylistRepository.load(this@MainActivity, url)
            loading.visibility = View.GONE
            loadedUrl = url
            allChannels = result.channels
            status.text = when {
                result.channels.isEmpty() -> "Couldn't load playlist"
                result.fromCache -> "Offline · saved playlist"
                else -> "${result.channels.size} channels"
            }
            if (result.channels.isEmpty()) {
                groupAdapter.submit(emptyList(), GROUP_ALL)
                channelAdapter.submit(emptyList())
                groupTitle.text = ""
                heroName.text = "No channels"
                heroMeta.text = ""
                empty.text = "Couldn't load the playlist.\n${result.error ?: ""}\n\nCheck the URL in Settings, then press Reload."
                empty.visibility = View.VISIBLE
                findViewById<View>(R.id.btnSettings).requestFocus()
                return@launch
            }
            if (result.fromCache) {
                Toast.makeText(this@MainActivity, "Using saved playlist: ${result.error}", Toast.LENGTH_LONG).show()
            }
            buildGroups()
        }
    }

    private fun buildGroups() {
        val groups = listOf(GROUP_FAV, GROUP_ALL) + allChannels.map { it.group }.distinct()
        val saved = prefs.lastGroup
        val sel = when {
            saved != null && saved in groups && (saved != GROUP_FAV || prefs.hasFavs()) -> saved
            prefs.hasFavs() -> GROUP_FAV
            else -> GROUP_ALL
        }
        groupAdapter.submit(groups, sel)
        showGroup(sel)
        val pos = groups.indexOf(sel)
        groupsView.scrollToPosition(pos)
        groupsView.post { groupsView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun channelsFor(g: String): List<Channel> = when (g) {
        GROUP_FAV -> allChannels.filter { prefs.isFav(it) }
        GROUP_ALL -> allChannels
        else -> allChannels.filter { it.group == g }
    }

    private fun showGroup(g: String) {
        currentGroup = g
        prefs.lastGroup = g
        val list = channelsFor(g)
        channelAdapter.submit(list)
        channelsView.scrollToPosition(0)
        groupTitle.text = groupLabel(g)
        status.text = "${list.size} channels"
        if (list.isEmpty()) {
            empty.text = if (g == GROUP_FAV)
                "No favourites yet.\nHold OK (or press Menu) on a channel to add it."
            else "No channels in this group."
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            bindHero(list[0], 0)
        }
    }

    // ---------- Hero + preview ----------

    private fun bindHero(ch: Channel, pos: Int) {
        heroLive.visibility = View.VISIBLE
        heroGroup.visibility = View.VISIBLE
        heroGroup.text = ch.group
        heroName.text = ch.name
        heroMeta.text = "Channel ${pos + 1} of ${channelAdapter.itemCount}"
        heroStar.visibility = if (prefs.isFav(ch)) View.VISIBLE else View.GONE
        showLogo(heroLogo, heroInitials, ch)
    }

    private fun onChannelFocused(pos: Int) {
        val ch = channelAdapter.items.getOrNull(pos) ?: return
        bindHero(ch, pos)
        if (ch == previewChannel && preview?.isPlaying == true) return
        stopPreview()
        if (!prefs.livePreview) return
        previewChannel = ch
        // Only start once the user rests on a channel
        handler.postDelayed(previewRunnable, 1000)
    }

    private val previewListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            previewView.animate().alpha(1f).setDuration(250).start()
        }

        override fun onPlayerError(error: PlaybackException) {
            val ch = previewChannel ?: return
            if (!previewTriedHls && Streams.guessMime(ch.url) == null) startPreview(true)
            else previewView.alpha = 0f
        }
    }

    private fun startPreview(forceHls: Boolean) {
        val ch = previewChannel ?: return
        val p = preview ?: ExoPlayer.Builder(this).build().also {
            it.volume = 0f
            it.addListener(previewListener)
            previewView.player = it
            preview = it
        }
        previewTriedHls = forceHls
        previewView.alpha = 0f
        p.setMediaSource(Streams.source(ch, forceHls))
        p.prepare()
        p.playWhenReady = true
    }

    private fun stopPreview() {
        handler.removeCallbacks(previewRunnable)
        preview?.stop()
        previewView.animate().cancel()
        previewView.alpha = 0f
    }

    private fun releasePreview() {
        stopPreview()
        preview?.release()
        preview = null
        previewView.player = null
        previewChannel = null
    }

    // ---------- Actions ----------

    private fun play(position: Int) {
        releasePreview()
        Session.playList = channelAdapter.items
        Session.index = position
        returningFromPlayer = true
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFav(position: Int) {
        val ch = channelAdapter.items.getOrNull(position) ?: return
        val added = prefs.toggleFav(ch)
        Toast.makeText(
            this,
            if (added) "Added ${ch.name} to favourites" else "Removed ${ch.name} from favourites",
            Toast.LENGTH_SHORT
        ).show()
        if (currentGroup == GROUP_FAV) {
            showGroup(GROUP_FAV)
            val next = position.coerceAtMost(channelAdapter.itemCount - 1)
            if (next >= 0) channelsView.post {
                channelsView.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus()
            }
        } else {
            channelAdapter.notifyItemChanged(position)
            heroStar.visibility = if (prefs.isFav(ch)) View.VISIBLE else View.GONE
        }
    }

    private fun focusChannel(key: String?) {
        if (key == null) return
        val pos = channelAdapter.items.indexOfFirst { it.key == key }
        if (pos < 0) return
        channelsView.scrollToPosition(pos)
        channelsView.post {
            channelsView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }
}
