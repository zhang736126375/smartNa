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
import com.bingo.smartna.collector.device.ego.EgoNetDeviceFoundActivity
import com.bingo.smartna.databinding.FragmentDeviceBinding
import com.blankj.utilcode.util.ClickUtils

class DeviceFragment : BaseFragment<FragmentDeviceBinding, BaseViewModel>() {

    private var moreClickCount = 0
    private var moreClickWindowStart = 0L

    private val connectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { renderState() }

    private val applyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { renderState() }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentDeviceBinding.inflate(inflater, container, false)

    override fun initData() {
        val lead = Prefs(requireContext()).role == UserRole.LEAD
        binding.tvTitle.setText(if (lead) R.string.tab_team_device else R.string.mine_menu_device)
        binding.emptyView.bind(getString(R.string.device_empty))
        ClickUtils.applySingleDebouncing(binding.btnApply) {
            applyLauncher.launch(Intent(requireContext(), DeviceApplyEntryActivity::class.java))
        }
        binding.btnMore.setOnClickListener { onMoreClicked() }
        ClickUtils.applySingleDebouncing(binding.btnConnect) {
            connectLauncher.launch(Intent(requireContext(), EgoNetDeviceFoundActivity::class.java))
        }
        ClickUtils.applySingleDebouncing(binding.btnViewApply) { openApplyProgress() }
        ClickUtils.applySingleDebouncing(binding.btnViewApplyConnected) { openApplyProgress() }
        ClickUtils.applySingleDebouncing(binding.btnConnectFromApplied) {
            connectLauncher.launch(Intent(requireContext(), EgoNetDeviceFoundActivity::class.java))
        }
        ClickUtils.applySingleDebouncing(binding.btnDisconnect) {
            Prefs(requireContext()).clearConnectedDevice()
            Toast.makeText(requireContext(), R.string.device_unbind_done, Toast.LENGTH_SHORT).show()
            renderState()
        }
        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) renderState()
    }

    private fun onMoreClicked() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - moreClickWindowStart > 1000L) {
            moreClickCount = 0
            moreClickWindowStart = now
        }
        moreClickCount += 1
        if (moreClickCount >= 3) {
            moreClickCount = 0
            startActivity(Intent(requireContext(), EgoNetDeviceFoundActivity::class.java))
        }
    }

    private fun openApplyProgress() {
        applyLauncher.launch(Intent(requireContext(), DeviceApplyEntryActivity::class.java))
    }

    private fun renderState() {
        val prefs = Prefs(requireContext())
        val kit = DeviceKit.fromId(prefs.connectedKitId) ?: DeviceKit.EGO
        val showApplyProgress = prefs.isApplyCompleted
        when {
            prefs.hasConnectedDevice -> {
                binding.emptyPanel.visibility = View.GONE
                binding.appliedPanel.visibility = View.GONE
                binding.connectedPanel.visibility = View.VISIBLE
                binding.tvConnectedName.setText(kit.titleRes)
                binding.connectedApplyTags.visibility = if (showApplyProgress) View.VISIBLE else View.GONE
                binding.btnViewApplyConnected.visibility = if (showApplyProgress) View.VISIBLE else View.GONE
            }
            prefs.isApplyCompleted -> {
                binding.emptyPanel.visibility = View.GONE
                binding.appliedPanel.visibility = View.VISIBLE
                binding.connectedPanel.visibility = View.GONE
                val appliedKit = DeviceKit.fromId(prefs.applyKitId) ?: DeviceKit.EGO
                binding.tvAppliedKitName.text = getString(appliedKit.titleRes)
            }
            else -> {
                binding.emptyPanel.visibility = View.VISIBLE
                binding.appliedPanel.visibility = View.GONE
                binding.connectedPanel.visibility = View.GONE
            }
        }
    }
}
