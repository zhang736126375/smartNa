package com.bingo.smartna.collector.upload

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.MainActivity
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.capture.CaptureActivity
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.databinding.ActivityUploadBinding
import com.blankj.utilcode.util.ClickUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class UploadActivity : BaseActivity<ActivityUploadBinding, CollectorViewModel>() {

    private lateinit var taskId: String
    private lateinit var clipId: String
    private var clipIndex: Int = 1
    private var durationMs: Long = 0L
    private var uploading = false

    override fun inflateBinding() = ActivityUploadBinding.inflate(layoutInflater)

    override fun initViewModel(): CollectorViewModel = CollectorViewModels.get(application)

    override fun initParam() {
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        clipId = intent.getStringExtra(EXTRA_CLIP_ID).orEmpty()
        clipIndex = intent.getIntExtra(EXTRA_CLIP_INDEX, 1)
        durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
    }

    override fun initData() {
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty()
        binding.tvTaskTitle.text = taskTitle
        binding.tvClipInfo.text = getString(R.string.upload_clip_info, clipIndex)
        binding.tvDuration.text = getString(R.string.upload_clip_duration, formatDuration(durationMs))
        binding.tvStatus.setText(R.string.upload_ready)
        binding.progressBar.progress = 0
        binding.btnStartUpload.visibility = View.VISIBLE
        binding.btnContinueCapture.visibility = View.GONE
        binding.btnToTasks.visibility = View.GONE
        ClickUtils.applySingleDebouncing(binding.btnToTasks) {
            startActivity(MainActivity.intentForTab(this, R.id.nav_tasks))
            finish()
        }
        ClickUtils.applySingleDebouncing(binding.btnContinueCapture) {
            val task = MockDataSource.allTasks.find { it.id == taskId }
                ?: viewModel.ui.value?.userTaskFor(taskId)?.task
            if (task != null) {
                CaptureActivity.start(this, task, demoMode = true)
            }
            finish()
        }
        ClickUtils.applySingleDebouncing(binding.btnStartUpload) {
            if (!uploading) startFakeUpload()
        }
    }

    private fun startFakeUpload() {
        uploading = true
        binding.btnStartUpload.isEnabled = false
        binding.btnStartUpload.visibility = View.GONE
        lifecycleScope.launch {
            for (progress in 1..100 step 5) {
                binding.progressBar.progress = progress
                binding.tvStatus.text = getString(R.string.upload_progress, progress)
                delay(80)
            }
            viewModel.completeUpload(clipId)
            binding.tvStatus.setText(R.string.upload_success)
            val userTask = viewModel.ui.value?.userTaskFor(taskId)
            val canCaptureMore = userTask?.canCaptureMoreDemo() == true
            binding.btnContinueCapture.visibility = if (canCaptureMore) View.VISIBLE else View.GONE
            binding.btnToTasks.visibility = View.VISIBLE
            uploading = false
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0L))
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            String.format(Locale.getDefault(), "%d分%02d秒", minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d秒", seconds)
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TASK_TITLE = "task_title"
        private const val EXTRA_CLIP_ID = "clip_id"
        private const val EXTRA_CLIP_INDEX = "clip_index"
        private const val EXTRA_DURATION_MS = "duration_ms"

        fun start(
            context: Context,
            taskId: String,
            taskTitle: String,
            clipId: String,
            clipIndex: Int,
            durationMs: Long = 0L
        ) {
            context.startActivity(
                Intent(context, UploadActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, taskId)
                    .putExtra(EXTRA_TASK_TITLE, taskTitle)
                    .putExtra(EXTRA_CLIP_ID, clipId)
                    .putExtra(EXTRA_CLIP_INDEX, clipIndex)
                    .putExtra(EXTRA_DURATION_MS, durationMs)
            )
        }
    }
}
