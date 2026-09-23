package com.bingo.smartna.collector.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
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
import java.io.File
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
        binding.tvTitle.text = taskTitle.ifBlank { getString(R.string.capture_title) }
        updateProgressText()
        ClickUtils.applySingleDebouncing(binding.btnBack) { onBackPressedInternal() }
        ClickUtils.applySingleDebouncing(binding.btnRecord) { onRecordButtonClick() }
        ClickUtils.applySingleDebouncing(binding.btnUpload) { onUploadClick() }
        ClickUtils.applySingleDebouncing(binding.btnRetake) { onRetakeClick() }
        if (demoMode) {
            binding.demoPanel.visibility = View.VISIBLE
            binding.previewView.visibility = View.GONE
            renderPreviewPhase()
        } else {
            binding.demoPanel.visibility = View.GONE
            binding.previewView.visibility = View.VISIBLE
            if (hasCameraPermission()) {
                bindCamera()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        }
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
        recordStartElapsed = SystemClock.elapsedRealtime()
        pendingClip = null
        binding.tvDeviceStatus.setText(R.string.capture_device_status_recording)
        binding.tvRecTimer.visibility = View.VISIBLE
        binding.tvRecTimer.text = getString(R.string.capture_rec_timer, formatDuration(0L))
        binding.btnRecord.setText(R.string.capture_finish)
        binding.tvStatus.setText(R.string.capture_recording)
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive && capturePhase == CapturePhase.RECORDING) {
                val elapsed = SystemClock.elapsedRealtime() - recordStartElapsed
                binding.tvRecTimer.text = getString(R.string.capture_rec_timer, formatDuration(elapsed))
                delay(500)
            }
        }
    }

    private fun finishRecording() {
        timerJob?.cancel()
        lastDurationMs = (SystemClock.elapsedRealtime() - recordStartElapsed).coerceAtLeast(1000L)
        val clip = viewModel.recordDemoClip(taskId, lastDurationMs)
        if (clip == null) {
            Toast.makeText(this, R.string.capture_demo_failed, Toast.LENGTH_SHORT).show()
            renderPreviewPhase()
            return
        }
        pendingClip = clip
        doneClips = clip.clipIndex
        updateProgressText()
        showCompletePhase(clip)
    }

    private fun showCompletePhase(clip: ClipRecord) {
        capturePhase = CapturePhase.COMPLETE
        binding.completePanel.visibility = View.VISIBLE
        binding.bottomPanel.visibility = View.GONE
        binding.tvRecTimer.visibility = View.GONE
        binding.tvCompleteDuration.text = getString(
            R.string.capture_complete_duration,
            formatDuration(clip.durationMs)
        )
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
            updateProgressText()
        }
        renderPreviewPhase()
    }

    private fun renderPreviewPhase() {
        capturePhase = CapturePhase.PREVIEW
        timerJob?.cancel()
        binding.completePanel.visibility = View.GONE
        binding.bottomPanel.visibility = View.VISIBLE
        binding.tvRecTimer.visibility = View.GONE
        binding.tvDeviceStatus.setText(R.string.capture_device_status_ready)
        binding.btnRecord.isEnabled = true
        binding.btnRecord.setText(R.string.capture_demo_start)
        binding.tvStatus.setText(R.string.capture_demo_hint)
    }

    private fun updateProgressText() {
        val target = if (targetClips > 0) targetClips else 1
        binding.tvProgress.text = getString(R.string.capture_progress, doneClips, target)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            String.format(Locale.getDefault(), "%d分%02d秒", minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d秒", seconds)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
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
            binding.tvStatus.setText(R.string.capture_hint)
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
