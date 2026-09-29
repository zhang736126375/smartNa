package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.databinding.FragmentGraspHallBinding
import com.bingo.smartna.databinding.ItemGraspCategoryBinding
import com.blankj.utilcode.util.ClickUtils

class GraspHallFragment : BaseFragment<FragmentGraspHallBinding, CollectorViewModel>() {

    private var selectedCategory: HallCategory? = null
    private var keyword = ""
    private var openPanel = CrowdFilterPanel.NONE
    private var appliedGroup: CrowdSceneGroup? = null
    private var appliedChild: String? = null
    private var appliedDuration: DurationBucket? = null
    private var appliedSort = CrowdHallSort.RECOMMEND
    private var draftGroup: CrowdSceneGroup? = null
    private var draftChild: String? = null
    private var draftDuration: DurationBucket? = null

    private lateinit var groupAdapter: SceneGroupAdapter
    private lateinit var childAdapter: SceneChildAdapter
    private lateinit var durationAdapter: DurationFilterAdapter
    private val filterBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closePanel()
        }
    }

    private val hallCategories = listOf(
        HallCategory.LIFE,
        HallCategory.AGRI,
        HallCategory.PRODUCE,
        HallCategory.WAREHOUSE
    )

    private val taskAdapter = GraspTaskAdapter(
        onItemClick = { TaskDetailActivity.start(requireContext(), it) },
        onAction = { task, claimed ->
            if (claimed) {
                HallTaskActions.openCapture(requireContext(), task)
            } else {
                TaskDetailActivity.start(requireContext(), task)
            }
        }
    )

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentGraspHallBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel =
        CollectorViewModels.get(requireActivity().application)

    override fun initData() {
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = taskAdapter
        binding.rvCategories.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.etSearch.doAfterTextChanged {
            keyword = it?.toString()?.trim().orEmpty()
            render(viewModel.ui.value)
        }
        binding.tvTicker.isSelected = true
        ClickUtils.applySingleDebouncing(binding.btnCheckIn) {
            Toast.makeText(requireContext(), R.string.grasp_checkin_done, Toast.LENGTH_SHORT).show()
        }
        ClickUtils.applySingleDebouncing(binding.btnMessage) {
            Toast.makeText(requireContext(), R.string.demo_dev_toast, Toast.LENGTH_SHORT).show()
        }
        ClickUtils.applySingleDebouncing(binding.btnSearch) {
            keyword = binding.etSearch.text?.toString()?.trim().orEmpty()
            render(viewModel.ui.value)
        }
        setupFilters()
        refreshFilterChips()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, filterBackCallback)
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { render(it) }
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
        childAdapter.submit(CrowdScenes.allChildren(), null)

        binding.chipScene.setOnClickListener { togglePanel(CrowdFilterPanel.SCENE) }
        binding.chipDuration.setOnClickListener { togglePanel(CrowdFilterPanel.DURATION) }
        binding.chipSort.setOnClickListener { togglePanel(CrowdFilterPanel.SORT) }
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
        binding.scenePanel.visibility = if (panel == CrowdFilterPanel.SCENE) View.VISIBLE else View.GONE
        binding.rvDuration.visibility = if (panel == CrowdFilterPanel.DURATION) View.VISIBLE else View.GONE
        binding.sortPanel.visibility = if (panel == CrowdFilterPanel.SORT) View.VISIBLE else View.GONE
        binding.filterActions.visibility =
            if (panel == CrowdFilterPanel.SORT) View.GONE else View.VISIBLE
        filterBackCallback.isEnabled = true
        refreshFilterChips()
        refreshSortRows()
        constrainFilterSheet()
    }

    private fun constrainFilterSheet() {
        binding.filterOverlay.post {
            if (!isAdded || openPanel == CrowdFilterPanel.NONE) return@post
            val overlayH = binding.filterOverlay.height
            if (overlayH <= 0) return@post
            val actionsH = if (binding.filterActions.visibility == View.VISIBLE) {
                binding.filterActions.height.takeIf { it > 0 }
                    ?: (76 * resources.displayMetrics.density).toInt()
            } else 0
            val maxBody = (overlayH - actionsH).coerceAtLeast(
                (120 * resources.displayMetrics.density).toInt()
            )
            val preferred = (200 * resources.displayMetrics.density).toInt()
            if (openPanel == CrowdFilterPanel.SCENE) {
                val params = binding.scenePanel.layoutParams
                params.height = minOf(preferred, maxBody)
                binding.scenePanel.layoutParams = params
            }
        }
    }

    private fun closePanel() {
        openPanel = CrowdFilterPanel.NONE
        binding.filterOverlay.visibility = View.GONE
        filterBackCallback.isEnabled = false
        refreshFilterChips()
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
        refreshFilterChips()
        render(viewModel.ui.value)
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
        closePanel()
        render(viewModel.ui.value)
    }

    private fun applySort(sort: CrowdHallSort) {
        appliedSort = sort
        closePanel()
        render(viewModel.ui.value)
    }

    private fun sceneChildrenForGroup(group: CrowdSceneGroup?): List<String> {
        return group?.children ?: CrowdScenes.allChildren()
    }

    private fun render(state: CollectorUiState?) {
        if (state == null) return
        val counts = hallCategories.associateWith { category ->
            viewModel.hallTasks.count { category.matches(it) }
        }
        binding.rvCategories.adapter = CategoryAdapter(
            hallCategories,
            counts,
            selectedCategory
        ) { category ->
            selectedCategory = if (selectedCategory == category) null else category
            render(state)
        }
        val tasks = filteredTasks(state)
        taskAdapter.submit(state, tasks)
        binding.tvFooter.text = getString(R.string.grasp_hall_footer_count, tasks.size)
        refreshFilterChips()
    }

    private fun filteredTasks(state: CollectorUiState): List<Task> {
        return viewModel.hallTasks
            .filter { selectedCategory?.matches(it) != false }
            .filter { matchesSearch(it) }
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

    private fun matchesSearch(task: Task): Boolean {
        if (keyword.isBlank()) return true
        return task.title.contains(keyword, ignoreCase = true) ||
            task.scene.contains(keyword, ignoreCase = true)
    }

    private fun matchesScene(task: Task): Boolean {
        appliedChild?.let { child ->
            if (task.sceneChildName != child && !task.scene.contains(child)) return false
        }
        val group = appliedGroup ?: return true
        val shortName = group.name.removeSuffix("场景")
        return task.sceneGroupName == group.name ||
            task.scene.contains(group.name) ||
            task.scene.contains(shortName)
    }

    private fun matchesDuration(task: Task): Boolean {
        val bucket = appliedDuration ?: return true
        return task.durationMax in bucket.min..bucket.max
    }

    private fun refreshFilterChips() {
        bindChip(
            binding.chipScene,
            sceneChipText(),
            openPanel == CrowdFilterPanel.SCENE,
            appliedGroup != null || appliedChild != null
        )
        bindChip(
            binding.chipDuration,
            appliedDuration?.label?.plus(" ▾") ?: getString(R.string.grasp_filter_duration),
            openPanel == CrowdFilterPanel.DURATION,
            appliedDuration != null
        )
        bindChip(
            binding.chipSort,
            sortChipText(),
            openPanel == CrowdFilterPanel.SORT,
            appliedSort != CrowdHallSort.RECOMMEND
        )
    }

    private fun sceneChipText(): String {
        return when {
            appliedChild != null -> appliedChild + " ▾"
            appliedGroup != null -> appliedGroup!!.name.removeSuffix("场景") + " ▾"
            else -> getString(R.string.grasp_filter_scene)
        }
    }

    private fun sortChipText(): String = when (appliedSort) {
        CrowdHallSort.RECOMMEND -> getString(R.string.grasp_filter_sort)
        CrowdHallSort.QUOTA -> getString(R.string.hall_sort_quota) + " ▾"
        CrowdHallSort.PRICE -> getString(R.string.hall_sort_price) + " ▾"
    }

    private fun bindChip(chip: TextView, text: String, open: Boolean, active: Boolean) {
        chip.text = text
        val selected = open || active
        chip.setBackgroundResource(if (selected) R.drawable.bg_pill_brand else R.drawable.bg_pill_ghost)
        chip.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.brand_primary_active else R.color.text_secondary
            )
        )
    }

    private fun refreshSortRows() {
        paintSortRow(binding.tvSortRecommend, binding.ivSortRecommend, appliedSort == CrowdHallSort.RECOMMEND)
        paintSortRow(binding.tvSortQuota, binding.ivSortQuota, appliedSort == CrowdHallSort.QUOTA)
        paintSortRow(binding.tvSortPrice, binding.ivSortPrice, appliedSort == CrowdHallSort.PRICE)
    }

    private fun paintSortRow(title: TextView, check: ImageView, selected: Boolean) {
        title.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.brand_primary else R.color.text_primary
            )
        )
        title.paint.isFakeBoldText = selected
        check.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private class CategoryAdapter(
        private val items: List<HallCategory>,
        private val counts: Map<HallCategory, Int>,
        private val selected: HallCategory?,
        private val onClick: (HallCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemGraspCategoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.bind(item, counts[item] ?: 0, selected == item, onClick)
        }

        override fun getItemCount() = items.size

        class Holder(private val binding: ItemGraspCategoryBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(
                item: HallCategory,
                count: Int,
                active: Boolean,
                onClick: (HallCategory) -> Unit
            ) {
                val context = binding.root.context
                binding.cover.setBackgroundResource(coverOf(item))
                binding.ivIcon.setImageResource(iconOf(item))
                binding.coverWrap.setBackgroundResource(
                    if (active) R.drawable.bg_cat_selected else 0
                )
                binding.tvName.setText(displayName(item))
                binding.tvCount.text = count.toString()
                binding.tvName.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (active) R.color.brand_primary_active else R.color.text_primary
                    )
                )
                binding.root.setOnClickListener { onClick(item) }
            }

            private fun displayName(item: HallCategory): Int = when (item) {
                HallCategory.LIFE -> R.string.grasp_cat_life
                HallCategory.AGRI -> R.string.grasp_cat_agri
                HallCategory.PRODUCE -> R.string.grasp_cat_factory
                else -> R.string.grasp_cat_shop
            }

            private fun coverOf(item: HallCategory): Int = when (item) {
                HallCategory.LIFE -> R.drawable.bg_cover_warm
                HallCategory.AGRI -> R.drawable.bg_cover_agri
                HallCategory.PRODUCE -> R.drawable.bg_cover_wood
                else -> R.drawable.bg_cover_shop
            }

            private fun iconOf(item: HallCategory): Int = when (item) {
                HallCategory.LIFE -> R.drawable.ic_cat_life
                HallCategory.AGRI -> R.drawable.ic_cat_agri
                HallCategory.PRODUCE -> R.drawable.ic_cat_factory
                else -> R.drawable.ic_cat_shop
            }
        }
    }
}
