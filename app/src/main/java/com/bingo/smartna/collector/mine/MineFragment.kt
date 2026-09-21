package com.bingo.smartna.collector.mine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.collector.login.LoginActivity
import com.bingo.smartna.collector.login.LoginViewModel
import com.bingo.smartna.databinding.DialogApplyUpgradeBinding
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
        ClickUtils.applySingleDebouncing(binding.btnApplyStaff) {
            showApplyDialog(
                titleRes = R.string.mine_apply_staff_title,
                hintRes = R.string.mine_apply_staff_hint,
                preset = LoginViewModel.ACCOUNT_STAFF
            ) { viewModel.upgradeTo(UserRole.STAFF, it) }
        }
        ClickUtils.applySingleDebouncing(binding.btnApplyLead) {
            showApplyDialog(
                titleRes = R.string.mine_apply_lead_title,
                hintRes = R.string.mine_apply_lead_hint,
                preset = LoginViewModel.ACCOUNT_LEAD
            ) { viewModel.upgradeTo(UserRole.LEAD, it) }
        }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            binding.tvPhone.text = state.profileTitle
            binding.tvAvatar.text = state.avatarLetter.ifBlank { getString(R.string.mine_avatar_fallback) }
            renderRole(state)
        }
        viewModel.upgraded.observe(viewLifecycleOwner) { target ->
            if (target == null) return@observe
            viewModel.consumeUpgrade()
            val message = if (target == UserRole.LEAD) {
                R.string.mine_upgrade_lead_success
            } else {
                R.string.mine_upgrade_staff_success
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            val intent = android.content.Intent(requireContext(), com.bingo.smartna.MainActivity::class.java)
            requireActivity().finish()
            startActivity(intent)
        }
        viewModel.loggedOut.observe(viewLifecycleOwner) { loggedOut ->
            if (loggedOut == true) {
                ActivityUtils.startActivity(LoginActivity::class.java)
                requireActivity().finish()
            }
        }
    }

    private fun renderRole(state: CollectorUiState) {
        val roleRes = when (state.role) {
            UserRole.CROWD -> R.string.mine_role_crowd
            UserRole.STAFF -> R.string.mine_role_staff
            UserRole.LEAD -> R.string.mine_role_lead
        }
        binding.tvRole.setText(roleRes)
        val showVerify = state.role != UserRole.LEAD
        binding.verifyWrap.visibility = if (showVerify) View.VISIBLE else View.GONE
        binding.upgradePanel.visibility = if (state.role == UserRole.CROWD) View.VISIBLE else View.GONE
        binding.btnApplyStaff.isEnabled = true
        binding.btnApplyLead.isEnabled = true
        binding.btnApplyStaff.setText(R.string.mine_apply_staff)
        binding.btnApplyLead.setText(R.string.mine_apply_lead)
        binding.btnApplyStaff.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_dark))
        binding.btnApplyLead.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_dark))
    }

    private fun showApplyDialog(
        titleRes: Int,
        hintRes: Int,
        preset: String,
        onSubmit: (String) -> Unit
    ) {
        val dialogBinding = DialogApplyUpgradeBinding.inflate(layoutInflater)
        dialogBinding.etArea.setHint(hintRes)
        dialogBinding.etArea.setText(preset)
        dialogBinding.etArea.setSelection(dialogBinding.etArea.text?.length ?: 0)
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.mine_apply_submit) { _, _ ->
                val area = dialogBinding.etArea.text?.toString()?.trim().orEmpty()
                if (area.isBlank()) {
                    Toast.makeText(requireContext(), R.string.mine_apply_area_empty, Toast.LENGTH_SHORT).show()
                } else {
                    onSubmit(area)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
