package com.bingo.smartna.collector.hall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskKind
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.databinding.FragmentHallTaskListBinding
import com.blankj.utilcode.util.ClickUtils

enum class HallKindFilter { ALL, CUSTOM, FREE }

class HallTaskListFragment : BaseFragment<FragmentHallTaskListBinding, CollectorViewModel>() {

    private val adapter = TaskAdapter(
        onClaim = { viewModel.claim(it) },
        onCapture = {
            Toast.makeText(requireContext(), R.string.hall_capture_toast, Toast.LENGTH_SHORT).show()
        }
    )
    private lateinit var category: HallCategory
    private var sceneFilter: String? = null
    private var deviceFilter: String? = null
    private var kindFilter = HallKindFilter.ALL
    private var latestState: CollectorUiState? = null

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHallTaskListBinding.inflate(inflater, container, false)

    override fun initParam() {
        category = HallCategory.fromId(requireArguments().getString(ARG_CATEGORY).orEmpty())
    }

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        binding.tvTitle.setText(category.titleResFor(viewModel.ui.value?.role ?: UserRole.STAFF))
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        ClickUtils.applySingleDebouncing(binding.btnBack) {
            (parentFragment as? HallCategoryNavigator)?.closeCategory()
        }
        ClickUtils.applySingleDebouncing(binding.btnFilter) { showFilterMenu() }
        binding.chipAll.setOnClickListener { kindFilter = HallKindFilter.ALL; renderList() }
        binding.chipCustom.setOnClickListener { kindFilter = HallKindFilter.CUSTOM; renderList() }
        binding.chipFree.setOnClickListener { kindFilter = HallKindFilter.FREE; renderList() }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            latestState = state
            renderList()
        }
    }

    private fun categoryTasks(): List<Task> = viewModel.hallTasks.filter { category.matches(it) }

    private fun renderList() {
        val state = latestState ?: return
        val all = categoryTasks()
        binding.chipAll.text = getString(R.string.hall_chip_all, all.size)
        binding.chipCustom.text = getString(
            R.string.hall_chip_custom,
            all.count { it.kind == TaskKind.CUSTOM }
        )
        binding.chipFree.text = getString(
            R.string.hall_chip_free,
            all.count { it.kind == TaskKind.FREE }
        )
        bindChip(binding.chipAll, kindFilter == HallKindFilter.ALL)
        bindChip(binding.chipCustom, kindFilter == HallKindFilter.CUSTOM)
        bindChip(binding.chipFree, kindFilter == HallKindFilter.FREE)

        val visible = all
            .filter {
                when (kindFilter) {
                    HallKindFilter.ALL -> true
                    HallKindFilter.CUSTOM -> it.kind == TaskKind.CUSTOM
                    HallKindFilter.FREE -> it.kind == TaskKind.FREE
                }
            }
            .filter { sceneFilter == null || it.scene == sceneFilter }
            .filter { deviceFilter == null || it.device == deviceFilter }
            .sortedByDescending { it.reward }
            .map { task ->
                HallTaskItem(
                    task = task,
                    claimed = state.claimedIds.contains(task.id),
                    quotaLeft = state.quotaLeft[task.id] ?: 0
                )
            }
        adapter.submitList(visible)
    }

    private fun bindChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
        chip.setTextColor(
            ContextCompat.getColor(requireContext(), if (selected) R.color.card_white else R.color.text_dark)
        )
        chip.paint.isFakeBoldText = selected
    }

    private fun showFilterMenu() {
        val scenes = listOf(getString(R.string.hall_filter_all_scene)) +
            categoryTasks().map { it.scene }.distinct()
        val devices = listOf(getString(R.string.hall_filter_all_device)) +
            categoryTasks().map { it.device }.distinct()
        PopupMenu(requireContext(), binding.btnFilter).apply {
            scenes.forEachIndexed { index, title ->
                menu.add(GROUP_SCENE, index, index, getString(R.string.hall_filter_scene_item, title))
            }
            devices.forEachIndexed { index, title ->
                menu.add(
                    GROUP_DEVICE,
                    index,
                    scenes.size + index,
                    getString(R.string.hall_filter_device_item, title)
                )
            }
            setOnMenuItemClickListener { item ->
                when (item.groupId) {
                    GROUP_SCENE -> sceneFilter = if (item.itemId == 0) null else scenes[item.itemId]
                    GROUP_DEVICE -> deviceFilter = if (item.itemId == 0) null else devices[item.itemId]
                }
                renderList()
                true
            }
            show()
        }
    }

    companion object {
        private const val ARG_CATEGORY = "category_id"
        private const val GROUP_SCENE = 1
        private const val GROUP_DEVICE = 2

        fun newInstance(categoryId: String) = HallTaskListFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORY, categoryId) }
        }
    }
}
