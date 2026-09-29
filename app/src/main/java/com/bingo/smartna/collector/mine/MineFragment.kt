package com.bingo.smartna.collector.mine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseFragment
import com.bingo.smartna.collector.CollectorUiState
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.MainActivity
import com.bingo.smartna.collector.device.DevicePageActivity
import com.bingo.smartna.collector.login.LoginActivity
import com.bingo.smartna.collector.login.LoginViewModel
import com.bingo.smartna.databinding.DialogApplyUpgradeBinding
import com.bingo.smartna.databinding.FragmentMineBinding
import com.bingo.smartna.databinding.ItemSettingRowBinding
import com.blankj.utilcode.util.ClickUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MineFragment : BaseFragment<FragmentMineBinding, CollectorViewModel>() {

    private var upgradeExpanded = false

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMineBinding.inflate(inflater, container, false)

    override fun initViewModel(): CollectorViewModel {
        return com.bingo.smartna.collector.CollectorViewModels.get(requireActivity().application)
    }

    override fun initData() {
        bindGraspProfile()
        bindSettings(getString(R.string.mine_storage_computing))
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { computeStorage(appContext) }
            if (view != null) bindSettings(text)
        }
        ClickUtils.applySingleDebouncing(binding.tvVerified) { showDevToast() }
        ClickUtils.applySingleDebouncing(binding.btnLogout) { viewModel.logout() }
        ClickUtils.applySingleDebouncing(binding.upgradeHeader) { toggleUpgradeSection() }
        updateUpgradeExpandedUi()
        ClickUtils.applySingleDebouncing(binding.btnApplyStaff) {
            showApplyDialog(
                titleRes = R.string.mine_apply_staff_title,
                descRes = R.string.mine_apply_staff_desc,
                hintRes = R.string.mine_apply_staff_hint,
                preset = LoginViewModel.ACCOUNT_STAFF
            ) { viewModel.upgradeTo(UserRole.STAFF, it) }
        }
        ClickUtils.applySingleDebouncing(binding.btnApplyLead) {
            showApplyDialog(
                titleRes = R.string.mine_apply_lead_title,
                descRes = R.string.mine_apply_lead_desc,
                hintRes = R.string.mine_apply_lead_hint,
                preset = LoginViewModel.ACCOUNT_LEAD
            ) { viewModel.upgradeTo(UserRole.LEAD, it) }
        }
    }

    override fun initViewObservable() {
        viewModel.ui.observe(viewLifecycleOwner) { state ->
            binding.tvPhone.text = state.profileTitle
            binding.tvAvatar.text = state.avatarLetter.ifBlank { getString(R.string.mine_avatar_fallback) }
            binding.tvStatFrames.text = String.format(
                Locale.CHINA,
                "%,d",
                state.userTasks.sumOf { it.doneClips } * 240
            )
            binding.tvStatMoney.text = getString(R.string.wallet_balance, state.walletBalance)
            binding.tvStatStreak.text = getString(R.string.mine_streak_days, 6)
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
            startActivity(com.bingo.smartna.MainActivity.freshStart(requireContext()))
            requireActivity().finish()
        }
        viewModel.loggedOut.observe(viewLifecycleOwner) { loggedOut ->
            if (loggedOut == true) {
                viewModel.consumeLoggedOut()
                val intent = android.content.Intent(requireContext(), LoginActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                startActivity(intent)
                requireActivity().finish()
            }
        }
    }

    private fun renderRole(state: CollectorUiState) {
        if (state.role == UserRole.CROWD) {
            binding.tvRole.text = getString(R.string.mine_master_level)
        } else {
            val roleRes = when (state.role) {
                UserRole.STAFF -> R.string.mine_role_staff
                UserRole.LEAD -> R.string.mine_role_lead
                else -> R.string.mine_role_crowd
            }
            binding.tvRole.setText(roleRes)
        }
        binding.tvVerified.visibility = if (state.role == UserRole.LEAD) View.GONE else View.VISIBLE
        binding.upgradeSection.visibility = if (state.role == UserRole.CROWD) View.VISIBLE else View.GONE
        if (state.role != UserRole.CROWD) {
            upgradeExpanded = false
            updateUpgradeExpandedUi()
        }
    }

    private fun toggleUpgradeSection() {
        upgradeExpanded = !upgradeExpanded
        updateUpgradeExpandedUi()
    }

    private fun updateUpgradeExpandedUi() {
        binding.upgradeContent.visibility = if (upgradeExpanded) View.VISIBLE else View.GONE
        binding.ivUpgradeChevron.rotation = if (upgradeExpanded) 270f else 90f
    }

    private fun showApplyDialog(
        titleRes: Int,
        descRes: Int,
        hintRes: Int,
        preset: String,
        onSubmit: (String) -> Unit
    ) {
        val dialogBinding = DialogApplyUpgradeBinding.inflate(layoutInflater)
        dialogBinding.tvTitle.setText(titleRes)
        dialogBinding.tvDesc.setText(descRes)
        dialogBinding.etArea.setHint(hintRes)
        dialogBinding.etArea.setText(preset)
        dialogBinding.etArea.setSelection(dialogBinding.etArea.text?.length ?: 0)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        ClickUtils.applySingleDebouncing(dialogBinding.btnCancel) { dialog.dismiss() }
        ClickUtils.applySingleDebouncing(dialogBinding.btnSubmit) {
            val area = dialogBinding.etArea.text?.toString()?.trim().orEmpty()
            if (area.isBlank()) {
                Toast.makeText(requireContext(), R.string.mine_apply_area_empty, Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                onSubmit(area)
            }
        }
        dialog.show()
    }

    private fun bindGraspProfile() {
        binding.tvRole.text = getString(R.string.mine_master_level)
        ClickUtils.applySingleDebouncing(binding.rowMyTasks) {
            startActivity(MainActivity.intentForTab(requireContext(), R.id.nav_tasks))
        }
        ClickUtils.applySingleDebouncing(binding.rowMyDevice) {
            DevicePageActivity.start(requireContext())
        }
        ClickUtils.applySingleDebouncing(binding.rowBank) {
            showDevToast()
        }
        ClickUtils.applySingleDebouncing(binding.rowSettings) {
            binding.settingCard.visibility =
                if (binding.settingCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
