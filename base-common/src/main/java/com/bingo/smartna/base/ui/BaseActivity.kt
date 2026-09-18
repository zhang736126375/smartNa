package com.bingo.smartna.base.ui

import android.app.ProgressDialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding

/**
 * Activity 基类：ViewBinding + ViewModel 生命周期模板。
 */
abstract class BaseActivity<VB : ViewBinding, VM : BaseViewModel> : AppCompatActivity() {

    protected lateinit var binding: VB
    protected lateinit var viewModel: VM

    private var loadingDialog: ProgressDialog? = null

    abstract fun inflateBinding(): VB

    /**
     * 自定义 ViewModel 创建（例如带 Factory）。
     * 返回 null 时按第二个泛型参数自动创建，未声明泛型则用 [BaseViewModel]。
     */
    protected open fun initViewModel(): VM? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initParam()
        binding = inflateBinding()
        setContentView(binding.root)
        viewModel = initViewModel() ?: ViewModelProvider(this)[resolveViewModelClass(this)]
        lifecycle.addObserver(viewModel)
        registerUiChange()
        initData()
        initViewObservable()
    }

    override fun onDestroy() {
        hideLoading()
        super.onDestroy()
    }

    protected open fun initParam() {}
    protected open fun initData() {}
    protected open fun initViewObservable() {}

    open fun showLoading(message: String = "加载中…") {
        if (isFinishing || loadingDialog?.isShowing == true) return
        loadingDialog = ProgressDialog(this).apply {
            setMessage(message)
            setCancelable(false)
            show()
        }
    }

    open fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun registerUiChange() {
        viewModel.loading.observe(this) { show ->
            if (show) showLoading() else hideLoading()
        }
    }
}
