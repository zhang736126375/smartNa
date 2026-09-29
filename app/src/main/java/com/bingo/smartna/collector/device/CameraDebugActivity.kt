package com.bingo.smartna.collector.device

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.capture.CaptureActivity
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.device.ego.EgoCollectorSession
import com.bingo.smartna.databinding.ActivityCameraDebugBinding
import com.blankj.utilcode.util.ClickUtils

class CameraDebugActivity : BaseActivity<ActivityCameraDebugBinding, BaseViewModel>() {

    private lateinit var task: Task
    private var handingOff = false
    private val session = EgoCollectorSession.get()

    override fun inflateBinding() = ActivityCameraDebugBinding.inflate(layoutInflater)

    override fun initParam() {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        task = MockDataSource.allTasks.find { it.id == taskId }
            ?: error("Task not found: $taskId")
    }

    override fun initData() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        ClickUtils.applySingleDebouncing(binding.btnStart) {
            if (!session.isStreaming) {
                Toast.makeText(this, R.string.debug_connecting, Toast.LENGTH_SHORT).show()
                return@applySingleDebouncing
            }
            handingOff = true
            CaptureActivity.start(this, task)
            finish()
        }
        if (!Prefs(this).hasConnectedDevice) {
            DevicePageActivity.start(this, task.id)
            finish()
            return
        }
        binding.tvPreviewHint.setText(R.string.debug_connecting)
        binding.btnStart.isEnabled = false
        attachPreview()
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
        session.detach(this, !handingOff && !isChangingConfigurations)
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
                binding.btnStart.isEnabled = streaming
            }

            override fun onError(message: String) {
                binding.tvPreviewHint.text = message
                Toast.makeText(
                    this@CameraDebugActivity,
                    getString(R.string.debug_stream_failed, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
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
