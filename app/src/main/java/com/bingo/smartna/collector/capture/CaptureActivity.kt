package com.bingo.smartna.collector.capture

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.ClipRecord
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.device.DevicePageActivity
import com.bingo.smartna.collector.device.ego.EgoCollectorSession
import com.bingo.smartna.collector.upload.UploadActivity
import com.bingo.smartna.databinding.ActivityCaptureBinding
import com.blankj.utilcode.util.ClickUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class CaptureActivity : BaseActivity<ActivityCaptureBinding, CollectorViewModel>() {

    private var taskTitle: String = ""
    private var taskId: String = ""
    private var targetClips: Int = 1
    private var doneClips: Int = 0

    private var capturePhase = CapturePhase.PREVIEW
    private var recordStartElapsed = 0L
    private var pausedAccumulated = 0L
    private var paused = false
    private var lastDurationMs = 0L
    private var timerJob: Job? = null
    private var pendingClip: ClipRecord? = null
    private val session = EgoCollectorSession.get()

    override fun inflateBinding() = ActivityCaptureBinding.inflate(layoutInflater)

    override fun initViewModel(): CollectorViewModel = CollectorViewModels.get(application)

    override fun initParam() {
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty()
        targetClips = intent.getIntExtra(EXTRA_TARGET_CLIPS, 1)
        doneClips = intent.getIntExtra(EXTRA_DONE_CLIPS, 0)
    }

    override fun initData() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.ui.value?.userTaskFor(taskId)?.let { userTask ->
            doneClips = userTask.doneClips
            targetClips = userTask.demoTargetClips()
        }
        ClickUtils.applySingleDebouncing(binding.btnBack) { onBackPressedInternal() }
        ClickUtils.applySingleDebouncing(binding.btnRecord) { onRecordButtonClick() }
        ClickUtils.applySingleDebouncing(binding.btnPause) { togglePause() }
        ClickUtils.applySingleDebouncing(binding.btnUpload) { onUploadClick() }
        ClickUtils.applySingleDebouncing(binding.btnRetake) { onRetakeClick() }
        if (!Prefs(this).hasConnectedDevice) {
            DevicePageActivity.start(this, taskId)
            finish()
            return
        }
        attachPreview()
        renderPreviewPhase()
    }

    override fun onResume() {
        super.onResume()
        session.onHostResume()
    }

    override fun onPause() {
        session.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        if (capturePhase == CapturePhase.RECORDING) {
            session.stopCollecting()
        }
        session.detach(this, !isChangingConfigurations)
        super.onDestroy()
    }

    private fun attachPreview() {
        val targets = EgoCollectorSession.PreviewTargets().apply {
            left = binding.glLeft
            right = binding.glRight
            leftTv = binding.tvLeft
            rightTv = binding.tvRight
            rightPanel = binding.previewRightSlot
        }
        session.attach(this, targets, object : EgoCollectorSession.Listener {
            override fun onStatus(message: String) {
                binding.tvPreviewHint.text = message
            }

            override fun onStreamingChanged(streaming: Boolean, stereo: Boolean) {
                binding.previewRightSlot.visibility = if (stereo) View.VISIBLE else View.GONE
            }

            override fun onError(message: String) {
                binding.tvPreviewHint.text = message
                Toast.makeText(this@CaptureActivity, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun onBackPressedInternal() {
        when {
            capturePhase == CapturePhase.RECORDING -> finishRecording()
            capturePhase == CapturePhase.COMPLETE -> onRetakeClick()
            else -> finish()
        }
    }

    private fun onRecordButtonClick() {
        when (capturePhase) {
            CapturePhase.PREVIEW -> startRecording()
            CapturePhase.RECORDING -> finishRecording()
            CapturePhase.COMPLETE -> Unit
        }
    }

    private fun startRecording() {
        if (!session.isStreaming) {
            Toast.makeText(this, R.string.debug_connecting, Toast.LENGTH_SHORT).show()
            return
        }
        if (!session.startCollecting()) {
            return
        }
        capturePhase = CapturePhase.RECORDING
        paused = false
        pausedAccumulated = 0L
        recordStartElapsed = SystemClock.elapsedRealtime()
        pendingClip = null
        renderHud(elapsedMs = 0L)
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive && capturePhase == CapturePhase.RECORDING) {
                renderHud(currentElapsed())
                delay(200)
            }
        }
    }

    private fun togglePause() {
        if (capturePhase != CapturePhase.RECORDING) return
        if (paused) {
            recordStartElapsed = SystemClock.elapsedRealtime()
            paused = false
        } else {
            pausedAccumulated = currentElapsed()
            paused = true
        }
        renderHud(currentElapsed())
    }

    private fun currentElapsed(): Long {
        return if (paused) {
            pausedAccumulated
        } else {
            pausedAccumulated + (SystemClock.elapsedRealtime() - recordStartElapsed)
        }
    }

    private fun finishRecording() {
        timerJob?.cancel()
        session.stopCollecting()
        lastDurationMs = currentElapsed().coerceAtLeast(1000L)
        val clip = viewModel.recordDemoClip(taskId, lastDurationMs)
        if (clip == null) {
            Toast.makeText(this, R.string.capture_demo_failed, Toast.LENGTH_SHORT).show()
            renderPreviewPhase()
            return
        }
        pendingClip = clip
        doneClips = clip.clipIndex
        showCompletePhase(clip)
    }

    private fun showCompletePhase(clip: ClipRecord) {
        capturePhase = CapturePhase.COMPLETE
        binding.completePanel.visibility = View.VISIBLE
        binding.bottomPanel.visibility = View.GONE
        binding.tvCompleteDuration.text = getString(
            R.string.capture_complete_duration,
            formatSpokenDuration(clip.durationMs)
        )
        renderHud(clip.durationMs)
    }

    private fun onUploadClick() {
        val clip = pendingClip ?: return
        UploadActivity.start(
            this,
            taskId,
            taskTitle,
            clip.id,
            clip.clipIndex,
            clip.durationMs
        )
        finish()
    }

    private fun onRetakeClick() {
        if (pendingClip != null) {
            viewModel.rollbackDemoClip(pendingClip!!.id)
            pendingClip = null
            viewModel.ui.value?.userTaskFor(taskId)?.let { userTask ->
                doneClips = userTask.doneClips
                targetClips = userTask.demoTargetClips()
            }
        }
        renderPreviewPhase()
    }

    private fun renderPreviewPhase() {
        capturePhase = CapturePhase.PREVIEW
        paused = false
        timerJob?.cancel()
        binding.completePanel.visibility = View.GONE
        binding.bottomPanel.visibility = View.VISIBLE
        renderHud(0L)
    }

    private fun renderHud(elapsedMs: Long) {
        val clipNo = (doneClips + if (capturePhase == CapturePhase.COMPLETE) 0 else 1).coerceAtLeast(1)
        val shortTitle = taskTitle.replace("【Demo】", "").ifBlank { getString(R.string.capture_title) }
        binding.tvTitle.text = getString(R.string.capture_task_chip, shortTitle, clipNo)
        val frames = (elapsedMs / 250L).toInt().coerceAtLeast(if (capturePhase == CapturePhase.PREVIEW) 0 else 1)
        binding.tvProgress.text = getString(R.string.capture_frames, frames)
        binding.tvRecTimer.text = formatClock(elapsedMs)

        val recording = capturePhase == CapturePhase.RECORDING
        binding.btnPause.visibility = View.GONE
        (binding.btnRecord.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        binding.btnRecord.requestLayout()

        when {
            capturePhase == CapturePhase.PREVIEW -> {
                binding.recChip.setBackgroundResource(R.drawable.bg_hchip)
                binding.recDot.visibility = View.GONE
                binding.tvRecLabel.setText(R.string.capture_ready)
                binding.tvRecLabel.setTextColor(ContextCompat.getColor(this, R.color.hud_gold))
                binding.btnRecord.setText(R.string.capture_demo_start)
                binding.tvStatus.setText(R.string.capture_stream_hint)
            }
            recording -> {
                binding.recChip.setBackgroundResource(R.drawable.bg_hchip_rec)
                binding.recDot.visibility = View.VISIBLE
                binding.tvRecLabel.setText(R.string.capture_recording)
                binding.tvRecLabel.setTextColor(ContextCompat.getColor(this, R.color.rec_red))
                binding.btnRecord.setText(R.string.capture_finish)
                binding.tvStatus.setText(R.string.capture_recording)
            }
            else -> {
                binding.recChip.setBackgroundResource(R.drawable.bg_hchip)
                binding.recDot.visibility = View.GONE
                binding.tvRecLabel.setText(R.string.capture_finish)
                binding.tvRecLabel.setTextColor(ContextCompat.getColor(this, R.color.hud_ok))
            }
        }
        renderSegments(recording)
    }

    private fun renderSegments(recording: Boolean) {
        binding.segmentRow.removeAllViews()
        val target = targetClips.coerceAtLeast(1)
        repeat(target) { index ->
            val bar = View(this)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) lp.marginStart = dp(4)
            bar.layoutParams = lp
            val filled = index < doneClips || (recording && index == doneClips) ||
                (capturePhase == CapturePhase.COMPLETE && index == doneClips - 1)
            bar.setBackgroundResource(if (filled) R.drawable.bg_segment_on else R.drawable.bg_segment_off)
            binding.segmentRow.addView(bar)
        }
    }

    private fun formatClock(durationMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0L))
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun formatSpokenDuration(durationMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            String.format(Locale.CHINA, "%d分%02d秒", minutes, seconds)
        } else {
            String.format(Locale.CHINA, "%d秒", seconds)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private enum class CapturePhase {
        PREVIEW, RECORDING, COMPLETE
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TASK_TITLE = "task_title"
        private const val EXTRA_TARGET_CLIPS = "target_clips"
        private const val EXTRA_DONE_CLIPS = "done_clips"
        const val CLIPS_DIR = "clips"

        fun start(context: Context, task: Task) {
            context.startActivity(
                Intent(context, CaptureActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, task.id)
                    .putExtra(EXTRA_TASK_TITLE, task.title)
                    .putExtra(EXTRA_TARGET_CLIPS, task.targetClips)
                    .putExtra(EXTRA_DONE_CLIPS, task.doneClips)
            )
        }
    }
}
