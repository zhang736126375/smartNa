package com.bingo.smartna.collector.lead

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.data.model.TeamTaskTab
import com.bingo.smartna.databinding.FragmentTeamTasksBinding

class TeamTasksFragment : BaseFragment<FragmentTeamTasksBinding, CollectorViewModel>() {

    private var selected = TeamTaskTab.ASSIGNED
    private val adapter = TeamTaskAdapter()

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentTeamTasksBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return com.bingo.smartna.collector.CollectorViewModels.get(requireActivity().application)
    }

    override fun initData() {
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        binding.tabAssigned.setOnClickListener { selected = TeamTaskTab.ASSIGNED; render() }
        binding.tabClaimed.setOnClickListener { selected = TeamTaskTab.CLAIMED; render() }
        binding.tabApplied.setOnClickListener { selected = TeamTaskTab.APPLIED; render() }
        render()
    }

    private fun render() {
        val list = viewModel.teamTasks.filter { it.tab == selected }
        bindTab(
            binding.tvAssigned,
            binding.indicatorAssigned,
            getString(R.string.lead_tab_assigned),
            viewModel.teamTasks.count { it.tab == TeamTaskTab.ASSIGNED },
            selected == TeamTaskTab.ASSIGNED
        )
        bindTab(
            binding.tvClaimed,
            binding.indicatorClaimed,
            getString(R.string.lead_tab_claimed),
            viewModel.teamTasks.count { it.tab == TeamTaskTab.CLAIMED },
            selected == TeamTaskTab.CLAIMED
        )
        bindTab(
            binding.tvApplied,
            binding.indicatorApplied,
            getString(R.string.lead_tab_applied),
            viewModel.teamTasks.count { it.tab == TeamTaskTab.APPLIED },
            selected == TeamTaskTab.APPLIED
        )
        if (list.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.rvTasks.visibility = View.GONE
            binding.emptyView.bind(getString(R.string.lead_empty))
        } else {
            binding.emptyView.visibility = View.GONE
            binding.rvTasks.visibility = View.VISIBLE
            adapter.submitList(list)
        }
    }

    private fun bindTab(
        titleView: TextView,
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
