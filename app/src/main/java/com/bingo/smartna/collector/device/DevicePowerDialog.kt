package com.bingo.smartna.collector.device

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bingo.smartna.databinding.DialogDevicePowerBinding
import com.blankj.utilcode.util.ClickUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DevicePowerDialog : BottomSheetDialogFragment() {

    var onPowered: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = DialogDevicePowerBinding.inflate(inflater, container, false)
        ClickUtils.applySingleDebouncing(binding.btnClose) { dismiss() }
        ClickUtils.applySingleDebouncing(binding.btnNext) {
            dismiss()
            onPowered?.invoke()
        }
        return binding.root
    }

    companion object {
        const val TAG = "DevicePowerDialog"
    }
}
