package com.bingo.smartna.collector.mine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.login.LoginActivity
import com.bingo.smartna.databinding.FragmentMineBinding
import com.bingo.smartna.databinding.ItemSettingRowBinding
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ClickUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MineFragment : BaseFragment<FragmentMineBinding, CollectorViewModel>() {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMineBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return ViewModelProvider(requireActivity())[CollectorViewModel::class.java]
    }

    override fun initData() {
        bindSettings(getString(R.string.mine_storage_computing))
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { computeStorage(appContext) }
            if (view != null) bindSettings(text)
        }
        ClickUtils.applySingleDebouncing(binding.btnVerify) { showDevToast() }
        ClickUtils.applySingleDebouncing(binding.btnLogout) { viewModel.logout() }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            binding.tvPhone.text = state.maskedPhone
            binding.tvAvatar.text = state.avatarLetter.ifBlank { getString(R.string.mine_avatar_fallback) }
        }
        viewModel.loggedOut.observe(viewLifecycleOwner) { loggedOut ->
            if (loggedOut == true) {
                ActivityUtils.startActivity(LoginActivity::class.java)
                requireActivity().finish()
            }
        }
    }

    private fun bindSettings(storageText: String) {
        if (!isAdded) return
        val rows = listOf(
            getString(R.string.mine_account) to null,
            getString(R.string.mine_storage) to storageText,
            getString(R.string.mine_language) to getString(R.string.mine_language_value),
            getString(R.string.mine_privacy) to null,
            getString(R.string.mine_share_list) to null,
            getString(R.string.mine_service) to null,
            getString(R.string.mine_about) to null
        )
        binding.settingContainer.removeAllViews()
        rows.forEachIndexed { index, (title, value) ->
            val row = ItemSettingRowBinding.inflate(layoutInflater, binding.settingContainer, false)
            row.tvTitle.text = title
            if (value.isNullOrBlank()) {
                row.tvValue.visibility = View.GONE
            } else {
                row.tvValue.visibility = View.VISIBLE
                row.tvValue.text = value
            }
            row.divider.visibility = if (index == rows.lastIndex) View.GONE else View.VISIBLE
            ClickUtils.applySingleDebouncing(row.root) { showDevToast() }
            binding.settingContainer.addView(row.root)
        }
    }

    private fun showDevToast() {
        Toast.makeText(requireContext(), R.string.demo_dev_toast, Toast.LENGTH_SHORT).show()
    }

    private fun computeStorage(context: android.content.Context): String {
        val bytes = dirSize(context.cacheDir) + dirSize(context.filesDir)
        return when {
            bytes >= 1L shl 30 -> String.format(Locale.CHINA, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
            else -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
        }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
