package com.bingo.smartna.collector.login

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import kotlinx.coroutines.delay

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

    fun login(phone: String) {
        prefs.saveLogin(phone)
    }
}
