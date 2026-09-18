package com.bingo.smartna.base.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding

/**
 * Fragment 基类：ViewBinding + ViewModel 生命周期模板。
 */
abstract class BaseFragment<VB : ViewBinding, VM : BaseViewModel> : Fragment() {

    private var _binding: VB? = null
    protected val binding: VB
        get() = _binding
            ?: error("binding 仅在 onCreateView 与 onDestroyView 之间可用")

    protected lateinit var viewModel: VM

    abstract fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    /**
     * 自定义 ViewModel 创建（例如带 Factory）。
     * 返回 null 时按第二个泛型参数自动创建，未声明泛型则用 [BaseViewModel]。
     */
    protected open fun initViewModel(): VM? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initParam()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = inflateBinding(inflater, container)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = initViewModel() ?: ViewModelProvider(this)[resolveViewModelClass(this)]
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        registerUiChange()
        initData()
        initViewObservable()
    }

    override fun onDestroyView() {
        (activity as? BaseActivity<*, *>)?.hideLoading()
        _binding = null
        super.onDestroyView()
    }

    protected open fun initParam() {}
    protected open fun initData() {}
    protected open fun initViewObservable() {}

    private fun registerUiChange() {
        viewModel.loading.observe(viewLifecycleOwner) { show ->
            val activity = activity as? BaseActivity<*, *> ?: return@observe
            if (show) activity.showLoading() else activity.hideLoading()
        }
    }
}
