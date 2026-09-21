package com.bingo.smartna.collector.device

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.databinding.FragmentDeviceBinding
import com.blankj.utilcode.util.ClickUtils

class DeviceFragment : BaseFragment<FragmentDeviceBinding, BaseViewModel>() {

    private val connectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { renderState() }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentDeviceBinding.inflate(inflater, container, false)

    override fun initData() {
        val lead = Prefs(requireContext()).role == UserRole.LEAD
        binding.tvTitle.setText(if (lead) R.string.tab_team_device else R.string.tab_device)
        binding.emptyView.bind(getString(R.string.device_empty))
        val toast = {
            Toast.makeText(requireContext(), R.string.demo_dev_toast, Toast.LENGTH_SHORT).show()
        }
        ClickUtils.applySingleDebouncing(binding.btnApply) { toast() }
        ClickUtils.applySingleDebouncing(binding.btnMore) { toast() }
        ClickUtils.applySingleDebouncing(binding.btnConnect) {
            connectLauncher.launch(Intent(requireContext(), ConnectKitActivity::class.java))
        }
        ClickUtils.applySingleDebouncing(binding.btnEgoDemo) {
            startActivity(Intent(requireContext(), EgoUsbDemoActivity::class.java))
        }
        ClickUtils.applySingleDebouncing(binding.btnDisconnect) {
            Prefs(requireContext()).clearConnectedDevice()
            renderState()
        }
        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) renderState()
    }

    private fun renderState() {
        val prefs = Prefs(requireContext())
        val kit = DeviceKit.fromId(prefs.connectedKitId)
        if (kit == null) {
            binding.emptyPanel.visibility = View.VISIBLE
            binding.connectedPanel.visibility = View.GONE
        } else {
            binding.emptyPanel.visibility = View.GONE
            binding.connectedPanel.visibility = View.VISIBLE
            binding.tvConnectedName.setText(kit.titleRes)
        }
    }
}
