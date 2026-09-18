package com.bingo.smartna.collector.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.collector.data.model.UserTask
import com.bingo.smartna.databinding.ItemUserTaskBinding
import com.blankj.utilcode.util.ClickUtils

class UserTaskAdapter(
    private val onAction: (UserTask) -> Unit
) : ListAdapter<UserTask, UserTaskAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemUserTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onAction)
    }

    class Holder(private val binding: ItemUserTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UserTask, onAction: (UserTask) -> Unit) {
            val context = binding.root.context
            val task = item.task
            binding.tvTitle.text = task.title
            binding.tvScene.text = task.scene
            binding.tvSettle.text = task.settle
            binding.tvDuration.text = task.duration
            binding.tvHint.text = when (item.status) {
                TaskStatus.IN_PROGRESS -> context.getString(R.string.tasks_hint_in_progress)
                TaskStatus.REVIEWING -> context.getString(R.string.tasks_hint_reviewing)
                TaskStatus.DONE -> context.getString(R.string.tasks_hint_done, task.reward)
            }
            binding.btnAction.visibility = View.VISIBLE
            when (item.status) {
                TaskStatus.IN_PROGRESS, TaskStatus.REVIEWING -> {
                    binding.btnAction.isEnabled = true
                    binding.btnAction.setBackgroundResource(R.drawable.bg_btn_primary)
                    binding.btnAction.setTextColor(context.getColor(R.color.card_white))
                    binding.btnAction.text = context.getString(
                        if (item.status == TaskStatus.IN_PROGRESS) {
                            R.string.tasks_submit_review
                        } else {
                            R.string.tasks_approve
                        }
                    )
                    ClickUtils.applySingleDebouncing(binding.btnAction) { onAction(item) }
                }
                TaskStatus.DONE -> {
                    binding.btnAction.setOnClickListener(null)
                    binding.btnAction.setBackgroundResource(0)
                    binding.btnAction.setTextColor(context.getColor(R.color.text_gray))
                    binding.btnAction.text = context.getString(R.string.tasks_settled)
                    binding.btnAction.isEnabled = false
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<UserTask>() {
        override fun areItemsTheSame(oldItem: UserTask, newItem: UserTask) =
            oldItem.task.id == newItem.task.id

        override fun areContentsTheSame(oldItem: UserTask, newItem: UserTask) =
            oldItem == newItem
    }
}
