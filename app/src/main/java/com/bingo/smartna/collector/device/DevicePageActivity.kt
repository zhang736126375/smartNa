package com.bingo.smartna.collector.device

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.device.ego.EgoNetDeviceFoundActivity
import com.bingo.smartna.collector.device.ego.EgoSampleNetActivity
import com.bingo.smartna.databinding.ActivityDevicePageBinding
import com.blankj.utilcode.util.ClickUtils

class DevicePageActivity : BaseActivity<ActivityDevicePageBinding, BaseViewModel>() {

    private var taskId: String = ""
    private var moreClickCount = 0
    private var moreClickWindowStart = 0L
    private var pendingTaskAfterConnect = false

    private val connectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        renderState()
        maybeContinueTask()
    }

    override fun inflateBinding() = ActivityDevicePageBinding.inflate(layoutInflater)

    override fun initParam() {
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
    }

    override fun initData() {
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        binding.btnMore.setOnClickListener { onMoreClicked() }
        ClickUtils.applySingleDebouncing(binding.btnConnect) {
            pendingTaskAfterConnect = taskId.isNotBlank()
            launchProvisioning()
        }
        ClickUtils.applySingleDebouncing(binding.btnPreview) { openPreview() }
        ClickUtils.applySingleDebouncing(binding.btnDisconnect) {
            Prefs(this).clearConnectedDevice()
            Toast.makeText(this, R.string.device_unbind_done, Toast.LENGTH_SHORT).show()
            renderState()
        }
        ClickUtils.applySingleDebouncing(binding.btnDebug) {
            if (taskId.isNotBlank()) {
                CameraDebugActivity.start(this, taskId)
                finish()
            }
        }
        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderState()
        maybeContinueTask()
    }

    private fun launchProvisioning() {
        connectLauncher.launch(Intent(this, EgoNetDeviceFoundActivity::class.java))
    }

    private fun openPreview() {
        val prefs = Prefs(this)
        val ip = prefs.connectedIp
        if (ip.isNullOrBlank()) {
            launchProvisioning()
            return
        }
        EgoSampleNetActivity.start(this, ip, prefs.connectedPort)
    }

    private fun maybeContinueTask() {
        if (!pendingTaskAfterConnect || taskId.isBlank() || !Prefs(this).hasConnectedDevice) return
        pendingTaskAfterConnect = false
        CameraDebugActivity.start(this, taskId)
        finish()
    }

    private fun onMoreClicked() {
        val now = SystemClock.elapsedRealtime()
        if (now - moreClickWindowStart > MORE_CLICK_WINDOW_MS) {
            moreClickCount = 0
            moreClickWindowStart = now
        }
        moreClickCount += 1
        if (moreClickCount >= MORE_CLICK_TO_SCAN) {
            moreClickCount = 0
            launchProvisioning()
        }
    }

    private fun renderState() {
        val prefs = Prefs(this)
        val connected = prefs.hasConnectedDevice
        binding.emptyPanel.visibility = if (connected) View.GONE else View.VISIBLE
        binding.connectedPanel.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) {
            val ip = prefs.connectedIp.orEmpty()
            binding.tvDeviceSerial.text = getString(R.string.device_address, ip, prefs.connectedPort)
        }
        binding.btnDebug.visibility =
            if (connected && taskId.isNotBlank()) View.VISIBLE else View.GONE
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val MORE_CLICK_TO_SCAN = 3
        private const val MORE_CLICK_WINDOW_MS = 2000L

        fun start(context: Context, taskId: String? = null) {
            context.startActivity(
                Intent(context, DevicePageActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, taskId.orEmpty())
            )
        }
    }
}
