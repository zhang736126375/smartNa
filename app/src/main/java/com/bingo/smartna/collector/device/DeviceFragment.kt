package com.bingo.smartna.collector.device

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.databinding.FragmentDeviceBinding
import com.blankj.utilcode.util.ClickUtils

class DeviceFragment : BaseFragment<FragmentDeviceBinding, BaseViewModel>() {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentDeviceBinding.inflate(inflater, container, false)

    override fun initData() {
        binding.emptyView.bind(getString(R.string.device_empty))
        val toast = {
            Toast.makeText(requireContext(), R.string.demo_dev_toast, Toast.LENGTH_SHORT).show()
        }
        ClickUtils.applySingleDebouncing(binding.btnApply) { toast() }
        ClickUtils.applySingleDebouncing(binding.btnConnect) { toast() }
        ClickUtils.applySingleDebouncing(binding.btnMore) { toast() }
    }
}
