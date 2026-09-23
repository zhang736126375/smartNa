package com.bingo.smartna.collector.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.HallNavigator
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.collector.hall.HallTaskActions
import com.bingo.smartna.databinding.FragmentMyTasksBinding

class MyTasksFragment : BaseFragment<FragmentMyTasksBinding, CollectorViewModel>() {

    private var selected = TaskStatus.IN_PROGRESS
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
            Toast.makeText(requireContext(), R.string.tasks_approve_done, Toast.LENGTH_SHORT).show()
        },
        onReject = { userTask ->
            viewModel.reject(userTask)
            Toast.makeText(requireContext(), R.string.tasks_reject_done, Toast.LENGTH_SHORT).show()
        }
    )

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMyTasksBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return CollectorViewModels.get(requireActivity().application)
    }

    override fun initData() {
        binding.rvTasks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        binding.tabInProgress.setOnClickListener { selected = TaskStatus.IN_PROGRESS; refreshTabs() }
        binding.tabReviewing.setOnClickListener { selected = TaskStatus.REVIEWING; refreshTabs() }
        binding.tabDone.setOnClickListener { selected = TaskStatus.DONE; refreshTabs() }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { refreshTabs() }
    }

    private fun refreshTabs() {
        val state = viewModel.ui.value ?: return
        bindTab(
            binding.tvInProgress,
            binding.indicatorInProgress,
            TaskStatus.IN_PROGRESS.label,
            state.inProgressCount(),
            selected == TaskStatus.IN_PROGRESS
        )
        bindTab(
            binding.tvReviewing,
            binding.indicatorReviewing,
            TaskStatus.REVIEWING.label,
            state.reviewingCount(),
            selected == TaskStatus.REVIEWING
        )
        bindTab(
            binding.tvDone,
            binding.indicatorDone,
            TaskStatus.DONE.label,
            state.doneCount(),
            selected == TaskStatus.DONE
        )

        val list = state.userTasks.filter { it.status == selected }
        if (list.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.rvTasks.visibility = View.GONE
            binding.emptyView.bind(
                getString(R.string.tasks_empty),
                getString(R.string.tasks_go_claim)
            ) { (activity as? HallNavigator)?.openHall() }
        } else {
            binding.emptyView.visibility = View.GONE
            binding.rvTasks.visibility = View.VISIBLE
            adapter.submitList(list)
        }
    }

    private fun bindTab(
        titleView: android.widget.TextView,
        indicator: View,
        label: String,
        count: Int,
        active: Boolean
    ) {
        titleView.text = if (count > 0) getString(R.string.tasks_tab_count, label, count) else label
        titleView.setTextColor(
            if (active) {
                com.google.android.material.color.MaterialColors.getColor(
                    titleView,
                    com.google.android.material.R.attr.colorPrimary
                )
            } else {
                ContextCompat.getColor(requireContext(), R.color.text_dark)
            }
        )
        titleView.paint.isFakeBoldText = active
        indicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }
}
