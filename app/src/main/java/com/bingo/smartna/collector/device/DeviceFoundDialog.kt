package com.bingo.smartna.collector.device

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bingo.smartna.R
import com.bingo.smartna.databinding.DialogDeviceFoundBinding
import com.blankj.utilcode.util.ClickUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DeviceFoundDialog : BottomSheetDialogFragment() {

    var kit: DeviceKit = DeviceKit.EGO
    var onConnect: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = DialogDeviceFoundBinding.inflate(inflater, container, false)
        binding.tvKitName.text = getString(kit.titleRes)
        binding.tvSerial.text = getString(R.string.device_found_serial, DEMO_SERIAL)
        binding.ivKit.setImageResource(
            if (kit == DeviceKit.GRIPPER) R.drawable.ic_kit_gripper else R.drawable.ic_kit_ego
        )
        ClickUtils.applySingleDebouncing(binding.btnClose) { dismiss() }
        ClickUtils.applySingleDebouncing(binding.btnConnect) {
            dismiss()
            onConnect?.invoke()
        }
        return binding.root
    }

    companion object {
        const val TAG = "DeviceFoundDialog"
        const val DEMO_SERIAL = "EGO-DEMO-001"
    }
}
