package com.bingo.smartna.collector.device

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.device.ego.EgoNetDeviceFoundActivity
import com.bingo.smartna.databinding.ActivityConnectKitBinding
import com.blankj.utilcode.util.ClickUtils

class ConnectKitActivity : BaseActivity<ActivityConnectKitBinding, DeviceConnectViewModel>() {

    private val adapter = DeviceKitAdapter { kit ->
        viewModel.selectedKit = kit
        refreshNext()
    }

    private val provisionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Prefs(this).hasConnectedDevice) {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun inflateBinding() = ActivityConnectKitBinding.inflate(layoutInflater)

    override fun initData() {
        binding.rvKits.layoutManager = LinearLayoutManager(this)
        binding.rvKits.adapter = adapter
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        ClickUtils.applySingleDebouncing(binding.btnNext) { onNext() }
        refreshNext()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs(this).hasConnectedDevice) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun onNext() {
        if (viewModel.selectedKit == null) return
        provisionLauncher.launch(Intent(this, EgoNetDeviceFoundActivity::class.java))
    }

    private fun refreshNext() {
        val enabled = viewModel.selectedKit != null
        binding.btnNext.isEnabled = enabled
        binding.btnNext.setBackgroundResource(
            if (enabled) R.drawable.bg_btn_primary else R.drawable.bg_btn_disabled
        )
        binding.btnNext.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.card_white else R.color.btn_disabled_text
            )
        )
    }
}
