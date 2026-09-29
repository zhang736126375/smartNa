package com.bingo.smartna.collector.hall

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskPriority
import com.bingo.smartna.databinding.ItemGraspTaskBinding
import com.blankj.utilcode.util.ClickUtils
import java.util.Locale

class GraspTaskAdapter(
    private val onItemClick: (Task) -> Unit,
    private val onAction: (Task, Boolean) -> Unit
) : RecyclerView.Adapter<GraspTaskAdapter.Holder>() {

    private var state: CollectorUiState? = null
    private var tasks: List<Task> = emptyList()

    fun submit(state: CollectorUiState, tasks: List<Task>) {
        this.state = state
        this.tasks = tasks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemGraspTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(tasks[position], state, onItemClick, onAction)
    }

    override fun getItemCount() = tasks.size

    class Holder(private val binding: ItemGraspTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            task: Task,
            state: CollectorUiState?,
            onItemClick: (Task) -> Unit,
            onAction: (Task, Boolean) -> Unit
        ) {
            val context = binding.root.context
            val claimed = state?.claimedIds?.contains(task.id) == true
            val quotaLeft = state?.quotaLeft?.get(task.id) ?: task.quotaTotal
            binding.cover.setBackgroundResource(coverOf(task))
            binding.tvScenePill.text = task.scene.replace("-", " · ")
            binding.tvTitle.text = task.title.replace("【Demo】", "")
            binding.tvMeta.text = metaText(task, quotaLeft)
            highlightQuota(quotaLeft)
            binding.tvPrice.text = formatPrice(task.reward)
            bindStatus(claimed, quotaLeft, task)
            binding.btnAction.text = context.getString(
                when {
                    claimed -> R.string.hall_enter
                    quotaLeft <= 0 -> R.string.hall_full
                    else -> R.string.grasp_claim
                }
            )
            binding.btnAction.isEnabled = quotaLeft > 0 || claimed
            ClickUtils.applySingleDebouncing(binding.root) { onItemClick(task) }
            ClickUtils.applySingleDebouncing(binding.btnAction) { onAction(task, claimed) }
        }

        private fun bindStatus(claimed: Boolean, quotaLeft: Int, task: Task) {
            val context = binding.root.context
            when {
                claimed -> {
                    binding.tvStatusPill.visibility = View.VISIBLE
                    binding.tvStatusPill.setBackgroundResource(R.drawable.bg_pill_brand)
                    binding.tvStatusPill.setTextColor(ContextCompat.getColor(context, R.color.brand_primary_active))
                    binding.tvStatusPill.setText(R.string.grasp_status_doing)
                }
                quotaLeft in 1..5 -> {
                    binding.tvStatusPill.visibility = View.VISIBLE
                    binding.tvStatusPill.setBackgroundResource(R.drawable.bg_pill_scarce)
                    binding.tvStatusPill.setTextColor(ContextCompat.getColor(context, R.color.priority_high))
                    binding.tvStatusPill.text = context.getString(R.string.grasp_status_left, quotaLeft)
                }
                task.priority == TaskPriority.HIGH -> {
                    binding.tvStatusPill.visibility = View.VISIBLE
                    binding.tvStatusPill.setBackgroundResource(R.drawable.bg_pill_gold)
                    binding.tvStatusPill.setTextColor(ContextCompat.getColor(context, R.color.gold_text))
                    binding.tvStatusPill.setText(R.string.grasp_status_deadline)
                }
                else -> binding.tvStatusPill.visibility = View.GONE
            }
        }

        private fun metaText(task: Task, quotaLeft: Int): CharSequence {
            val context = binding.root.context
            return context.getString(
                R.string.grasp_task_meta,
                task.duration,
                quotaLeft,
                task.claimedCount
            )
        }

        private fun highlightQuota(quotaLeft: Int) {
            val context = binding.root.context
            val raw = binding.tvMeta.text.toString()
            val highlight = context.getString(R.string.grasp_quota_highlight, quotaLeft)
            val start = raw.indexOf(highlight)
            if (start < 0) return
            val spanned = SpannableStringBuilder(raw)
            spanned.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.scarce_text)),
                start,
                start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.tvMeta.text = spanned
        }

        private fun formatPrice(reward: Double): String {
            return if (reward % 1.0 == 0.0) {
                String.format(Locale.CHINA, "¥%.0f", reward)
            } else {
                String.format(Locale.CHINA, "¥%.2f", reward)
            }
        }

        private fun coverOf(task: Task): Int = when (task.category) {
            HallCategory.AGRI -> R.drawable.bg_cover_agri_flat
            HallCategory.PRODUCE -> R.drawable.bg_cover_wood_flat
            HallCategory.WAREHOUSE, HallCategory.CATERING -> R.drawable.bg_cover_shop_flat
            else -> R.drawable.bg_cover_warm_flat
        }
    }
}
