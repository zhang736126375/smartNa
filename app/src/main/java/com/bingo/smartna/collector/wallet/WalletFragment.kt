package com.bingo.smartna.collector.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.databinding.FragmentWalletBinding
import com.blankj.utilcode.util.ClickUtils
import java.util.Locale

class WalletFragment : BaseFragment<FragmentWalletBinding, CollectorViewModel>() {

    private val adapter = WalletEntryAdapter()

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentWalletBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel =
        CollectorViewModels.get(requireActivity().application)

    override fun initData() {
        binding.rvEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.rvEntries.adapter = adapter
        ClickUtils.applySingleDebouncing(binding.btnWithdraw) {
            Toast.makeText(requireContext(), R.string.wallet_withdraw_mock, Toast.LENGTH_SHORT).show()
        }
        ClickUtils.applySingleDebouncing(binding.btnDetail) { scrollToLedger() }
        ClickUtils.applySingleDebouncing(binding.btnAllLedger) { scrollToLedger() }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            binding.tvBalance.text = String.format(Locale.CHINA, "%.2f", state.walletBalance)
            val monthGain = state.walletEntries.take(5).sumOf { it.amount.coerceAtLeast(0.0) }
            val pending = state.userTasks
                .filter { it.status == TaskStatus.REVIEWING }
                .sumOf { it.task.reward }
            binding.tvMonthGain.text = getString(R.string.wallet_month_gain, monthGain)
            binding.tvPending.text = getString(R.string.wallet_pending, pending)
            val frames = state.userTasks.sumOf { it.doneClips } * 240
            binding.tvStatsFrames.text = String.format(Locale.CHINA, "%,d 帧", frames)
            binding.tvStatsJobs.text = getString(R.string.wallet_stat_jobs_value, state.doneCount())
            if (state.walletEntries.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.rvEntries.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.rvEntries.visibility = View.VISIBLE
                adapter.submitList(state.walletEntries)
            }
        }
    }

    private fun scrollToLedger() {
        if (adapter.itemCount > 0) {
            binding.rvEntries.smoothScrollToPosition(0)
        }
    }
}
