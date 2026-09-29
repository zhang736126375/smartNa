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
        binding.tvClipInfo.text = getString(R.string.upload_clip_info, clipIndex)
        val sizeMb = ((durationMs / 1000L) * 7L).coerceIn(80L, 420L).toInt()
        binding.tvDuration.text = getString(R.string.upload_clip_size, sizeMb)
        val reward = viewModel.ui.value?.userTaskFor(taskId)?.task?.reward
            ?: MockDataSource.allTasks.find { it.id == taskId }?.reward
            ?: 0.0
        binding.tvEarnPreview.text = getString(R.string.upload_earn_preview, reward)
        val canQueue = viewModel.ui.value?.userTaskFor(taskId)?.canCaptureMoreDemo() == true
        if (canQueue) {
            binding.nextCard.visibility = View.VISIBLE
            binding.tvNextQueue.text = getString(R.string.upload_next_queue, clipIndex + 1)
        } else {
            binding.nextCard.visibility = View.GONE
        }
        binding.tvUploadTitle.setText(R.string.upload_title)
        binding.tvStatus.text = getString(R.string.upload_progress, 0)
        binding.progressBar.progress = 0
        binding.btnStartUpload.visibility = View.GONE
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
        startFakeUpload()
    }

    private fun startFakeUpload() {
        if (uploading) return
        uploading = true
        binding.btnStartUpload.visibility = View.GONE
        binding.tvUploadTitle.setText(R.string.upload_title)
        lifecycleScope.launch {
            for (progress in 0..100 step 2) {
                binding.progressBar.progress = progress
                binding.tvStatus.text = getString(R.string.upload_progress, progress)
                delay(40)
            }
            viewModel.completeUpload(clipId)
            binding.tvUploadTitle.setText(R.string.upload_title_done)
            binding.tvStatus.text = getString(R.string.upload_progress, 100)
            val userTask = viewModel.ui.value?.userTaskFor(taskId)
            val canCaptureMore = userTask?.canCaptureMoreDemo() == true
            binding.nextCard.visibility = View.GONE
            binding.btnContinueCapture.visibility = if (canCaptureMore) View.VISIBLE else View.GONE
            binding.btnToTasks.visibility = View.VISIBLE
            uploading = false
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
