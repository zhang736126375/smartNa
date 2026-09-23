package com.bingo.smartna.collector.hall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskKind
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.databinding.FragmentHallTaskListBinding
import com.blankj.utilcode.util.ClickUtils

enum class HallKindFilter { ALL, CUSTOM, FREE }

class HallTaskListFragment : BaseFragment<FragmentHallTaskListBinding, CollectorViewModel>() {

    private val adapter = CrowdTaskAdapter(
        onItemClick = { TaskDetailActivity.start(requireContext(), it) },
        onClaim = { task -> TaskDetailActivity.start(requireContext(), task) },
        onCapture = { task -> HallTaskActions.openCapture(requireContext(), task) },
        showStaffTags = true
    )
    private lateinit var category: HallCategory
    private lateinit var groupAdapter: SceneGroupAdapter
    private lateinit var childAdapter: SceneChildAdapter
    private lateinit var durationAdapter: DurationFilterAdapter

    private var latestState: CollectorUiState? = null
    private var kindFilter = HallKindFilter.ALL
    private var openPanel = CrowdFilterPanel.NONE

    private var appliedGroup: CrowdSceneGroup? = null
    private var appliedChild: String? = null
    private var appliedDuration: DurationBucket? = null
    private var appliedSort = CrowdHallSort.RECOMMEND

    private lateinit var draftGroup: CrowdSceneGroup
    private var draftChild: String? = null
    private var draftDuration: DurationBucket? = null
    private var sceneDraftTouched = false

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHallTaskListBinding.inflate(inflater, container, false)

    override fun initParam() {
        category = HallCategory.fromId(requireArguments().getString(ARG_CATEGORY).orEmpty())
    }

    override fun initViewModel(): CollectorViewModel {
        return CollectorViewModels.get(requireActivity().application)
    }

    override fun initData() {
        binding.tvTitle.setText(category.titleResFor(viewModel.ui.value?.role ?: UserRole.STAFF))
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        binding.etSearch.doAfterTextChanged { renderList() }

        ClickUtils.applySingleDebouncing(binding.btnBack) {
            (parentFragment as? HallCategoryNavigator)?.closeCategory()
        }
        binding.chipAll.setOnClickListener { kindFilter = HallKindFilter.ALL; renderList() }
        binding.chipCustom.setOnClickListener { kindFilter = HallKindFilter.CUSTOM; renderList() }
        binding.chipFree.setOnClickListener { kindFilter = HallKindFilter.FREE; renderList() }

        draftGroup = defaultSceneGroupForCategory()
        setupFilters()
        refreshFilterTabs()
        refreshSortRows()
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            latestState = state
            renderList()
        }
    }

    private fun categoryTasks(): List<Task> = viewModel.hallTasks.filter { category.matches(it) }

    private fun setupFilters() {
        groupAdapter = SceneGroupAdapter { group ->
            sceneDraftTouched = true
            draftChild = null
            if (group == null) {
                draftGroup = defaultSceneGroupForCategory()
                groupAdapter.selectedId = SceneGroupAdapter.ALL_ID
                childAdapter.submit(CrowdScenes.allChildren(), null)
            } else {
                draftGroup = group
                groupAdapter.selectedId = group.id
                childAdapter.submit(group.children, null)
            }
        }
        childAdapter = SceneChildAdapter { child ->
            sceneDraftTouched = true
            draftChild = child
            childAdapter.selected = child
        }
        durationAdapter = DurationFilterAdapter { bucket ->
            draftDuration = bucket
        }
        binding.rvSceneGroups.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSceneGroups.adapter = groupAdapter
        binding.rvSceneChildren.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSceneChildren.adapter = childAdapter
        binding.rvDuration.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDuration.adapter = durationAdapter
        groupAdapter.selectedId = SceneGroupAdapter.ALL_ID
        childAdapter.submit(CrowdScenes.allChildren(), draftChild)

        binding.filterScene.setOnClickListener { togglePanel(CrowdFilterPanel.SCENE) }
        binding.filterDuration.setOnClickListener { togglePanel(CrowdFilterPanel.DURATION) }
        binding.filterSort.setOnClickListener { togglePanel(CrowdFilterPanel.SORT) }
        binding.filterDim.setOnClickListener { closePanel() }
        ClickUtils.applySingleDebouncing(binding.btnReset) { resetCurrentPanel() }
        ClickUtils.applySingleDebouncing(binding.btnApply) { applyCurrentPanel() }
        binding.sortRecommend.setOnClickListener { applySort(CrowdHallSort.RECOMMEND) }
        binding.sortQuota.setOnClickListener { applySort(CrowdHallSort.QUOTA) }
        binding.sortPrice.setOnClickListener { applySort(CrowdHallSort.PRICE) }
    }

    private fun togglePanel(panel: CrowdFilterPanel) {
        if (openPanel == panel) {
            closePanel()
            return
        }
        openPanel = panel
        if (panel == CrowdFilterPanel.SCENE) {
            sceneDraftTouched = false
            draftGroup = appliedGroup ?: defaultSceneGroupForCategory()
            draftChild = appliedChild
            groupAdapter.selectedId = appliedGroup?.id ?: SceneGroupAdapter.ALL_ID
            val children = if (appliedGroup == null) CrowdScenes.allChildren() else draftGroup.children
            childAdapter.submit(children, draftChild)
        }
        if (panel == CrowdFilterPanel.DURATION) {
            draftDuration = appliedDuration
            durationAdapter.selected = appliedDuration
        }
        binding.filterOverlay.visibility = View.VISIBLE
        binding.scenePanel.visibility = if (panel == CrowdFilterPanel.SCENE) View.VISIBLE else View.GONE
        binding.rvDuration.visibility = if (panel == CrowdFilterPanel.DURATION) View.VISIBLE else View.GONE
        binding.sortPanel.visibility = if (panel == CrowdFilterPanel.SORT) View.VISIBLE else View.GONE
        binding.filterActions.visibility =
            if (panel == CrowdFilterPanel.SORT) View.GONE else View.VISIBLE
        refreshFilterTabs()
        refreshSortRows()
    }

    private fun closePanel() {
        openPanel = CrowdFilterPanel.NONE
        binding.filterOverlay.visibility = View.GONE
        refreshFilterTabs()
    }

    private fun resetCurrentPanel() {
        when (openPanel) {
            CrowdFilterPanel.SCENE -> {
                appliedGroup = null
                appliedChild = null
                sceneDraftTouched = false
                draftGroup = defaultSceneGroupForCategory()
                draftChild = null
                groupAdapter.selectedId = SceneGroupAdapter.ALL_ID
                childAdapter.submit(CrowdScenes.allChildren(), null)
            }
            CrowdFilterPanel.DURATION -> {
                appliedDuration = null
                draftDuration = null
                durationAdapter.selected = null
            }
            else -> Unit
        }
        refreshFilterTabs()
        renderList()
    }

    private fun applyCurrentPanel() {
        when (openPanel) {
            CrowdFilterPanel.SCENE -> {
                if (sceneDraftTouched) {
                    appliedGroup = if (groupAdapter.selectedId == SceneGroupAdapter.ALL_ID) {
                        null
                    } else {
                        draftGroup
                    }
                    appliedChild = draftChild
                }
            }
            CrowdFilterPanel.DURATION -> appliedDuration = draftDuration
            else -> Unit
        }
        closePanel()
        renderList()
    }

    private fun applySort(sort: CrowdHallSort) {
        appliedSort = sort
        closePanel()
        renderList()
    }

    private fun filteredTasks(state: CollectorUiState): List<Task> {
        val query = binding.etSearch.text?.toString().orEmpty()
        return categoryTasks()
            .filter { matchesKind(it) }
            .filter { matchesSearch(it, query) }
            .filter { matchesScene(it) }
            .filter { matchesDuration(it) }
            .let { list ->
                when (appliedSort) {
                    CrowdHallSort.RECOMMEND -> list
                    CrowdHallSort.QUOTA -> list.sortedByDescending {
                        state.quotaLeft[it.id] ?: it.quotaTotal
                    }
                    CrowdHallSort.PRICE -> list.sortedByDescending { it.reward }
                }
            }
    }

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

        val visible = filteredTasks(state).map { task ->
            val userTask = state.userTaskFor(task.id)
            val displayTask = userTask?.task ?: task
            HallTaskItem(
                task = displayTask,
                claimed = state.claimedIds.contains(task.id),
                quotaLeft = state.quotaLeft[task.id] ?: task.quotaTotal,
                doneClips = userTask?.doneClips ?: 0,
                uploadedClips = userTask?.uploadedClips ?: 0,
                canCaptureMore = userTask?.canCaptureMore() ?: true
            )
        }
        adapter.submitList(visible)
        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        refreshFilterTabs()
    }

    private fun matchesKind(task: Task): Boolean = when (kindFilter) {
        HallKindFilter.ALL -> true
        HallKindFilter.CUSTOM -> task.kind == TaskKind.CUSTOM
        HallKindFilter.FREE -> task.kind == TaskKind.FREE
    }

    private fun matchesScene(task: Task): Boolean {
        appliedChild?.let { child ->
            if (task.sceneChildName != child) return false
        }
        val group = appliedGroup ?: return true
        return task.sceneGroupName == group.name
    }

    private fun matchesDuration(task: Task): Boolean {
        val bucket = appliedDuration ?: return true
        return task.durationMax in bucket.min..bucket.max
    }

    private fun defaultSceneGroupForCategory(): CrowdSceneGroup {
        val groupName = when (category) {
            HallCategory.PRODUCE -> "生产制造场景"
            HallCategory.CATERING -> "餐饮场景"
            HallCategory.WAREHOUSE -> "物流仓储场景"
            HallCategory.ENTERTAIN -> "户外与公共场景"
            HallCategory.AGRI -> "农业生产场景"
            else -> "居住场景"
        }
        return CrowdScenes.groupOfName(groupName) ?: CrowdScenes.groups.first()
    }

    private fun matchesSearch(task: Task, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return task.title.contains(q, ignoreCase = true) ||
            task.scene.contains(q, ignoreCase = true) ||
            task.device.contains(q, ignoreCase = true)
    }

    private fun bindChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
        chip.setTextColor(
            ContextCompat.getColor(requireContext(), if (selected) R.color.card_white else R.color.text_dark)
        )
        chip.paint.isFakeBoldText = selected
    }

    private fun refreshFilterTabs() {
        bindTab(
            binding.tvScene,
            binding.ivScene,
            openPanel == CrowdFilterPanel.SCENE,
            appliedGroup != null
        )
        bindTab(
            binding.tvDurationFilter,
            binding.ivDuration,
            openPanel == CrowdFilterPanel.DURATION,
            appliedDuration != null
        )
        val sortActive = openPanel == CrowdFilterPanel.SORT || appliedSort != CrowdHallSort.RECOMMEND
        bindTab(binding.tvSort, binding.ivSort, openPanel == CrowdFilterPanel.SORT, sortActive)
        binding.tvSort.setText(
            when (appliedSort) {
                CrowdHallSort.RECOMMEND -> R.string.hall_sort_recommend
                CrowdHallSort.QUOTA -> R.string.hall_sort_quota
                CrowdHallSort.PRICE -> R.string.hall_sort_price
            }
        )
    }

    private fun bindTab(label: TextView, arrow: ImageView, opened: Boolean, highlighted: Boolean) {
        val color = ContextCompat.getColor(
            requireContext(),
            if (opened || highlighted) R.color.brand_orange else R.color.text_gray
        )
        label.setTextColor(color)
        arrow.setColorFilter(color)
        arrow.rotation = if (opened) 180f else 0f
    }

    private fun refreshSortRows() {
        bindSortRow(
            binding.sortRecommend,
            binding.tvSortRecommend,
            binding.ivSortRecommend,
            appliedSort == CrowdHallSort.RECOMMEND
        )
        bindSortRow(
            binding.sortQuota,
            binding.tvSortQuota,
            binding.ivSortQuota,
            appliedSort == CrowdHallSort.QUOTA
        )
        bindSortRow(
            binding.sortPrice,
            binding.tvSortPrice,
            binding.ivSortPrice,
            appliedSort == CrowdHallSort.PRICE
        )
    }

    private fun bindSortRow(row: View, label: TextView, check: ImageView, selected: Boolean) {
        label.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        label.paint.isFakeBoldText = selected
        row.setBackgroundResource(
            if (selected) R.drawable.bg_filter_child_selected else android.R.color.transparent
        )
        check.visibility = if (selected) View.VISIBLE else View.GONE
    }

    companion object {
        private const val ARG_CATEGORY = "category_id"

        fun newInstance(categoryId: String) = HallTaskListFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORY, categoryId) }
        }
    }
}
