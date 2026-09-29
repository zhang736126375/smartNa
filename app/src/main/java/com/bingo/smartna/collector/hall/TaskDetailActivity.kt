package com.bingo.smartna.collector.hall

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.databinding.ActivityTaskDetailBinding
import com.blankj.utilcode.util.ClickUtils
import java.util.Locale

class TaskDetailActivity : BaseActivity<ActivityTaskDetailBinding, CollectorViewModel>() {

    private lateinit var task: Task
    private var actionMode = ActionMode.CLAIM

    override fun inflateBinding() = ActivityTaskDetailBinding.inflate(layoutInflater)

    override fun initViewModel(): CollectorViewModel = CollectorViewModels.get(application)

    private fun currentTask(): Task {
        return viewModel.ui.value?.userTaskFor(task.id)?.task ?: task
    }

    override fun initParam() {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        task = MockDataSource.allTasks.find { it.id == taskId }
            ?: error("Task not found: $taskId")
    }

    override fun initData() {
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        ClickUtils.applySingleDebouncing(binding.videoPanel) {
            TeachVideoDialog.show(this)
        }
        bindContent()
        bindRequirements()
        ClickUtils.applySingleDebouncing(binding.btnAction) { onActionClick() }
        renderAction()
    }

    override fun initViewObservable() {
        viewModel.ui.observe(this) { renderAction() }
    }

    private fun bindContent() {
        val state = viewModel.ui.value
        val quotaLeft = state?.quotaLeft?.get(task.id) ?: task.quotaTotal
        binding.cover.setBackgroundResource(R.drawable.bg_cover_wood_flat)
        binding.tvScenePill.text = task.scene.replace("-", " · ").replace("场景", "")
        binding.tvTaskTitle.text = task.title.replace("【Demo】", "")
        binding.tvPrice.text = formatPrice(task.reward)
        binding.tvMeta.text = getString(R.string.grasp_detail_meta, task.duration, quotaLeft)
    }

    private fun bindRequirements() {
        val items = listOf(
            R.string.grasp_req_1_title,
            R.string.grasp_req_2_title,
            R.string.grasp_req_3_title,
            R.string.grasp_req_4_title,
            R.string.grasp_req_5_title
        )
        val inflater = LayoutInflater.from(this)
        binding.reqList.removeAllViews()
        items.forEachIndexed { index, titleRes ->
            if (index > 0) {
                val line = View(this)
                line.setBackgroundColor(0xFFFBF2E2.toInt())
                binding.reqList.addView(
                    line,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                )
            }
            val row = inflater.inflate(R.layout.item_grasp_req, binding.reqList, false)
            row.findViewById<TextView>(R.id.tvIndex).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.tvReqTitle).text = getString(titleRes)
            binding.reqList.addView(row)
        }
    }

    private fun renderAction() {
        val state = viewModel.ui.value ?: return
        val claimed = state.claimedIds.contains(task.id)
        val quotaLeft = state.quotaLeft[task.id] ?: task.quotaTotal
        val full = !claimed && quotaLeft <= 0

        actionMode = when {
            full -> ActionMode.FULL
            claimed -> ActionMode.CAPTURE
            else -> ActionMode.CLAIM
        }

        when (actionMode) {
            ActionMode.FULL -> {
                binding.tvBottomHint.visibility = View.VISIBLE
                binding.tvBottomHint.text = getString(R.string.task_detail_full_hint)
                binding.btnAction.text = getString(R.string.hall_full)
                binding.btnAction.setBackgroundResource(R.drawable.bg_btn_disabled)
                binding.btnAction.setTextColor(ContextCompat.getColor(this, R.color.btn_disabled_text))
                binding.btnAction.isEnabled = false
            }
            ActionMode.CAPTURE -> {
                binding.tvBottomHint.visibility = View.GONE
                binding.btnAction.text = getString(R.string.grasp_detail_go_capture)
                stylePrimaryButton()
                binding.btnAction.isEnabled = true
            }
            ActionMode.CLAIM -> {
                binding.tvBottomHint.visibility = View.GONE
                binding.btnAction.text = getString(R.string.grasp_claim_task)
                stylePrimaryButton()
                binding.btnAction.isEnabled = true
            }
        }
    }

    private fun onActionClick() {
        when (actionMode) {
            ActionMode.CLAIM -> {
                HallTaskActions.claimAndGuide(this, viewModel, task)
                finish()
            }
            ActionMode.CAPTURE -> HallTaskActions.openCapture(this, currentTask())
            ActionMode.FULL -> Unit
        }
    }

    private fun stylePrimaryButton() {
        binding.btnAction.setBackgroundResource(R.drawable.bg_btn_primary)
        binding.btnAction.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun formatPrice(reward: Double): String {
        return if (reward % 1.0 == 0.0) {
            String.format(Locale.CHINA, "¥%.0f", reward)
        } else {
            String.format(Locale.CHINA, "¥%.2f", reward)
        }
    }

    private enum class ActionMode {
        CLAIM, CAPTURE, FULL
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context, task: Task) {
            context.startActivity(
                Intent(context, TaskDetailActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, task.id)
            )
        }
    }
}
