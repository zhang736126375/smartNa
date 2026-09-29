package com.bingo.smartna.collector.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.data.model.ClipRecord
import com.bingo.smartna.collector.data.model.Task
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

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var taskTitle: String = ""
    private var taskId: String = ""
    private var targetClips: Int = 1
    private var doneClips: Int = 0
    private var demoMode: Boolean = true

    private var capturePhase = CapturePhase.PREVIEW
    private var recordStartElapsed = 0L
    private var pausedAccumulated = 0L
    private var paused = false
    private var lastDurationMs = 0L
    private var timerJob: Job? = null
    private var pendingClip: ClipRecord? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true || hasCameraPermission()
        if (cameraOk) {
            bindCamera()
        } else {
            Toast.makeText(this, R.string.capture_camera_denied, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun inflateBinding() = ActivityCaptureBinding.inflate(layoutInflater)

    override fun initViewModel(): CollectorViewModel = CollectorViewModels.get(application)

    override fun initParam() {
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty()
        targetClips = intent.getIntExtra(EXTRA_TARGET_CLIPS, 1)
        doneClips = intent.getIntExtra(EXTRA_DONE_CLIPS, 0)
        demoMode = intent.getBooleanExtra(EXTRA_DEMO_MODE, true)
    }

    override fun initData() {
        viewModel.ui.value?.userTaskFor(taskId)?.let { userTask ->
            doneClips = userTask.doneClips
            targetClips = userTask.demoTargetClips()
        }
        ClickUtils.applySingleDebouncing(binding.btnBack) { onBackPressedInternal() }
        ClickUtils.applySingleDebouncing(binding.btnRecord) { onRecordButtonClick() }
        ClickUtils.applySingleDebouncing(binding.btnPause) { togglePause() }
        ClickUtils.applySingleDebouncing(binding.btnUpload) { onUploadClick() }
        ClickUtils.applySingleDebouncing(binding.btnRetake) { onRetakeClick() }
        if (demoMode) {
            binding.demoPanel.visibility = View.VISIBLE
            binding.previewView.visibility = View.GONE
        } else {
            binding.demoPanel.visibility = View.GONE
            binding.previewView.visibility = View.VISIBLE
            if (hasCameraPermission()) {
                bindCamera()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        }
        renderPreviewPhase()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        recording?.stop()
        recording = null
        super.onDestroy()
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
        binding.btnPause.visibility = if (recording) View.VISIBLE else View.GONE
        (binding.btnRecord.layoutParams as LinearLayout.LayoutParams).marginStart =
            if (recording) dp(12) else 0
        binding.btnRecord.requestLayout()

        when {
            capturePhase == CapturePhase.PREVIEW -> {
                binding.recChip.setBackgroundResource(R.drawable.bg_hchip)
                binding.recDot.visibility = View.GONE
                binding.tvRecLabel.setText(R.string.capture_ready)
                binding.tvRecLabel.setTextColor(ContextCompat.getColor(this, R.color.hud_gold))
                binding.btnRecord.setText(R.string.capture_demo_start)
                binding.tvStatus.setText(R.string.capture_demo_hint)
            }
            recording && paused -> {
                binding.recChip.setBackgroundResource(R.drawable.bg_hchip)
                binding.recDot.visibility = View.VISIBLE
                binding.tvRecLabel.setText(R.string.capture_paused)
                binding.tvRecLabel.setTextColor(ContextCompat.getColor(this, R.color.hud_gold))
                binding.btnPause.setText(R.string.capture_resume)
                binding.btnRecord.setText(R.string.capture_finish)
                binding.tvStatus.setText(R.string.capture_paused)
            }
            recording -> {
                binding.recChip.setBackgroundResource(R.drawable.bg_hchip_rec)
                binding.recDot.visibility = View.VISIBLE
                binding.tvRecLabel.setText(R.string.capture_recording)
                binding.tvRecLabel.setTextColor(ContextCompat.getColor(this, R.color.rec_red))
                binding.btnPause.setText(R.string.capture_pause)
                binding.btnRecord.setText(R.string.capture_finish)
                binding.tvStatus.setText(R.string.capture_demo_hint)
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

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val capture = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            videoCapture = capture
        }, ContextCompat.getMainExecutor(this))
    }

    private enum class CapturePhase {
        PREVIEW, RECORDING, COMPLETE
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TASK_TITLE = "task_title"
        private const val EXTRA_TARGET_CLIPS = "target_clips"
        private const val EXTRA_DONE_CLIPS = "done_clips"
        private const val EXTRA_DEMO_MODE = "demo_mode"
        const val CLIPS_DIR = "clips"

        fun start(context: Context, task: Task, demoMode: Boolean = true) {
            context.startActivity(
                Intent(context, CaptureActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, task.id)
                    .putExtra(EXTRA_TASK_TITLE, task.title)
                    .putExtra(EXTRA_TARGET_CLIPS, task.targetClips)
                    .putExtra(EXTRA_DONE_CLIPS, task.doneClips)
                    .putExtra(EXTRA_DEMO_MODE, demoMode)
            )
        }
    }
}
