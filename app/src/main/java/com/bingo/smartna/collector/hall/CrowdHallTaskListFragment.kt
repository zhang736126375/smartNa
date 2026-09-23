package com.bingo.smartna.collector.hall

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskKind
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.databinding.FragmentHallTaskListCrowdBinding
import com.bingo.smartna.databinding.ItemHallBannerBinding
import com.blankj.utilcode.util.ClickUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CrowdHallTaskListFragment : BaseFragment<FragmentHallTaskListCrowdBinding, CollectorViewModel>() {

    private lateinit var adapter: CrowdTaskAdapter
    private var staffTagsEnabled = false
    private lateinit var groupAdapter: SceneGroupAdapter
    private lateinit var childAdapter: SceneChildAdapter
    private lateinit var durationAdapter: DurationFilterAdapter
    private lateinit var bannerItems: List<HallBannerItem>
    private val bannerHandler = Handler(Looper.getMainLooper())
    private var bannerRunnable: Runnable? = null

    private var latestState: CollectorUiState? = null
    private var openPanel = CrowdFilterPanel.NONE

    private var appliedGroup: CrowdSceneGroup? = null
    private var appliedChild: String? = null
    private var appliedDuration: DurationBucket? = null
    private var appliedSort = CrowdHallSort.RECOMMEND

    private var draftGroup: CrowdSceneGroup? = null
    private var draftChild: String? = null
    private var draftDuration: DurationBucket? = null

    private var displayLimit = PAGE_SIZE
    private var isLoadingMore = false
    private var category: HallCategory? = null
    private var kindFilter = HallKindFilter.ALL

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHallTaskListCrowdBinding.inflate(inflater, container, false)

    override fun initParam() {
        val categoryId = arguments?.getString(ARG_CATEGORY)
        category = categoryId?.let { HallCategory.fromId(it) }
    }

    override fun initViewModel(): CollectorViewModel {
        return CollectorViewModels.get(requireActivity().application)
    }

    override fun initData() {
        staffTagsEnabled = showStaffTags()
        adapter = createAdapter(staffTagsEnabled)
        setupCategoryHeader()
        bannerItems = listOf(
            HallBannerItem(
                getString(R.string.hall_banner_title),
                getString(R.string.hall_banner_sub),
                R.drawable.bg_banner_orange
            ),
            HallBannerItem(
                getString(R.string.hall_banner_surprise_title),
                getString(R.string.hall_banner_surprise_sub),
                R.drawable.bg_banner_gold
            )
        )
        binding.rvTasks.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvTasks.adapter = adapter
        binding.etSearch.doAfterTextChanged {
            displayLimit = PAGE_SIZE
            renderList()
        }
        if (category == null) {
            setupBanner()
        } else {
            binding.bannerContainer.visibility = View.GONE
        }
        setupRefreshAndLoadMore()
        setupKindChips()
        setupFilters()
        refreshFilterTabs()
        refreshSortRows()
        applyStaffUi()
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            latestState = state
            val enableTags = state.role == UserRole.STAFF || state.role == UserRole.LEAD
            if (enableTags != staffTagsEnabled) {
                staffTagsEnabled = enableTags
                adapter = createAdapter(staffTagsEnabled)
                binding.rvTasks.adapter = adapter
                applyStaffUi()
            }
            renderList()
        }
    }

    private fun setupKindChips() {
        binding.chipAll.setOnClickListener {
            kindFilter = HallKindFilter.ALL
            displayLimit = PAGE_SIZE
            renderList()
        }
        binding.chipCustom.setOnClickListener {
            kindFilter = HallKindFilter.CUSTOM
            displayLimit = PAGE_SIZE
            renderList()
        }
        binding.chipFree.setOnClickListener {
            kindFilter = HallKindFilter.FREE
            displayLimit = PAGE_SIZE
            renderList()
        }
    }

    override fun onResume() {
        super.onResume()
        startBannerAutoScroll()
    }

    override fun onPause() {
        stopBannerAutoScroll()
        super.onPause()
    }

    private fun setupBanner() {
        binding.bannerFlipper.removeAllViews()
        bannerItems.forEach { item ->
            val bannerBinding = ItemHallBannerBinding.inflate(layoutInflater, binding.bannerFlipper, false)
            bannerBinding.bannerContainer.setBackgroundResource(item.bgRes)
            bannerBinding.tvBannerTitle.text = item.title
            bannerBinding.tvBannerSub.text = item.subtitle
            ClickUtils.applySingleDebouncing(bannerBinding.btnBannerClaim) {
                Toast.makeText(requireContext(), R.string.hall_banner_claim_toast, Toast.LENGTH_SHORT).show()
            }
            ClickUtils.applySingleDebouncing(bannerBinding.btnBannerMore) {
                Toast.makeText(requireContext(), R.string.demo_dev_toast, Toast.LENGTH_SHORT).show()
            }
            binding.bannerFlipper.addView(bannerBinding.root)
        }
        setupBannerDots(0)
    }

    private fun setupBannerDots(activeIndex: Int) {
        binding.bannerDots.removeAllViews()
        val gap = (4 * resources.displayMetrics.density).toInt()
        bannerItems.indices.forEach { index ->
            val dot = View(requireContext())
            val active = index == activeIndex
            val size = ((if (active) 8 else 6) * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            if (index > 0) params.marginStart = gap
            dot.layoutParams = params
            dot.setBackgroundResource(
                if (index == activeIndex) R.drawable.bg_banner_dot_active
                else R.drawable.bg_banner_dot_inactive
            )
            binding.bannerDots.addView(dot)
        }
    }

    private fun startBannerAutoScroll() {
        stopBannerAutoScroll()
        if (bannerItems.size <= 1) return
        bannerRunnable = Runnable {
            if (!isAdded || binding.bannerFlipper.childCount <= 1) return@Runnable
            binding.bannerFlipper.showNext()
            setupBannerDots(binding.bannerFlipper.displayedChild)
            bannerHandler.postDelayed(bannerRunnable!!, BANNER_INTERVAL_MS)
        }
        bannerHandler.postDelayed(bannerRunnable!!, BANNER_INTERVAL_MS)
    }

    private fun stopBannerAutoScroll() {
        bannerRunnable?.let { bannerHandler.removeCallbacks(it) }
        bannerRunnable = null
    }

    private fun setupRefreshAndLoadMore() {
        binding.pullRefresh.onRefresh = { refreshTasks() }
        binding.rvTasks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                val lm = recyclerView.layoutManager ?: return
                val last = when (lm) {
                    is GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                if (last >= lm.itemCount - 2) {
                    loadMoreTasks()
                }
            }
        })
    }

    private fun setupFilters() {
        groupAdapter = SceneGroupAdapter { group ->
            draftGroup = group
            draftChild = null
            groupAdapter.selectedId = group?.id ?: SceneGroupAdapter.ALL_ID
            childAdapter.submit(sceneChildrenForGroup(group), null)
        }
        childAdapter = SceneChildAdapter { child ->
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

    private fun refreshTasks() {
        binding.pullRefresh.setRefreshing(true)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(800)
            displayLimit = PAGE_SIZE
            binding.pullRefresh.setRefreshing(false)
            renderList()
        }
    }

    private fun loadMoreTasks() {
        val state = latestState ?: return
        val total = filteredTasks(state).size
        if (displayLimit >= total || isLoadingMore) return
        isLoadingMore = true
        binding.tvLoadMore.visibility = View.VISIBLE
        binding.tvLoadMore.setText(R.string.hall_loading_more)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            displayLimit += PAGE_SIZE
            isLoadingMore = false
            renderList()
        }
    }

    private fun togglePanel(panel: CrowdFilterPanel) {
        if (openPanel == panel) {
            closePanel()
            return
        }
        openPanel = panel
        if (panel == CrowdFilterPanel.SCENE) {
            draftGroup = appliedGroup
            draftChild = appliedChild
            groupAdapter.selectedId = draftGroup?.id ?: SceneGroupAdapter.ALL_ID
            childAdapter.submit(sceneChildrenForGroup(draftGroup), draftChild)
        }
        if (panel == CrowdFilterPanel.DURATION) {
            draftDuration = appliedDuration
            durationAdapter.selected = appliedDuration
        }
        binding.filterOverlay.visibility = View.VISIBLE
        binding.pullRefresh.alpha = 0.35f
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
        binding.pullRefresh.alpha = 1f
        refreshFilterTabs()
    }

    private fun resetCurrentPanel() {
        when (openPanel) {
            CrowdFilterPanel.SCENE -> {
                appliedGroup = null
                appliedChild = null
                draftGroup = null
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
        displayLimit = PAGE_SIZE
        refreshFilterTabs()
        renderList()
    }

    private fun applyCurrentPanel() {
        when (openPanel) {
            CrowdFilterPanel.SCENE -> {
                appliedGroup = draftGroup
                appliedChild = draftChild
            }
            CrowdFilterPanel.DURATION -> appliedDuration = draftDuration
            else -> Unit
        }
        displayLimit = PAGE_SIZE
        closePanel()
        renderList()
    }

    private fun applySort(sort: CrowdHallSort) {
        appliedSort = sort
        displayLimit = PAGE_SIZE
        closePanel()
        renderList()
    }

    private fun setupCategoryHeader() {
        val cat = category
        if (cat == null) {
            binding.btnBack.visibility = View.GONE
            binding.tvTitle.setText(R.string.tab_hall)
            return
        }
        val role = viewModel.ui.value?.role ?: Prefs(requireContext()).role
        binding.btnBack.visibility = View.VISIBLE
        binding.tvTitle.setText(cat.titleResFor(role))
        ClickUtils.applySingleDebouncing(binding.btnBack) {
            (parentFragment as? HallCategoryNavigator)?.closeCategory()
        }
    }

    private fun categoryTasks(): List<Task> {
        val cat = category ?: return viewModel.hallTasks
        return viewModel.hallTasks.filter { cat.matches(it) }
    }

    private fun filteredTasks(state: CollectorUiState): List<Task> {
        val query = binding.etSearch.text?.toString().orEmpty()
        return categoryTasks()
            .filter { matchesSearch(it, query) }
            .filter { matchesKind(it) }
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
        if (staffTagsEnabled) {
            updateKindChipCounts()
        }
        val filtered = filteredTasks(state)
        val page = filtered.take(displayLimit)
        adapter.submitList(
            page.map { task ->
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
        )
        binding.tvEmpty.visibility = if (page.isEmpty()) View.VISIBLE else View.GONE
        binding.pullRefresh.visibility = if (page.isEmpty()) View.INVISIBLE else View.VISIBLE
        when {
            isLoadingMore -> {
                binding.tvLoadMore.visibility = View.VISIBLE
                binding.tvLoadMore.setText(R.string.hall_loading_more)
            }
            displayLimit < filtered.size -> binding.tvLoadMore.visibility = View.GONE
            filtered.isNotEmpty() -> {
                binding.tvLoadMore.visibility = View.VISIBLE
                binding.tvLoadMore.setText(R.string.hall_no_more)
            }
            else -> binding.tvLoadMore.visibility = View.GONE
        }
        refreshFilterTabs()
    }

    private fun matchesKind(task: Task): Boolean {
        if (!staffTagsEnabled) return true
        return when (kindFilter) {
            HallKindFilter.ALL -> true
            HallKindFilter.CUSTOM -> task.kind == TaskKind.CUSTOM
            HallKindFilter.FREE -> task.kind == TaskKind.FREE
        }
    }

    private fun updateKindChipCounts() {
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
        bindKindChip(binding.chipAll, kindFilter == HallKindFilter.ALL)
        bindKindChip(binding.chipCustom, kindFilter == HallKindFilter.CUSTOM)
        bindKindChip(binding.chipFree, kindFilter == HallKindFilter.FREE)
    }

    private fun bindKindChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
        chip.setTextColor(
            ContextCompat.getColor(requireContext(), if (selected) R.color.card_white else R.color.text_dark)
        )
        chip.paint.isFakeBoldText = selected
    }

    private fun matchesScene(task: Task): Boolean {
        appliedChild?.let { child ->
            if (task.sceneChildName != child) return false
        }
        val group = appliedGroup ?: return true
        return task.sceneGroupName == group.name
    }

    private fun sceneChildrenForGroup(group: CrowdSceneGroup?): List<String> {
        return group?.children ?: CrowdScenes.allChildren()
    }

    private fun matchesDuration(task: Task): Boolean {
        val bucket = appliedDuration ?: return true
        return task.durationMax in bucket.min..bucket.max
    }

    private fun matchesSearch(task: Task, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return task.title.contains(q, ignoreCase = true) ||
            task.scene.contains(q, ignoreCase = true) ||
            task.device.contains(q, ignoreCase = true)
    }

    private fun refreshFilterTabs() {
        bindTab(
            binding.tvScene,
            binding.ivScene,
            binding.indicatorScene,
            openPanel == CrowdFilterPanel.SCENE,
            openPanel == CrowdFilterPanel.NONE && (appliedGroup != null || appliedChild != null)
        )
        bindTab(
            binding.tvDurationFilter,
            binding.ivDuration,
            binding.indicatorDuration,
            openPanel == CrowdFilterPanel.DURATION,
            openPanel == CrowdFilterPanel.NONE && appliedDuration != null
        )
        bindTab(
            binding.tvSort,
            binding.ivSort,
            binding.indicatorSort,
            openPanel == CrowdFilterPanel.SORT,
            openPanel == CrowdFilterPanel.NONE && appliedSort != CrowdHallSort.RECOMMEND
        )
        binding.tvSort.setText(
            when (appliedSort) {
                CrowdHallSort.RECOMMEND -> R.string.hall_sort_recommend
                CrowdHallSort.QUOTA -> R.string.hall_sort_quota
                CrowdHallSort.PRICE -> R.string.hall_sort_price
            }
        )
    }

    private fun bindTab(
        label: TextView,
        arrow: ImageView,
        indicator: View,
        opened: Boolean,
        highlighted: Boolean
    ) {
        val active = opened || highlighted
        val color = ContextCompat.getColor(
            requireContext(),
            if (active) R.color.brand_orange else R.color.text_gray
        )
        label.setTextColor(color)
        label.paint.isFakeBoldText = active
        arrow.setColorFilter(color)
        arrow.rotation = if (opened) 180f else 0f
        indicator.visibility = if (active) View.VISIBLE else View.GONE
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

    private fun createAdapter(showStaffTags: Boolean) = CrowdTaskAdapter(
        onItemClick = { TaskDetailActivity.start(requireContext(), it) },
        onClaim = { task -> TaskDetailActivity.start(requireContext(), task) },
        onCapture = { task -> HallTaskActions.openCapture(requireContext(), task) },
        showStaffTags = showStaffTags
    )

    private fun showStaffTags(): Boolean {
        val role = viewModel.ui.value?.role ?: Prefs(requireContext()).role
        return role == UserRole.STAFF || role == UserRole.LEAD
    }

    private fun applyStaffUi() {
        val staffMode = staffTagsEnabled
        binding.kindChipRow.visibility = if (staffMode) View.VISIBLE else View.GONE
        binding.sortPrice.visibility = if (staffMode) View.GONE else View.VISIBLE
        if (!staffMode) {
            kindFilter = HallKindFilter.ALL
        }
        if (staffMode && appliedSort == CrowdHallSort.PRICE) {
            appliedSort = CrowdHallSort.RECOMMEND
            refreshFilterTabs()
            refreshSortRows()
        }
    }

    companion object {
        private const val ARG_CATEGORY = "category_id"
        private const val PAGE_SIZE = 10
        private const val BANNER_INTERVAL_MS = 4000L

        fun newInstance(categoryId: String? = null) = CrowdHallTaskListFragment().apply {
            arguments = Bundle().apply {
                categoryId?.let { putString(ARG_CATEGORY, it) }
            }
        }
    }
}
