package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.databinding.FragmentHallCategoryBinding

class HallCategoryFragment : BaseFragment<FragmentHallCategoryBinding, CollectorViewModel>() {

    private val adapter = HallCategoryAdapter { category ->
        (parentFragment as? HallCategoryNavigator)?.openCategory(category)
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHallCategoryBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        binding.rvCategories.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCategories.adapter = adapter
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            val role = state.role
            val items = HallCategory.homeEntries(role).map { category ->
                val remain = viewModel.hallTasks.count { task ->
                    category.matches(task) &&
                        task.id !in state.claimedIds &&
                        (state.quotaLeft[task.id] ?: 0) > 0
                }
                HallCategoryItem(
                    category = category,
                    remainCount = remain,
                    titleRes = category.titleResFor(role),
                    bgRes = category.bgResFor(role)
                )
            }
            adapter.submitList(items)
        }
    }
}
