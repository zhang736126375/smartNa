package com.bingo.smartna.collector.lead

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.collector.data.model.TeamTaskRow
import com.bingo.smartna.databinding.ItemTeamTaskBinding

class TeamTaskAdapter : ListAdapter<TeamTaskRow, TeamTaskAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTeamTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(private val binding: ItemTeamTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TeamTaskRow) {
            binding.tvTitle.text = item.title
            binding.tvMember.text = item.member
            binding.tvExtra.text = item.extra
        }
    }

    private object Diff : DiffUtil.ItemCallback<TeamTaskRow>() {
        override fun areItemsTheSame(oldItem: TeamTaskRow, newItem: TeamTaskRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TeamTaskRow, newItem: TeamTaskRow) = oldItem == newItem
    }
}
