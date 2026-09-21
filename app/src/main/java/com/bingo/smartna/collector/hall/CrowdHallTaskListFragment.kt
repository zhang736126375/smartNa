package com.bingo.smartna.collector.hall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.capture.CaptureActivity
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.databinding.FragmentHallTaskListCrowdBinding
import com.blankj.utilcode.util.ClickUtils

class CrowdHallTaskListFragment : BaseFragment<FragmentHallTaskListCrowdBinding, CollectorViewModel>() {

    private val adapter = CrowdTaskAdapter(
        onClaim = { viewModel.claim(it) },
        onCapture = { CaptureActivity.start(requireContext(), it) }
    )
    private lateinit var category: HallCategory
    private var sceneFilter: String? = null
    private var deviceFilter: String? = null
    private var latestState: CollectorUiState? = null

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHallTaskListCrowdBinding.inflate(inflater, container, false)

    override fun initParam() {
        category = HallCategory.fromId(requireArguments().getString(ARG_CATEGORY).orEmpty())
    }

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        binding.tvTitle.setText(category.titleResFor(UserRole.CROWD))
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        binding.etSearch.doAfterTextChanged { renderList() }
        ClickUtils.applySingleDebouncing(binding.btnBack) {
            (parentFragment as? HallCategoryNavigator)?.closeCategory()
        }
        binding.filterScene.setOnClickListener { showSceneMenu() }
        binding.filterDevice.setOnClickListener { showDeviceMenu() }
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
        val query = binding.etSearch.text?.toString().orEmpty()
        val visible = categoryTasks()
            .filter { matchesSearch(it, query) }
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

    private fun showSceneMenu() {
        val options = listOf(getString(R.string.hall_filter_all_scene)) +
            categoryTasks().map { it.scene }.distinct()
        val selected = sceneFilter?.let { options.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        showFilter(binding.tvScene, options, selected) { index ->
            sceneFilter = if (index == 0) null else options[index]
            updateFilterLabel(
                binding.tvScene,
                binding.ivScene,
                sceneFilter,
                R.string.hall_filter_scene
            )
            renderList()
        }
    }

    private fun showDeviceMenu() {
        val options = listOf(getString(R.string.hall_filter_all_device)) +
            categoryTasks().map { it.device }.distinct()
        val selected = deviceFilter?.let { options.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        showFilter(binding.tvDevice, options, selected) { index ->
            deviceFilter = if (index == 0) null else options[index]
            updateFilterLabel(
                binding.tvDevice,
                binding.ivDevice,
                deviceFilter,
                R.string.hall_filter_device
            )
            renderList()
        }
    }

    private fun updateFilterLabel(
        label: TextView,
        arrow: ImageView,
        selected: String?,
        defaultRes: Int
    ) {
        val isDefault = selected == null
        label.text = selected ?: getString(defaultRes)
        val color = ContextCompat.getColor(
            requireContext(),
            if (isDefault) R.color.text_gray else R.color.blue_primary
        )
        label.setTextColor(color)
        arrow.setColorFilter(color)
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
        return task.title.contains(q, ignoreCase = true) ||
            task.scene.contains(q, ignoreCase = true) ||
            task.device.contains(q, ignoreCase = true)
    }

    companion object {
        private const val ARG_CATEGORY = "category_id"

        fun newInstance(categoryId: String) = CrowdHallTaskListFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORY, categoryId) }
        }
    }
}
