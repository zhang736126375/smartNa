package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.databinding.ItemTaskCrowdBinding
import com.blankj.utilcode.util.ClickUtils

class CrowdTaskAdapter(
    private val onClaim: (Task) -> Unit
) : ListAdapter<HallTaskItem, CrowdTaskAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTaskCrowdBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onClaim)
    }

    class Holder(private val binding: ItemTaskCrowdBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HallTaskItem, onClaim: (Task) -> Unit) {
            val context = binding.root.context
            binding.tvTitle.text = item.task.title
            binding.tvScene.text = item.task.scene
            binding.tvSettle.text = item.task.settle
            binding.tvDuration.text = item.task.duration
            binding.tvQuota.text = item.quotaLeft.toString()

            val disabled = item.claimed || item.quotaLeft <= 0
            binding.btnClaim.text = when {
                item.claimed -> context.getString(R.string.hall_claimed)
                item.quotaLeft <= 0 -> context.getString(R.string.hall_full)
                else -> context.getString(R.string.hall_claim_short)
            }
            binding.btnClaim.setBackgroundResource(
                if (disabled) R.drawable.bg_btn_disabled else R.drawable.bg_btn_primary
            )
            binding.btnClaim.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (disabled) R.color.btn_disabled_text else R.color.card_white
                )
            )
            binding.btnClaim.isEnabled = !disabled
            ClickUtils.applySingleDebouncing(binding.btnClaim) {
                if (!disabled) onClaim(item.task)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HallTaskItem>() {
        override fun areItemsTheSame(oldItem: HallTaskItem, newItem: HallTaskItem) =
            oldItem.task.id == newItem.task.id

        override fun areContentsTheSame(oldItem: HallTaskItem, newItem: HallTaskItem) =
            oldItem == newItem
    }
}
