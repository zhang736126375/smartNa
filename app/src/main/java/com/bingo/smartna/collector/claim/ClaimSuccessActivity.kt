package com.bingo.smartna.collector.claim

import android.content.Context
import android.content.Intent
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.device.CameraDebugActivity
import com.bingo.smartna.collector.device.DevicePageActivity
import com.bingo.smartna.databinding.ActivityClaimSuccessBinding
import com.blankj.utilcode.util.ClickUtils

class ClaimSuccessActivity : BaseActivity<ActivityClaimSuccessBinding, BaseViewModel>() {

    private lateinit var taskId: String

    override fun inflateBinding() = ActivityClaimSuccessBinding.inflate(layoutInflater)

    override fun initParam() {
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
    }

    override fun initData() {
        val hasDevice = Prefs(this).hasConnectedDevice
        binding.btnPrimary.text = getString(
            if (hasDevice) R.string.claim_go_debug else R.string.claim_connect_device
        )
        ClickUtils.applySingleDebouncing(binding.backdrop) { finish() }
        ClickUtils.applySingleDebouncing(binding.btnPrimary) {
            if (hasDevice) {
                CameraDebugActivity.start(this, taskId)
            } else {
                DevicePageActivity.start(this, taskId)
            }
            finish()
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context, taskId: String) {
            context.startActivity(
                Intent(context, ClaimSuccessActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, taskId)
            )
        }
    }
}
