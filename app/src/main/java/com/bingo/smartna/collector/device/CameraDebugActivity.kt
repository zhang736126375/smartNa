package com.bingo.smartna.collector.device

import android.content.Context
import android.content.Intent
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.capture.CaptureActivity
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.databinding.ActivityCameraDebugBinding
import com.blankj.utilcode.util.ClickUtils

class CameraDebugActivity : BaseActivity<ActivityCameraDebugBinding, BaseViewModel>() {

    private lateinit var task: Task

    override fun inflateBinding() = ActivityCameraDebugBinding.inflate(layoutInflater)

    override fun initParam() {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        task = MockDataSource.allTasks.find { it.id == taskId }
            ?: error("Task not found: $taskId")
    }

    override fun initData() {
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        ClickUtils.applySingleDebouncing(binding.btnStart) {
            CaptureActivity.start(this, task, demoMode = true)
            finish()
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context, task: Task) {
            context.startActivity(
                Intent(context, CameraDebugActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, task.id)
            )
        }

        fun start(context: Context, taskId: String) {
            context.startActivity(
                Intent(context, CameraDebugActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, taskId)
            )
        }
    }
}
