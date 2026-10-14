package com.ahusan.livetv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

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

    private var allChannels: List<Channel> = emptyList()
    private var currentGroup = GROUP_ALL
    private var loadedUrl: String? = null
    private var returningFromPlayer = false

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

        groupAdapter = GroupAdapter { g ->
            groupAdapter.select(g)
            showGroup(g)
        }
        groupsView.layoutManager = LinearLayoutManager(this)
        groupsView.adapter = groupAdapter
        groupsView.itemAnimator = null

        channelAdapter = ChannelAdapter(prefs, ::play, ::toggleFav)
        channelsView.layoutManager = GridLayoutManager(this, 4)
        channelsView.adapter = channelAdapter
        channelsView.itemAnimator = null

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener { load() }

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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            if (focused != null) {
                val pos = (focused.parent as? RecyclerView)?.takeIf { it === channelsView }
                    ?.getChildAdapterPosition(focused) ?: RecyclerView.NO_POSITION
                if (pos != RecyclerView.NO_POSITION) {
                    toggleFav(pos)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun load() {
        val url = prefs.playlistUrl
        loading.visibility = View.VISIBLE
        status.text = "Loading playlist…"
        lifecycleScope.launch {
            val result = PlaylistRepository.load(this@MainActivity, url)
            loading.visibility = View.GONE
            loadedUrl = url
            allChannels = result.channels
            status.text = when {
                result.channels.isEmpty() -> "Couldn't load playlist"
                result.fromCache -> "Offline · saved playlist (${result.channels.size})"
                else -> "${result.channels.size} channels"
            }
            if (result.channels.isEmpty()) {
                groupAdapter.submit(emptyList(), GROUP_ALL)
                channelAdapter.submit(emptyList())
                groupTitle.text = ""
                empty.text = "Couldn't load the playlist.\n${result.error ?: ""}\n\nCheck the URL in Settings, then press Reload."
                empty.visibility = View.VISIBLE
                findViewById<Button>(R.id.btnSettings).requestFocus()
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
        groupTitle.text = "${groupLabel(g)}  ·  ${list.size}"
        if (list.isEmpty()) {
            empty.text = if (g == GROUP_FAV)
                "No favourites yet.\nHold OK (or press Menu) on a channel to add it."
            else "No channels in this group."
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun play(position: Int) {
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
            if (added) "★ Added ${ch.name} to favourites" else "Removed ${ch.name} from favourites",
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
