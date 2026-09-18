package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.databinding.FragmentHallBinding

class HallFragment : BaseFragment<FragmentHallBinding, CollectorViewModel>() {

    private val adapter = TaskAdapter { viewModel.claim(it) }
    private var durationFilter = 0
    private var sortMode = 0
    private var latestState: CollectorUiState? = null

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHallBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        binding.etSearch.doAfterTextChanged { renderList() }
        binding.filterDuration.setOnClickListener {
            showFilter(
                binding.tvDuration,
                listOf(
                    getString(R.string.hall_duration_all),
                    getString(R.string.hall_duration_le30),
                    getString(R.string.hall_duration_gt30)
                ),
                durationFilter
            ) { durationFilter = it; updateFilterLabels(); renderList() }
        }
        binding.filterSort.setOnClickListener {
            showFilter(
                binding.tvSort,
                listOf(
                    getString(R.string.hall_sort_recommend),
                    getString(R.string.hall_sort_quota),
                    getString(R.string.hall_sort_reward)
                ),
                sortMode
            ) { sortMode = it; updateFilterLabels(); renderList() }
        }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            latestState = state
            renderList()
        }
    }

    private fun renderList() {
        val state = latestState ?: return
        val query = binding.etSearch.text?.toString().orEmpty()
        val visible = viewModel.hallTasks
            .filter { matchesSearch(it, query) }
            .filter { matchesDuration(it, durationFilter) }
            .let { list ->
                when (sortMode) {
                    1 -> list.sortedByDescending { state.quotaLeft[it.id] ?: 0 }
                    2 -> list.sortedByDescending { it.reward }
                    else -> list
                }
            }
            .map { task ->
                HallTaskItem(
                    task = task,
                    claimed = state.claimedIds.contains(task.id),
                    quotaLeft = state.quotaLeft[task.id] ?: 0
                )
            }
        adapter.submitList(visible)
    }

    private fun updateFilterLabels() {
        val durationDefault = durationFilter == 0
        binding.tvDuration.text = if (durationDefault) {
            getString(R.string.hall_filter_duration)
        } else {
            listOf(
                getString(R.string.hall_duration_all),
                getString(R.string.hall_duration_le30),
                getString(R.string.hall_duration_gt30)
            )[durationFilter]
        }
        val durationColor = ContextCompat.getColor(
            requireContext(),
            if (durationDefault) R.color.text_gray else R.color.blue_primary
        )
        binding.tvDuration.setTextColor(durationColor)
        binding.ivDuration.setColorFilter(durationColor)

        val sortDefault = sortMode == 0
        binding.tvSort.text = if (sortDefault) {
            getString(R.string.hall_filter_sort)
        } else {
            listOf(
                getString(R.string.hall_sort_recommend),
                getString(R.string.hall_sort_quota),
                getString(R.string.hall_sort_reward)
            )[sortMode]
        }
        val sortColor = ContextCompat.getColor(
            requireContext(),
            if (sortDefault) R.color.text_gray else R.color.blue_primary
        )
        binding.tvSort.setTextColor(sortColor)
        binding.ivSort.setColorFilter(sortColor)
    }

    private fun showFilter(
        anchor: android.view.View,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit
    ) {
        PopupMenu(requireContext(), anchor).apply {
            options.forEachIndexed { index, title -> menu.add(0, index, index, title) }
            menu.getItem(selected)?.isChecked = true
            setOnMenuItemClickListener {
                onSelect(it.itemId)
                true
            }
            show()
        }
    }

    private fun matchesSearch(task: Task, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return task.title.contains(q, ignoreCase = true) || task.scene.contains(q, ignoreCase = true)
    }

    private fun matchesDuration(task: Task, mode: Int): Boolean {
        if (mode == 0) return true
        val numbers = Regex("\\d+").findAll(task.duration).map { it.value.toInt() }.toList()
        if (numbers.isEmpty()) return true
        val maxMinutes = numbers.last()
        return if (mode == 1) maxMinutes <= 30 else maxMinutes > 30
    }
}
