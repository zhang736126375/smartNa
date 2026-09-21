package com.bingo.smartna.collector.device

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.databinding.ActivityConnectKitBinding
import com.blankj.utilcode.util.ClickUtils

class ConnectKitActivity : BaseActivity<ActivityConnectKitBinding, DeviceConnectViewModel>() {

    private val adapter = DeviceKitAdapter { kit ->
        viewModel.selectedKit = kit
        refreshNext()
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openScan()
        } else {
            Toast.makeText(this, R.string.device_camera_denied, Toast.LENGTH_SHORT).show()
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

    private fun onNext() {
        val kit = viewModel.selectedKit ?: return
        if (kit.hasEgo) {
            DevicePowerDialog().apply {
                onPowered = { requestCameraThenScan() }
            }.show(supportFragmentManager, DevicePowerDialog.TAG)
        } else {
            requestCameraThenScan()
        }
    }

    private fun requestCameraThenScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            openScan()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openScan() {
        val kit = viewModel.selectedKit ?: return
        scanLauncher.launch(
            Intent(this, ScanDeviceActivity::class.java)
                .putExtra(ScanDeviceActivity.EXTRA_KIT_ID, kit.id)
        )
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
