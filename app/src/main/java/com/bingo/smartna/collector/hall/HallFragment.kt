package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.databinding.FragmentHallBinding

class HallFragment : BaseFragment<FragmentHallBinding, CollectorViewModel>(), HallCategoryNavigator {

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            childFragmentManager.popBackStack()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHallBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        childFragmentManager.addOnBackStackChangedListener {
            refreshBackCallback()
        }
        if (childFragmentManager.findFragmentById(R.id.hallContainer) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.hallContainer, HallCategoryFragment())
                .commit()
        }
        refreshBackCallback()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        refreshBackCallback()
    }

    override fun onResume() {
        super.onResume()
        refreshBackCallback()
    }

    private fun refreshBackCallback() {
        backCallback.isEnabled = isAdded && !isHidden && childFragmentManager.backStackEntryCount > 0
    }

    override fun openCategory(category: HallCategory) {
        childFragmentManager.beginTransaction()
            .replace(R.id.hallContainer, HallTaskListFragment.newInstance(category.id))
            .addToBackStack(TAG_LIST)
            .commit()
    }

    override fun closeCategory() {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
        }
    }

    fun resetToHome() {
        while (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStackImmediate()
        }
    }

    private companion object {
        const val TAG_LIST = "hall_list"
    }
}
