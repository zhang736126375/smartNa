package com.bingo.smartna.collector.login

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.UserRole
import kotlinx.coroutines.delay

sealed class AccountLoginResult {
    data class Success(val role: UserRole) : AccountLoginResult()
    object AccountNotFound : AccountLoginResult()
    object PasswordTooShort : AccountLoginResult()
}

class LoginViewModel(application: Application) : BaseViewModel(application) {

    private val prefs = Prefs(application)

    val alreadyLoggedIn: Boolean = prefs.isLoggedIn

    private val _countdown = MutableLiveData(0)
    val countdown: LiveData<Int> = _countdown

    fun sendCode(phone: String): Boolean {
        if (phone.length != 11) return false
        if ((_countdown.value ?: 0) > 0) return false
        _countdown.value = 60
        launch {
            while ((_countdown.value ?: 0) > 0) {
                delay(1000)
                _countdown.value = (_countdown.value ?: 1) - 1
            }
        }
        return true
    }

    fun loginByPhone(phone: String) {
        prefs.savePhoneLogin(phone)
    }

    fun loginByAccount(username: String, password: String, role: UserRole): AccountLoginResult {
        if (username.isBlank()) return AccountLoginResult.AccountNotFound
        if (password.length < 4) return AccountLoginResult.PasswordTooShort
        if (role != UserRole.STAFF && role != UserRole.LEAD) return AccountLoginResult.AccountNotFound
        prefs.saveAccountLogin(username.trim(), role)
        return AccountLoginResult.Success(role)
    }

    companion object {
        const val ACCOUNT_STAFF = "石家庄1区28号"
        const val ACCOUNT_LEAD = "石家庄1区"
    }
}
