package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskPriority
import com.bingo.smartna.databinding.ItemTaskCrowdBinding
import com.blankj.utilcode.util.ClickUtils

class CrowdTaskAdapter(
    private val onItemClick: (Task) -> Unit,
    private val onClaim: (Task) -> Unit,
    private val onCapture: (Task) -> Unit,
    private val showStaffTags: Boolean = false
) : ListAdapter<HallTaskItem, CrowdTaskAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTaskCrowdBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, showStaffTags)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onItemClick, onClaim, onCapture)
    }

    class Holder(
        private val binding: ItemTaskCrowdBinding,
        private val showStaffTags: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: HallTaskItem,
            onItemClick: (Task) -> Unit,
            onClaim: (Task) -> Unit,
            onCapture: (Task) -> Unit
        ) {
            val context = binding.root.context
            val task = item.task
            binding.tvTitle.text = task.title
            binding.tvScene.text = task.scene.replace("-", "·")
            if (showStaffTags) {
                binding.tvPrice.visibility = View.GONE
            } else {
                binding.tvPrice.visibility = View.VISIBLE
                binding.tvPrice.text = formatPrice(task.reward)
            }
            binding.tvDuration.text = task.duration

            if (showStaffTags) {
                binding.tagRow.visibility = View.VISIBLE
                binding.tvTitle.layoutParams = (binding.tvTitle.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    topMargin = (8 * context.resources.displayMetrics.density).toInt()
                }
                binding.tvKind.text = task.kind.label
                binding.tvPriority.text = task.priority.label
                binding.tvPriority.setBackgroundResource(
                    when (task.priority) {
                        TaskPriority.HIGH -> R.drawable.bg_priority_high
                        TaskPriority.IN_PROGRESS -> R.drawable.bg_priority_doing
                        TaskPriority.UNLIMITED -> R.drawable.bg_priority_unlimited
                    }
                )
                binding.tvPriority.setTextColor(
                    ContextCompat.getColor(
                        context,
                        when (task.priority) {
                            TaskPriority.HIGH -> R.color.priority_high
                            TaskPriority.IN_PROGRESS -> R.color.priority_doing
                            TaskPriority.UNLIMITED -> R.color.priority_unlimited
                        }
                    )
                )
            } else {
                binding.tagRow.visibility = View.GONE
                binding.tvTitle.layoutParams = (binding.tvTitle.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    topMargin = 0
                }
            }

            val full = !item.claimed && item.quotaLeft <= 0
            val continueCapture = item.claimed && item.canCaptureMore &&
                (item.doneClips > 0 || item.uploadedClips > 0)

            binding.btnClaim.text = when {
                full -> context.getString(R.string.hall_full)
                continueCapture -> context.getString(R.string.hall_continue)
                item.claimed -> context.getString(R.string.hall_enter)
                else -> context.getString(R.string.hall_claim_short)
            }
            binding.btnClaim.setBackgroundResource(
                if (full) R.drawable.bg_btn_disabled else R.drawable.bg_btn_login
            )
            binding.btnClaim.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (full) R.color.btn_disabled_text else R.color.card_white
                )
            )
            binding.btnClaim.isEnabled = !full

            ClickUtils.applySingleDebouncing(binding.cardRoot) { onItemClick(task) }
            ClickUtils.applySingleDebouncing(binding.btnClaim) {
                when {
                    full -> Unit
                    item.claimed || continueCapture -> onCapture(task)
                    else -> onClaim(task)
                }
            }
        }

        private fun formatPrice(reward: Double): String {
            return if (reward % 1.0 == 0.0) "¥ ${reward.toInt()}" else "¥ ${"%.2f".format(reward)}"
        }
    }

    private object Diff : DiffUtil.ItemCallback<HallTaskItem>() {
        override fun areItemsTheSame(oldItem: HallTaskItem, newItem: HallTaskItem) =
            oldItem.task.id == newItem.task.id

        override fun areContentsTheSame(oldItem: HallTaskItem, newItem: HallTaskItem) =
            oldItem == newItem
    }
}
