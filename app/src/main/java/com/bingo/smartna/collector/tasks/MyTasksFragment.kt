package com.bingo.smartna.collector.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.HallNavigator
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.databinding.FragmentMyTasksBinding

class MyTasksFragment : BaseFragment<FragmentMyTasksBinding, CollectorViewModel>() {

    private var selected = TaskStatus.IN_PROGRESS
    private val adapter = UserTaskAdapter { task ->
        when (task.status) {
            TaskStatus.IN_PROGRESS -> viewModel.submitForReview(task)
            TaskStatus.REVIEWING -> viewModel.approve(task)
            TaskStatus.DONE -> Unit
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMyTasksBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
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
            ContextCompat.getColor(requireContext(), if (active) R.color.blue_primary else R.color.text_dark)
        )
        titleView.paint.isFakeBoldText = active
        indicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }
}
