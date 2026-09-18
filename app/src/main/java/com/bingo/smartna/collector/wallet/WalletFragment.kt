package com.bingo.smartna.collector.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.HallNavigator
import com.bingo.smartna.databinding.FragmentWalletBinding
import com.blankj.utilcode.util.ClickUtils

class WalletFragment : BaseFragment<FragmentWalletBinding, CollectorViewModel>() {

    private val adapter = WalletEntryAdapter()

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentWalletBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        binding.rvEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.rvEntries.adapter = adapter
        ClickUtils.applySingleDebouncing(binding.btnWithdraw) {
            Toast.makeText(requireContext(), R.string.demo_dev_toast, Toast.LENGTH_SHORT).show()
        }
        ClickUtils.applySingleDebouncing(binding.btnDetail) {
            if (adapter.itemCount > 0) {
                binding.rvEntries.smoothScrollToPosition(0)
            }
        }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            binding.tvBalance.text = getString(R.string.wallet_balance, state.walletBalance)
            if (state.walletEntries.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.rvEntries.visibility = View.GONE
                binding.emptyView.bind(
                    getString(R.string.wallet_empty),
                    getString(R.string.tasks_go_claim)
                ) { (activity as? HallNavigator)?.openHall() }
            } else {
                binding.emptyView.visibility = View.GONE
                binding.rvEntries.visibility = View.VISIBLE
                adapter.submitList(state.walletEntries)
            }
        }
    }
}
