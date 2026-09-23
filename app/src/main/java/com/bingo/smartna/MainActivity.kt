package com.bingo.smartna

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.CollectorViewModels
import com.bingo.smartna.collector.HallNavigator
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.collector.device.DeviceFragment
import com.bingo.smartna.collector.hall.HallFragment
import com.bingo.smartna.collector.mine.MineFragment
import com.bingo.smartna.collector.tasks.MyTasksFragment
import com.bingo.smartna.collector.wallet.WalletFragment
import com.bingo.smartna.databinding.ActivityMainBinding

class MainActivity : BaseActivity<ActivityMainBinding, CollectorViewModel>(), HallNavigator {

    private var role: UserRole = UserRole.CROWD
    private var currentTabId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        role = Prefs(this).role
        if (role == UserRole.STAFF) {
            setTheme(R.style.Theme_SmartNa_Staff)
        }
        currentTabId = savedInstanceState?.getInt(STATE_TAB_ID, 0) ?: 0
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (currentTabId != 0) {
            outState.putInt(STATE_TAB_ID, currentTabId)
        }
    }

    override fun inflateBinding() = ActivityMainBinding.inflate(layoutInflater)

    override fun initViewModel(): CollectorViewModel = CollectorViewModels.get(application)

    override fun initData() {
        viewModel.reloadFromPrefs()
        role = Prefs(this).role
        bindTabClicks()
        applyRoleTabs()

        val launchTab = resolveLaunchTabId()
        goToTab(launchTab, resetHall = launchTab == R.id.nav_hall)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        viewModel.reloadFromPrefs()
        role = Prefs(this).role
        applyRoleTabs()
        resolveLaunchTabId().let { goToTab(it) }
    }

    override fun openHall() {
        goToTab(R.id.nav_hall, resetHall = true)
    }

    private fun bindTabClicks() {
        binding.tabHall.setOnClickListener { onTabClicked(R.id.nav_hall) }
        binding.tabTasks.setOnClickListener { onTabClicked(R.id.nav_tasks) }
        binding.tabDevice.setOnClickListener { onTabClicked(R.id.nav_device) }
        binding.tabWallet.setOnClickListener { onTabClicked(R.id.nav_wallet) }
        binding.tabMine.setOnClickListener { onTabClicked(R.id.nav_mine) }
    }

    private fun onTabClicked(tabId: Int) {
        if (!isValidTabId(tabId)) return
        val reselectHall = tabId == currentTabId && tabId == R.id.nav_hall
        goToTab(tabId, resetHall = reselectHall)
    }

    private fun applyRoleTabs() {
        val lead = role == UserRole.LEAD
        binding.tabTasks.visibility = if (lead) android.view.View.GONE else android.view.View.VISIBLE
        binding.tabWallet.visibility = if (lead) android.view.View.GONE else android.view.View.VISIBLE
        binding.labelDevice.setText(if (lead) R.string.tab_team_device else R.string.tab_device)
    }

    private fun resolveLaunchTabId(): Int {
        MainTabCoordinator.consume()?.let { tabId ->
            if (isValidTabId(tabId)) return tabId
        }
        intent.getIntExtra(EXTRA_TAB_ID, -1)
            .takeIf { it != -1 && isValidTabId(it) }
            ?.let {
                intent.removeExtra(EXTRA_TAB_ID)
                return it
            }
        if (currentTabId != 0 && isValidTabId(currentTabId)) return currentTabId
        return R.id.nav_hall
    }

    private fun goToTab(tabId: Int, resetHall: Boolean = false) {
        if (!isValidTabId(tabId)) return
        currentTabId = tabId
        showFragment(tabId, resetHall)
        paintTabs(tabId)
    }

    private fun paintTabs(selectedId: Int) {
        val active = themeColor(androidx.appcompat.R.attr.colorPrimary)
        val inactive = ContextCompat.getColor(this, R.color.text_gray)
        paintOne(binding.iconHall, binding.labelHall, selectedId == R.id.nav_hall, active, inactive)
        paintOne(binding.iconTasks, binding.labelTasks, selectedId == R.id.nav_tasks, active, inactive)
        paintOne(binding.iconDevice, binding.labelDevice, selectedId == R.id.nav_device, active, inactive)
        paintOne(binding.iconWallet, binding.labelWallet, selectedId == R.id.nav_wallet, active, inactive)
        paintOne(binding.iconMine, binding.labelMine, selectedId == R.id.nav_mine, active, inactive)
    }

    private fun paintOne(
        icon: ImageView,
        label: TextView,
        selected: Boolean,
        active: Int,
        inactive: Int
    ) {
        val color = if (selected) active else inactive
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun showFragment(itemId: Int, resetHall: Boolean = false) {
        if (!isValidTabId(itemId)) return
        val tag = tagOf(itemId)
        val allowedTags = allTags().toSet()
        val transaction = supportFragmentManager.beginTransaction()
        allowedTags.forEach { otherTag ->
            if (otherTag == tag) return@forEach
            supportFragmentManager.findFragmentByTag(otherTag)?.let { transaction.hide(it) }
        }
        var target = supportFragmentManager.findFragmentByTag(tag)
        if (target == null) {
            target = createFragment(itemId)
            transaction.add(binding.tabContainer.id, target, tag)
        } else {
            transaction.show(target)
        }
        transaction.commitNow()
        if (resetHall) {
            (target as? HallFragment)?.resetToHome()
        }
    }

    private fun isValidTabId(itemId: Int): Boolean = validTabIds().contains(itemId)

    private fun validTabIds(): Set<Int> = allTags().map { itemIdOf(it) }.toSet()

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_tasks -> MyTasksFragment()
        R.id.nav_device -> DeviceFragment()
        R.id.nav_wallet -> WalletFragment()
        R.id.nav_mine -> MineFragment()
        else -> HallFragment()
    }

    private fun tagOf(itemId: Int) = when (itemId) {
        R.id.nav_tasks -> TAG_TASKS
        R.id.nav_device -> TAG_DEVICE
        R.id.nav_wallet -> TAG_WALLET
        R.id.nav_mine -> TAG_MINE
        else -> TAG_HALL
    }

    private fun itemIdOf(tag: String) = when (tag) {
        TAG_TASKS -> R.id.nav_tasks
        TAG_DEVICE -> R.id.nav_device
        TAG_WALLET -> R.id.nav_wallet
        TAG_MINE -> R.id.nav_mine
        else -> R.id.nav_hall
    }

    private fun allTags() = if (role == UserRole.LEAD) {
        listOf(TAG_HALL, TAG_DEVICE, TAG_MINE)
    } else {
        listOf(TAG_HALL, TAG_TASKS, TAG_DEVICE, TAG_WALLET, TAG_MINE)
    }

    companion object {
        const val EXTRA_TAB_ID = "tab_id"
        private const val STATE_TAB_ID = "state_tab_id"
        private const val TAG_HALL = "hall"
        private const val TAG_TASKS = "tasks"
        private const val TAG_DEVICE = "device"
        private const val TAG_WALLET = "wallet"
        private const val TAG_MINE = "mine"

        /** 登录 / 升级：清掉旧任务栈，新建 MainActivity。 */
        fun freshStart(context: Context, tabId: Int = R.id.nav_hall): Intent {
            MainTabCoordinator.clear()
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TAB_ID, tabId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        fun intentForTab(context: Context, tabId: Int): Intent {
            MainTabCoordinator.request(tabId)
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TAB_ID, tabId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}
