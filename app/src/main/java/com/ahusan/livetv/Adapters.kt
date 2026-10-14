package com.ahusan.livetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

const val GROUP_FAV = "__fav__"
const val GROUP_ALL = "__all__"

fun groupLabel(g: String) = when (g) {
    GROUP_FAV -> "★  Favourites"
    GROUP_ALL -> "All channels"
    else -> g
}

fun initialsOf(name: String): String =
    name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifBlank { "TV" }

private fun View.focusZoom(scale: Float) {
    setOnFocusChangeListener { v, hasFocus ->
        val s = if (hasFocus) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }
}

class GroupAdapter(private val onSelect: (String) -> Unit) :
    RecyclerView.Adapter<GroupAdapter.VH>() {

    var items: List<String> = emptyList()
        private set
    var selected: String? = null
        private set

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    fun submit(list: List<String>, sel: String) {
        items = list
        selected = sel
        notifyDataSetChanged()
    }

    fun select(g: String) {
        val old = items.indexOf(selected)
        selected = g
        if (old >= 0) notifyItemChanged(old)
        val now = items.indexOf(g)
        if (now >= 0) notifyItemChanged(now)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false) as TextView)

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.text.text = groupLabel(g)
        holder.text.isActivated = g == selected
        val pick = {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && items[p] != selected) onSelect(items[p])
        }
        holder.text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) pick() }
        holder.text.setOnClickListener { pick() }
    }
}

class ChannelAdapter(
    private val prefs: Prefs,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val initials: TextView = v.findViewById(R.id.initials)
        val name: TextView = v.findViewById(R.id.name)
        val star: TextView = v.findViewById(R.id.star)
    }

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        v.focusZoom(1.08f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.name.text = ch.name
        h.star.visibility = if (prefs.isFav(ch)) View.VISIBLE else View.GONE
        h.initials.text = initialsOf(ch.name)
        h.initials.visibility = View.VISIBLE
        h.logo.setImageDrawable(null)
        if (!ch.logo.isNullOrBlank()) {
            h.logo.load(ch.logo) {
                crossfade(true)
                listener(onSuccess = { _, _ -> h.initials.visibility = View.GONE })
            }
        } else {
            h.logo.dispose()
        }
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
    }
}
