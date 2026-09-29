package com.bingo.smartna.collector.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bingo.smartna.MainActivity
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.HallNavigator
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.collector.done.JobDoneActivity
import com.bingo.smartna.collector.hall.HallTaskActions
import com.bingo.smartna.collector.wallet.WalletEntryAdapter
import com.bingo.smartna.databinding.FragmentMyTasksBinding
import com.blankj.utilcode.util.ClickUtils

class MyTasksFragment : BaseFragment<FragmentMyTasksBinding, CollectorViewModel>() {

    private enum class TaskFilter { ALL, IN_PROGRESS, REVIEWING, DONE }

    private var selected = TaskFilter.ALL
    private val adapter = UserTaskAdapter(
        onCapture = { HallTaskActions.openCapture(requireContext(), it) },
        onSubmitReview = { userTask ->
            if (viewModel.canSubmitReview(userTask)) {
                viewModel.submitForReview(userTask)
                Toast.makeText(requireContext(), R.string.tasks_submit_done, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.tasks_submit_need_upload, Toast.LENGTH_SHORT).show()
            }
        },
        onApprove = { userTask ->
            viewModel.approve(userTask)
            JobDoneActivity.start(
                requireContext(),
                userTask.task.title,
                userTask.task.reward,
                userTask.doneClips.coerceAtLeast(1)
            )
        },
        onReject = { userTask ->
            viewModel.reject(userTask)
            Toast.makeText(requireContext(), R.string.tasks_reject_done, Toast.LENGTH_SHORT).show()
        }
    )
    private val recentAdapter = WalletEntryAdapter(showPaidHint = true)

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMyTasksBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel =
        CollectorViewModels.get(requireActivity().application)

    override fun initData() {
        binding.rvTasks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        binding.rvRecent.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.rvRecent.adapter = recentAdapter
        binding.tabAll.setOnClickListener { selected = TaskFilter.ALL; refreshTabs() }
        binding.tabInProgress.setOnClickListener { selected = TaskFilter.IN_PROGRESS; refreshTabs() }
        binding.tabReviewing.setOnClickListener { selected = TaskFilter.REVIEWING; refreshTabs() }
        binding.tabDone.setOnClickListener { selected = TaskFilter.DONE; refreshTabs() }
        ClickUtils.applySingleDebouncing(binding.btnGoHall) {
            (activity as? HallNavigator)?.openHall()
        }
        ClickUtils.applySingleDebouncing(binding.btnRecentAll) {
            startActivity(MainActivity.intentForTab(requireContext(), R.id.nav_wallet))
        }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { refreshTabs() }
    }

    private fun refreshTabs() {
        val state = viewModel.ui.value ?: return
        bindPill(binding.tabAll, getString(R.string.tasks_tab_all), state.userTasks.size, selected == TaskFilter.ALL)
        bindPill(
            binding.tabInProgress,
            TaskStatus.IN_PROGRESS.label,
            state.inProgressCount(),
            selected == TaskFilter.IN_PROGRESS
        )
        bindPill(
            binding.tabReviewing,
            getString(R.string.tasks_tab_checking),
            state.reviewingCount(),
            selected == TaskFilter.REVIEWING
        )
        bindPill(
            binding.tabDone,
            getString(R.string.tasks_tab_done_short),
            state.doneCount(),
            selected == TaskFilter.DONE
        )

        val list = when (selected) {
            TaskFilter.ALL -> state.userTasks
            TaskFilter.IN_PROGRESS -> state.userTasks.filter { it.status == TaskStatus.IN_PROGRESS }
            TaskFilter.REVIEWING -> state.userTasks.filter { it.status == TaskStatus.REVIEWING }
            TaskFilter.DONE -> state.userTasks.filter { it.status == TaskStatus.DONE }
        }
        if (list.isEmpty()) {
            binding.emptyBlock.visibility = View.VISIBLE
            binding.rvTasks.visibility = View.GONE
        } else {
            binding.emptyBlock.visibility = View.GONE
            binding.rvTasks.visibility = View.VISIBLE
            adapter.submitList(list)
        }

        if (state.walletEntries.isEmpty()) {
            binding.recentBlock.visibility = View.GONE
        } else {
            binding.recentBlock.visibility = View.VISIBLE
            recentAdapter.submitList(state.walletEntries.take(4))
        }
    }

    private fun bindPill(view: TextView, label: String, count: Int, active: Boolean) {
        view.text = getString(R.string.tasks_pill_count, label, count)
        view.setBackgroundResource(if (active) R.drawable.bg_pill_brand else R.drawable.bg_pill_ghost)
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (active) R.color.brand_primary_active else R.color.text_secondary
            )
        )
    }
}
