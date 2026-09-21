package com.bingo.smartna

import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.HallNavigator
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.collector.device.DeviceFragment
import com.bingo.smartna.collector.hall.HallFragment
import com.bingo.smartna.collector.lead.TeamTasksFragment
import com.bingo.smartna.collector.mine.MineFragment
import com.bingo.smartna.collector.tasks.MyTasksFragment
import com.bingo.smartna.collector.wallet.WalletFragment
import com.bingo.smartna.databinding.ActivityMainBinding

class MainActivity : BaseActivity<ActivityMainBinding, CollectorViewModel>(), HallNavigator {

    private lateinit var role: UserRole

    override fun onCreate(savedInstanceState: Bundle?) {
        role = Prefs(this).role
        if (role == UserRole.STAFF) {
            setTheme(R.style.Theme_SmartNa_Staff)
        }
        super.onCreate(savedInstanceState)
    }

    override fun inflateBinding() = ActivityMainBinding.inflate(layoutInflater)

    override fun initData() {
        if (role == UserRole.LEAD) {
            binding.bottomNav.menu.clear()
            binding.bottomNav.inflateMenu(R.menu.menu_lead_tabs)
        }
        if (role == UserRole.STAFF) {
            val tint = ContextCompat.getColorStateList(this, R.color.selector_tab_color)
            binding.bottomNav.itemIconTintList = tint
            binding.bottomNav.itemTextColor = tint
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        val visibleTag = allTags().firstOrNull { tag ->
            val fragment = supportFragmentManager.findFragmentByTag(tag)
            fragment != null && !fragment.isHidden
        }
        if (visibleTag == null) {
            showTab(defaultTabId())
            binding.bottomNav.selectedItemId = defaultTabId()
        } else {
            binding.bottomNav.selectedItemId = itemIdOf(visibleTag)
        }
    }

    override fun openHall() {
        if (role == UserRole.LEAD) return
        binding.bottomNav.selectedItemId = R.id.nav_hall
        (supportFragmentManager.findFragmentByTag(TAG_HALL) as? HallFragment)?.resetToHome()
    }

    private fun defaultTabId(): Int = if (role == UserRole.LEAD) R.id.nav_team_tasks else R.id.nav_hall

    private fun showTab(itemId: Int) {
        val transaction = supportFragmentManager.beginTransaction()
        hideAll(transaction)
        val tag = tagOf(itemId)
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing == null) {
            transaction.add(binding.tabContainer.id, createFragment(itemId), tag)
        } else {
            transaction.show(existing)
        }
        transaction.commit()
    }

    private fun hideAll(transaction: androidx.fragment.app.FragmentTransaction) {
        allTags().forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.let { transaction.hide(it) }
        }
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_team_tasks -> TeamTasksFragment()
        R.id.nav_tasks -> MyTasksFragment()
        R.id.nav_device -> DeviceFragment()
        R.id.nav_wallet -> WalletFragment()
        R.id.nav_mine -> MineFragment()
        else -> HallFragment()
    }

    private fun tagOf(itemId: Int) = when (itemId) {
        R.id.nav_team_tasks -> TAG_TEAM
        R.id.nav_tasks -> TAG_TASKS
        R.id.nav_device -> TAG_DEVICE
        R.id.nav_wallet -> TAG_WALLET
        R.id.nav_mine -> TAG_MINE
        else -> TAG_HALL
    }

    private fun itemIdOf(tag: String) = when (tag) {
        TAG_TEAM -> R.id.nav_team_tasks
        TAG_TASKS -> R.id.nav_tasks
        TAG_DEVICE -> R.id.nav_device
        TAG_WALLET -> R.id.nav_wallet
        TAG_MINE -> R.id.nav_mine
        else -> R.id.nav_hall
    }

    private fun allTags() = if (role == UserRole.LEAD) {
        listOf(TAG_TEAM, TAG_DEVICE, TAG_MINE)
    } else {
        listOf(TAG_HALL, TAG_TASKS, TAG_DEVICE, TAG_WALLET, TAG_MINE)
    }

    private companion object {
        const val TAG_HALL = "hall"
        const val TAG_TASKS = "tasks"
        const val TAG_TEAM = "team"
        const val TAG_DEVICE = "device"
        const val TAG_WALLET = "wallet"
        const val TAG_MINE = "mine"
    }
}
