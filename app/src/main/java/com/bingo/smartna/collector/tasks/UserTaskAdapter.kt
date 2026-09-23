package com.bingo.smartna.collector.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.UserTask
import com.bingo.smartna.databinding.ItemUserTaskBinding
import com.blankj.utilcode.util.ClickUtils

class UserTaskAdapter(
    private val onCapture: (UserTask) -> Unit,
    private val onSubmitReview: (UserTask) -> Unit,
    private val onApprove: (UserTask) -> Unit,
    private val onReject: (UserTask) -> Unit
) : ListAdapter<UserTask, UserTaskAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemUserTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onCapture, onSubmitReview, onApprove, onReject)
    }

    class Holder(private val binding: ItemUserTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: UserTask,
            onCapture: (UserTask) -> Unit,
            onSubmitReview: (UserTask) -> Unit,
            onApprove: (UserTask) -> Unit,
            onReject: (UserTask) -> Unit
        ) {
            val context = binding.root.context
            val task = item.task
            binding.tvTitle.text = task.title
            binding.tvScene.text = task.scene
            binding.tvSettle.text = task.settle
            binding.tvDuration.text = task.duration
            binding.btnSecondary.visibility = View.GONE
            binding.btnSecondary.setOnClickListener(null)
            binding.btnAction.setOnClickListener(null)

            when (item.status) {
                TaskStatus.IN_PROGRESS -> {
                    val target = item.demoTargetClips()
                    binding.tvHint.text = when {
                        item.uploadedClips >= target -> context.getString(R.string.tasks_hint_ready_review)
                        item.doneClips > item.uploadedClips -> context.getString(R.string.tasks_hint_uploading)
                        item.doneClips > 0 -> context.getString(R.string.tasks_hint_capture_progress, item.doneClips, target)
                        else -> context.getString(R.string.tasks_hint_need_capture)
                    }
                    if (item.canCaptureMoreDemo()) {
                        binding.btnSecondary.visibility = View.VISIBLE
                        binding.btnSecondary.text = context.getString(
                            if (item.doneClips > 0) R.string.tasks_continue_capture else R.string.tasks_go_capture
                        )
                        ClickUtils.applySingleDebouncing(binding.btnSecondary) { onCapture(item) }
                    }
                    val canSubmit = item.canSubmitReviewDemo()
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.isEnabled = canSubmit
                    binding.btnAction.text = context.getString(R.string.tasks_submit_review)
                    binding.btnAction.setBackgroundResource(
                        if (canSubmit) R.drawable.bg_btn_primary else R.drawable.bg_btn_disabled
                    )
                    binding.btnAction.setTextColor(
                        context.getColor(if (canSubmit) R.color.card_white else R.color.btn_disabled_text)
                    )
                    if (canSubmit) {
                        ClickUtils.applySingleDebouncing(binding.btnAction) { onSubmitReview(item) }
                    }
                }
                TaskStatus.REVIEWING -> {
                    binding.tvHint.text = context.getString(R.string.tasks_hint_reviewing)
                    binding.btnSecondary.visibility = View.VISIBLE
                    binding.btnSecondary.text = context.getString(R.string.tasks_reject)
                    binding.btnSecondary.setBackgroundResource(R.drawable.bg_btn_outline)
                    ClickUtils.applySingleDebouncing(binding.btnSecondary) { onReject(item) }
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.isEnabled = true
                    binding.btnAction.text = context.getString(R.string.tasks_approve)
                    binding.btnAction.setBackgroundResource(R.drawable.bg_btn_primary)
                    binding.btnAction.setTextColor(context.getColor(R.color.card_white))
                    ClickUtils.applySingleDebouncing(binding.btnAction) { onApprove(item) }
                }
                TaskStatus.DONE -> {
                    val staffMode = isStaffCollector(context)
                    binding.tvHint.text = if (staffMode) {
                        context.getString(R.string.tasks_hint_done_staff)
                    } else {
                        context.getString(R.string.tasks_hint_done, task.reward)
                    }
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.isEnabled = false
                    binding.btnAction.setBackgroundResource(0)
                    binding.btnAction.setTextColor(context.getColor(R.color.text_gray))
                    binding.btnAction.text = context.getString(R.string.tasks_settled)
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

private fun isStaffCollector(context: android.content.Context): Boolean {
    return when (Prefs(context).role) {
        UserRole.STAFF, UserRole.LEAD -> true
        UserRole.CROWD -> false
    }
}
