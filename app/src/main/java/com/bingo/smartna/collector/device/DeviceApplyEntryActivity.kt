package com.bingo.smartna.collector.device

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.databinding.ActivityDeviceApplyEntryBinding
import com.blankj.utilcode.util.ClickUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeviceApplyEntryActivity : BaseActivity<ActivityDeviceApplyEntryBinding, BaseViewModel>() {

    private var currentStep = ApplyStep.VERIFY
    private var isProcessing = false
    private var applyPersisted = false

    override fun inflateBinding() = ActivityDeviceApplyEntryBinding.inflate(layoutInflater)

    override fun initData() {
        val prefs = Prefs(this)
        if (prefs.isApplyCompleted) {
            currentStep = ApplyStep.DONE
            applyPersisted = true
        }
        ClickUtils.applySingleDebouncing(binding.btnBack) { finishWithResult() }
        ClickUtils.applySingleDebouncing(binding.btnAction) { performCurrentStep() }
        ClickUtils.applySingleDebouncing(binding.stepCardVerify) {
            if (currentStep == ApplyStep.VERIFY) performCurrentStep()
        }
        ClickUtils.applySingleDebouncing(binding.stepCardSign) {
            if (currentStep == ApplyStep.SIGN) performCurrentStep()
        }
        ClickUtils.applySingleDebouncing(binding.stepCardModel) {
            if (currentStep == ApplyStep.MODEL) performCurrentStep()
        }
        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun performCurrentStep() {
        if (isProcessing) return
        if (currentStep == ApplyStep.DONE) {
            if (applyPersisted) {
                if (Prefs(this).hasConnectedDevice) {
                    finishWithResult()
                } else {
                    startActivity(Intent(this, ConnectKitActivity::class.java))
                }
            } else {
                persistApplyAndFinish()
            }
            return
        }
        isProcessing = true
        setActionEnabled(false)
        showLoading()
        lifecycleScope.launch {
            delay(1200)
            hideLoading()
            val previous = currentStep
            currentStep = when (currentStep) {
                ApplyStep.VERIFY -> ApplyStep.SIGN
                ApplyStep.SIGN -> ApplyStep.MODEL
                ApplyStep.MODEL -> ApplyStep.DONE
                ApplyStep.DONE -> ApplyStep.DONE
            }
            if (previous == ApplyStep.MODEL && currentStep == ApplyStep.DONE) {
                persistApply()
            }
            renderState()
            isProcessing = false
            setActionEnabled(true)
        }
    }

    private fun persistApply() {
        Prefs(this).saveApplyCompleted(DeviceKit.EGO.id)
        applyPersisted = true
        setResult(RESULT_OK)
        Toast.makeText(this, R.string.device_apply_complete_toast, Toast.LENGTH_SHORT).show()
    }

    private fun persistApplyAndFinish() {
        persistApply()
        finishWithResult()
    }

    private fun finishWithResult() {
        if (applyPersisted) setResult(RESULT_OK)
        finish()
    }

    private fun renderState() {
        renderProgressHeader()
        renderStepCards()
        renderShipmentPanel()
        renderBottomArea()
    }

    private fun renderShipmentPanel() {
        val show = currentStep == ApplyStep.DONE && applyPersisted
        binding.shipmentStatusPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun renderProgressHeader() {
        renderProgressBar()
        bindProgressStep(
            binding.tvProgressStep1,
            binding.tvProgressLabel1,
            index = 1,
            state = stepState(1)
        )
        bindProgressStep(
            binding.tvProgressStep2,
            binding.tvProgressLabel2,
            index = 2,
            state = stepState(2)
        )
        bindProgressStep(
            binding.tvProgressStep3,
            binding.tvProgressLabel3,
            index = 3,
            state = stepState(3)
        )
    }

    private fun stepState(index: Int): StepVisualState {
        val doneIndex = when (currentStep) {
            ApplyStep.VERIFY -> 0
            ApplyStep.SIGN -> 1
            ApplyStep.MODEL -> 2
            ApplyStep.DONE -> 3
        }
        return when {
            index <= doneIndex -> StepVisualState.DONE
            index == doneIndex + 1 -> StepVisualState.ACTIVE
            else -> StepVisualState.PENDING
        }
    }

    private fun bindProgressStep(circle: TextView, label: TextView, index: Int, state: StepVisualState) {
        when (state) {
            StepVisualState.DONE -> {
                circle.setBackgroundResource(R.drawable.bg_step_circle_active)
                circle.text = "✓"
                circle.setTextColor(ContextCompat.getColor(this, R.color.card_white))
                label.setTextColor(ContextCompat.getColor(this, R.color.brand_orange))
                label.paint.isFakeBoldText = false
            }
            StepVisualState.ACTIVE -> {
                circle.setBackgroundResource(R.drawable.bg_step_circle_active)
                circle.text = index.toString()
                circle.setTextColor(ContextCompat.getColor(this, R.color.card_white))
                label.setTextColor(ContextCompat.getColor(this, R.color.brand_orange))
                label.paint.isFakeBoldText = true
            }
            StepVisualState.PENDING -> {
                circle.setBackgroundResource(R.drawable.bg_step_circle_inactive)
                circle.text = index.toString()
                circle.setTextColor(ContextCompat.getColor(this, R.color.text_gray))
                label.setTextColor(ContextCompat.getColor(this, R.color.text_gray))
                label.paint.isFakeBoldText = false
            }
        }
    }

    private fun renderProgressBar() {
        binding.progressTrack.post {
            val trackWidth = binding.progressTrack.width
            if (trackWidth <= 0) return@post
            val fraction = when (currentStep) {
                ApplyStep.VERIFY -> 0.5f
                ApplyStep.SIGN -> 0.75f
                ApplyStep.MODEL -> 0.92f
                ApplyStep.DONE -> 1f
            }
            val fillWidth = (trackWidth * fraction).toInt().coerceAtLeast(0)
            binding.progressFill.layoutParams = binding.progressFill.layoutParams.apply {
                width = fillWidth
            }
            val thumbSize = binding.progressThumb.layoutParams.width
            val thumbStart = (fillWidth - thumbSize / 2).coerceIn(0, trackWidth - thumbSize)
            (binding.progressThumb.layoutParams as FrameLayout.LayoutParams).marginStart = thumbStart
            binding.progressFill.requestLayout()
            binding.progressThumb.requestLayout()
        }
    }

    private fun renderStepCards() {
        bindStepCard(
            card = binding.stepCardVerify,
            stepCircle = binding.tvCardStep1,
            statusView = binding.tvStatusVerify,
            chevronView = binding.ivChevronVerify,
            index = 1,
            state = stepState(1)
        )
        bindStepCard(
            card = binding.stepCardSign,
            stepCircle = binding.tvCardStep2,
            statusView = binding.tvStatusSign,
            chevronView = null,
            index = 2,
            state = stepState(2)
        )
        bindStepCard(
            card = binding.stepCardModel,
            stepCircle = binding.tvCardStep3,
            statusView = binding.tvStatusModel,
            chevronView = null,
            index = 3,
            state = stepState(3)
        )
    }

    private fun bindStepCard(
        card: MaterialCardView,
        stepCircle: TextView,
        statusView: TextView,
        chevronView: View?,
        index: Int,
        state: StepVisualState
    ) {
        val density = resources.displayMetrics.density
        when (state) {
            StepVisualState.ACTIVE -> {
                card.strokeWidth = (1.5f * density).toInt()
                card.strokeColor = ContextCompat.getColor(this, R.color.brand_primary)
                card.cardElevation = 0f
            }
            else -> {
                card.strokeWidth = 0
                card.cardElevation = if (state == StepVisualState.PENDING) 2f * density else 1f * density
            }
        }
        when (state) {
            StepVisualState.DONE -> {
                stepCircle.setBackgroundResource(R.drawable.bg_step_circle_active)
                stepCircle.text = "✓"
                stepCircle.setTextColor(ContextCompat.getColor(this, R.color.card_white))
                bindStatusTag(statusView, done = true)
                chevronView?.visibility = View.GONE
            }
            StepVisualState.ACTIVE -> {
                stepCircle.setBackgroundResource(R.drawable.bg_step_circle_active)
                stepCircle.text = index.toString()
                stepCircle.setTextColor(ContextCompat.getColor(this, R.color.card_white))
                statusView.visibility = View.GONE
                chevronView?.visibility = View.VISIBLE
            }
            StepVisualState.PENDING -> {
                stepCircle.setBackgroundResource(R.drawable.bg_step_circle_inactive)
                stepCircle.text = index.toString()
                stepCircle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                bindStatusTag(statusView, done = false)
                chevronView?.visibility = View.GONE
            }
        }
    }

    private fun bindStatusTag(statusView: TextView, done: Boolean) {
        statusView.visibility = View.VISIBLE
        statusView.text = getString(
            if (done) R.string.device_apply_status_done else R.string.device_apply_status_pending
        )
        statusView.setBackgroundResource(
            if (done) R.drawable.bg_status_done else R.drawable.bg_status_pending
        )
        statusView.setTextColor(
            ContextCompat.getColor(
                this,
                if (done) R.color.brand_orange else R.color.text_gray
            )
        )
    }

    private fun renderBottomArea() {
        binding.btnAction.visibility = View.VISIBLE
        when (currentStep) {
            ApplyStep.VERIFY -> {
                binding.tvBottomHint.setText(R.string.device_apply_bottom_hint)
                binding.btnAction.setText(R.string.device_apply_action_verify)
            }
            ApplyStep.SIGN -> {
                binding.tvBottomHint.setText(R.string.device_apply_bottom_hint_sign)
                binding.btnAction.setText(R.string.device_apply_action_sign)
            }
            ApplyStep.MODEL -> {
                binding.tvBottomHint.setText(R.string.device_apply_bottom_hint_model)
                binding.btnAction.setText(R.string.device_apply_action_model)
            }
            ApplyStep.DONE -> {
                if (applyPersisted) {
                    val connected = Prefs(this).hasConnectedDevice
                    binding.tvBottomHint.setText(
                        if (connected) R.string.device_apply_bottom_hint_connected
                        else R.string.device_apply_bottom_hint_complete
                    )
                    if (connected) {
                        binding.btnAction.visibility = View.GONE
                    } else {
                        binding.btnAction.setText(R.string.device_apply_action_connect)
                    }
                } else {
                    binding.tvBottomHint.setText(R.string.device_apply_bottom_hint_done)
                    binding.btnAction.setText(R.string.device_apply_action_done)
                }
            }
        }
        setActionEnabled(binding.btnAction.visibility == View.VISIBLE)
    }

    private fun setActionEnabled(enabled: Boolean) {
        binding.btnAction.isEnabled = enabled
        binding.btnAction.setBackgroundResource(
            if (enabled) R.drawable.bg_btn_login else R.drawable.bg_btn_disabled
        )
        binding.btnAction.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.card_white else R.color.btn_disabled_text
            )
        )
    }

    private enum class ApplyStep {
        VERIFY, SIGN, MODEL, DONE
    }

    private enum class StepVisualState {
        DONE, ACTIVE, PENDING
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DeviceApplyEntryActivity::class.java))
        }
    }
}
