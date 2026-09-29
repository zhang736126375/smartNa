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
import com.bingo.smartna.databinding.ActivityDevicePageBinding
import com.blankj.utilcode.util.ClickUtils

class DevicePageActivity : BaseActivity<ActivityDevicePageBinding, BaseViewModel>() {

    private var taskId: String = ""
    private var moreClickCount = 0
    private var moreClickWindowStart = 0L

    private val connectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        renderState()
        if (Prefs(this).hasConnectedDevice && taskId.isNotBlank()) {
            CameraDebugActivity.start(this, taskId)
            finish()
        }
    }

    override fun inflateBinding() = ActivityDevicePageBinding.inflate(layoutInflater)

    override fun initParam() {
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
    }

    override fun initData() {
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        binding.btnMore.setOnClickListener { onMoreClicked() }
        ClickUtils.applySingleDebouncing(binding.btnConnect) {
            connectLauncher.launch(Intent(this, ConnectKitActivity::class.java))
        }
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
            startActivity(Intent(this, EgoNetDeviceFoundActivity::class.java))
        }
    }

    private fun renderState() {
        val connected = Prefs(this).hasConnectedDevice
        binding.emptyPanel.visibility = if (connected) View.GONE else View.VISIBLE
        binding.connectedPanel.visibility = if (connected) View.VISIBLE else View.GONE
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
