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
import com.bingo.smartna.databinding.ItemTaskBinding
import com.blankj.utilcode.util.ClickUtils

data class HallTaskItem(
    val task: Task,
    val claimed: Boolean,
    val quotaLeft: Int,
    val doneClips: Int = 0,
    val uploadedClips: Int = 0,
    val canCaptureMore: Boolean = true
)

class TaskAdapter(
    private val onItemClick: (Task) -> Unit,
    private val onClaim: (Task) -> Unit,
    private val onCapture: (Task) -> Unit
) : ListAdapter<HallTaskItem, TaskAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onItemClick, onClaim, onCapture)
    }

    class Holder(private val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: HallTaskItem,
            onItemClick: (Task) -> Unit,
            onClaim: (Task) -> Unit,
            onCapture: (Task) -> Unit
        ) {
            val context = binding.root.context
            val task = item.task
            ClickUtils.applySingleDebouncing(binding.root) { onItemClick(task) }
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
            binding.tvTitle.text = task.title
            val unlimited = task.priority == TaskPriority.UNLIMITED || task.targetClips <= 0
            binding.tvMeta.text = if (unlimited) {
                context.getString(R.string.hall_meta_unlimited, task.reward, task.deadline)
            } else {
                context.getString(R.string.hall_meta, task.targetClips, task.reward, task.deadline)
            }
            if (unlimited) {
                binding.progressClips.visibility = View.GONE
                binding.tvProgress.text = context.getString(R.string.hall_progress_unlimited, task.doneClips)
            } else {
                binding.progressClips.visibility = View.VISIBLE
                val percent = (task.doneClips * 100 / task.targetClips).coerceIn(0, 100)
                binding.progressClips.progress = percent
                binding.tvProgress.text = context.getString(
                    R.string.hall_progress,
                    task.doneClips,
                    task.targetClips
                )
            }

            val full = !item.claimed && item.quotaLeft <= 0
            val continueCapture = item.claimed && task.doneClips > 0 && !unlimited &&
                task.doneClips < task.targetClips
            binding.btnClaim.text = when {
                full -> context.getString(R.string.hall_full)
                continueCapture -> context.getString(R.string.hall_continue)
                item.claimed -> context.getString(R.string.hall_enter)
                else -> context.getString(R.string.hall_claim)
            }
            binding.btnClaim.setBackgroundResource(
                if (full) R.drawable.bg_btn_disabled else R.drawable.bg_btn_primary
            )
            binding.btnClaim.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (full) R.color.btn_disabled_text else R.color.card_white
                )
            )
            binding.btnClaim.isEnabled = !full
            ClickUtils.applySingleDebouncing(binding.btnClaim) {
                when {
                    full -> Unit
                    item.claimed -> onCapture(task)
                    else -> onClaim(task)
                }
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
