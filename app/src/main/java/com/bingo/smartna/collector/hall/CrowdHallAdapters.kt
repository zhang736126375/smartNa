package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R

class SceneGroupAdapter(
    private val onClick: (CrowdSceneGroup?) -> Unit
) : RecyclerView.Adapter<SceneGroupAdapter.Holder>() {

    private val items = CrowdScenes.groups
    var selectedId: String = ALL_ID
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crowd_scene_group, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val selected = isAllPosition(position) && selectedId == ALL_ID ||
            !isAllPosition(position) && items[position - 1].id == selectedId
        val label = if (isAllPosition(position)) CrowdScenes.ALL else items[position - 1].name
        holder.name.text = label
        holder.name.setTextColor(
            ContextCompat.getColor(
                holder.name.context,
                if (selected) R.color.brand_primary else R.color.text_primary
            )
        )
        holder.name.paint.isFakeBoldText = selected
        holder.root.setBackgroundResource(
            if (selected) R.drawable.bg_filter_group_selected else android.R.color.transparent
        )
        holder.root.setOnClickListener {
            if (isAllPosition(position)) {
                onClick(null)
            } else {
                onClick(items[position - 1])
            }
        }
    }

    override fun getItemCount() = items.size + 1

    private fun isAllPosition(position: Int) = position == 0

    class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val root: View = root
        val name: TextView = root.findViewById(R.id.tvName)
    }

    companion object {
        const val ALL_ID = "__all__"
    }
}

class SceneChildAdapter(
    private val onClick: (String?) -> Unit
) : RecyclerView.Adapter<SceneChildAdapter.Holder>() {

    private var items: List<String> = emptyList()
    var selected: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(children: List<String>, selectedChild: String?) {
        items = listOf(CrowdScenes.ALL) + children
        selected = selectedChild
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crowd_scene_child, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val label = items[position]
        val value = label.takeIf { it != CrowdScenes.ALL }
        val selectedNow = selected == value
        holder.name.text = label
        holder.name.setTextColor(
            ContextCompat.getColor(holder.name.context, R.color.text_primary)
        )
        holder.name.paint.isFakeBoldText = selectedNow
        holder.root.setBackgroundResource(
            if (selectedNow) R.drawable.bg_filter_child_selected else android.R.color.transparent
        )
        holder.indicator.visibility = if (selectedNow) View.VISIBLE else View.GONE
        holder.root.setOnClickListener { onClick(value) }
    }

    override fun getItemCount() = items.size

    class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val root: View = root
        val name: TextView = root.findViewById(R.id.tvName)
        val indicator: ImageView = root.findViewById(R.id.ivIndicator)
    }
}

class DurationFilterAdapter(
    private val onClick: (DurationBucket?) -> Unit
) : RecyclerView.Adapter<DurationFilterAdapter.Holder>() {

    private val items = CrowdDurations.buckets
    var selected: DurationBucket? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter_option, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val bucket = items[position]
        val selectedNow = selected == bucket
        holder.label.text = bucket.label
        holder.label.paint.isFakeBoldText = selectedNow
        holder.root.setBackgroundResource(
            if (selectedNow) R.drawable.bg_filter_child_selected else android.R.color.transparent
        )
        holder.check.visibility = if (selectedNow) View.VISIBLE else View.GONE
        holder.root.setOnClickListener {
            selected = if (selectedNow) null else bucket
            onClick(selected)
        }
    }

    override fun getItemCount() = items.size

    class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val root: View = root
        val label: TextView = root.findViewById(R.id.tvLabel)
        val check: ImageView = root.findViewById(R.id.ivCheck)
    }
}
