package com.bingo.smartna.collector.hall

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bingo.smartna.MainActivity
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.capture.CaptureActivity
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.UserRole
import android.view.View
import com.bingo.smartna.databinding.ActivityTaskDetailBinding
import com.blankj.utilcode.util.ClickUtils
import kotlin.math.roundToInt

class TaskDetailActivity : BaseActivity<ActivityTaskDetailBinding, CollectorViewModel>() {

    private lateinit var task: Task
    private var actionMode = ActionMode.FULL

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
        ClickUtils.applySingleDebouncing(binding.btnStandardDetail) {
            Toast.makeText(this, R.string.task_detail_standard_toast, Toast.LENGTH_SHORT).show()
        }
        bindContent()
        bindStandards()
        ClickUtils.applySingleDebouncing(binding.btnAction) { onActionClick() }
        renderAction()
    }

    override fun initViewObservable() {
        viewModel.ui.observe(this) { renderAction() }
    }

    private fun bindContent() {
        val state = viewModel.ui.value
        val quotaLeft = state?.quotaLeft?.get(task.id) ?: task.quotaTotal

        binding.tvTaskTitle.text = task.title
        binding.tvTaskScene.text = task.scene
        binding.tvDuration.text = task.duration.replace("-", "~")
        binding.tvQuota.text = quotaLeft.toString()
        binding.tvDeviceName.text = deviceLabel(task.device)
        binding.ivDevice.setImageResource(
            if (task.device.contains("UMI", ignoreCase = true)) R.drawable.ic_kit_gripper
            else R.drawable.ic_kit_ego
        )
        binding.tvRuleDuration.text = getString(R.string.task_detail_rule_duration_value, task.durationMax)
        applyPriceVisibility(isStaffCollector())
    }

    private fun isStaffCollector(): Boolean {
        val role = viewModel.ui.value?.role ?: Prefs(this).role
        return role == UserRole.STAFF || role == UserRole.LEAD
    }

    private fun applyPriceVisibility(staffMode: Boolean) {
        val priceVisible = if (staffMode) View.GONE else View.VISIBLE
        binding.earnColumn.visibility = priceVisible
        binding.dividerEarn.visibility = priceVisible
        binding.ruleUnitRow.visibility = priceVisible
        binding.ruleUnitDivider.visibility = priceVisible
        if (staffMode) {
            binding.tvRuleSectionTitle.setText(R.string.task_detail_rule_section_staff)
            binding.tvRuleSettleValue.setText(R.string.task_detail_rule_settle_value_staff)
            binding.tvRuleNotice.setText(R.string.task_detail_rule_notice_staff)
        } else {
            binding.tvEarn.text = formatEarnRange(task)
            binding.tvRuleUnit.text = getString(R.string.task_detail_rule_unit_value, task.reward)
            binding.tvRuleSectionTitle.setText(R.string.task_detail_rule_section)
            binding.tvRuleSettleValue.setText(R.string.task_detail_rule_settle_value)
            binding.tvRuleNotice.setText(R.string.task_detail_rule_notice)
        }
    }

    private fun bindStandards() {
        val items = listOf(
            R.string.task_detail_std_1 to R.string.task_detail_std_1_desc,
            R.string.task_detail_std_2 to R.string.task_detail_std_2_desc,
            R.string.task_detail_std_3 to R.string.task_detail_std_3_desc,
            R.string.task_detail_std_4 to R.string.task_detail_std_4_desc,
            R.string.task_detail_std_5 to R.string.task_detail_std_5_desc
        )
        val inflater = LayoutInflater.from(this)
        binding.standardList.removeAllViews()
        val margin = (8 * resources.displayMetrics.density).toInt()
        items.forEach { (titleRes, descRes) ->
            val row = inflater.inflate(R.layout.item_task_standard, binding.standardList, false)
            val tvTitle = row.findViewById<TextView>(R.id.tvStdTitle)
            val tvDesc = row.findViewById<TextView>(R.id.tvStdDesc)
            tvTitle.text = getString(titleRes)
            tvDesc.text = getString(descRes)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = margin
            binding.standardList.addView(row, params)
        }
        bindStandardIntro()
    }

    private fun bindStandardIntro() {
        val full = getString(R.string.task_detail_standard_intro)
        val highlight = getString(R.string.task_detail_standard_highlight)
        val span = SpannableString(full)
        val start = full.indexOf(highlight)
        if (start >= 0) {
            span.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.brand_orange)),
                start,
                start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.tvStandardIntro.text = span
    }

    private fun renderAction() {
        val state = viewModel.ui.value ?: return
        val claimed = state.claimedIds.contains(task.id)
        val quotaLeft = state.quotaLeft[task.id] ?: task.quotaTotal
        val full = !claimed && quotaLeft <= 0
        val hasDevice = Prefs(this).hasConnectedDevice

        actionMode = when {
            full -> ActionMode.FULL
            claimed -> ActionMode.CAPTURE
            !hasDevice -> ActionMode.BIND
            else -> ActionMode.CLAIM
        }

        when (actionMode) {
            ActionMode.FULL -> {
                binding.tvBottomHint.text = getString(R.string.task_detail_full_hint)
                binding.btnAction.text = getString(R.string.hall_full)
                binding.btnAction.setBackgroundResource(R.drawable.bg_btn_disabled)
                binding.btnAction.setTextColor(ContextCompat.getColor(this, R.color.btn_disabled_text))
                binding.btnAction.isEnabled = false
            }
            ActionMode.CAPTURE -> {
                binding.tvBottomHint.text = getString(R.string.task_detail_capture_hint)
                binding.btnAction.text = getString(R.string.hall_enter)
                stylePrimaryButton()
                binding.btnAction.isEnabled = true
            }
            ActionMode.BIND -> {
                binding.tvBottomHint.text = getString(R.string.task_detail_bind_hint)
                binding.btnAction.text = getString(R.string.task_detail_bind)
                stylePrimaryButton()
                binding.btnAction.isEnabled = true
            }
            ActionMode.CLAIM -> {
                binding.tvBottomHint.text = getString(R.string.task_detail_claim_hint)
                binding.btnAction.text = getString(R.string.hall_claim)
                stylePrimaryButton()
                binding.btnAction.isEnabled = true
            }
        }
    }

    private fun onActionClick() {
        when (actionMode) {
            ActionMode.BIND -> {
                startActivity(MainActivity.intentForTab(this, R.id.nav_device))
                finish()
            }
            ActionMode.CLAIM -> {
                when (HallTaskActions.tryClaim(this, viewModel, task)) {
                    ClaimResult.Success -> {
                        Toast.makeText(this, R.string.task_detail_claimed_toast, Toast.LENGTH_SHORT).show()
                        binding.tvQuota.text = (viewModel.ui.value?.quotaLeft?.get(task.id) ?: 0).toString()
                        renderAction()
                    }
                    ClaimResult.NeedDevice -> {
                        startActivity(MainActivity.intentForTab(this, R.id.nav_device))
                        finish()
                    }
                }
            }
            ActionMode.CAPTURE -> HallTaskActions.openCapture(this, currentTask())
            ActionMode.FULL -> Unit
        }
    }

    private enum class ActionMode {
        BIND, CLAIM, CAPTURE, FULL
    }

    private fun stylePrimaryButton() {
        binding.btnAction.setBackgroundResource(R.drawable.bg_btn_login)
        binding.btnAction.setTextColor(ContextCompat.getColor(this, R.color.card_white))
    }

    private fun formatEarnRange(task: Task): String {
        val min = task.reward * 2 / 60.0
        val max = task.reward * task.durationMax / 60.0
        return "¥${formatMoney(min)}~${formatMoney(max)}"
    }

    private fun formatMoney(value: Double): String {
        val rounded = (value * 100).roundToInt() / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
        else "%.2f".format(rounded).trimEnd('0').trimEnd('.')
    }

    private fun deviceLabel(device: String): String {
        return when {
            device.contains("UMI", ignoreCase = true) -> "UMI x1"
            device.contains("ego", ignoreCase = true) -> "Mego Ego x1"
            else -> device
        }
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
