package com.bingo.smartna.collector.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.databinding.ActivityCaptureBinding
import com.blankj.utilcode.util.ClickUtils
import java.io.File

class CaptureActivity : BaseActivity<ActivityCaptureBinding, BaseViewModel>() {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var taskTitle: String = ""
    private var taskId: String = ""

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

    override fun initParam() {
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty()
    }

    override fun initData() {
        binding.tvTitle.text = taskTitle.ifBlank { getString(R.string.capture_title) }
        ClickUtils.applySingleDebouncing(binding.btnBack) {
            if (recording != null) stopRecord() else finish()
        }
        ClickUtils.applySingleDebouncing(binding.btnRecord) { toggleRecord() }
        if (hasCameraPermission()) {
            bindCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onDestroy() {
        recording?.stop()
        recording = null
        super.onDestroy()
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

    private fun toggleRecord() {
        if (recording != null) {
            stopRecord()
        } else {
            startRecord()
        }
    }

    private fun startRecord() {
        val capture = videoCapture ?: return
        val dir = getExternalFilesDir(CLIPS_DIR) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "clip_${taskId.ifBlank { "draft" }}_${System.currentTimeMillis()}.mp4")
        val pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        val started = if (hasAudioPermission()) pending.withAudioEnabled() else pending
        recording = started.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.btnRecord.setText(R.string.capture_stop)
                    binding.tvStatus.setText(R.string.capture_recording)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    binding.btnRecord.setText(R.string.capture_start)
                    if (event.hasError()) {
                        binding.tvStatus.text = getString(R.string.capture_failed, event.error)
                    } else {
                        binding.tvStatus.text = getString(R.string.capture_saved, file.absolutePath)
                    }
                }
            }
        }
    }

    private fun stopRecord() {
        recording?.stop()
        recording = null
        binding.btnRecord.setText(R.string.capture_start)
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TASK_TITLE = "task_title"
        const val CLIPS_DIR = "clips"

        fun start(context: Context, task: Task) {
            context.startActivity(
                Intent(context, CaptureActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, task.id)
                    .putExtra(EXTRA_TASK_TITLE, task.title)
            )
        }
    }
}
