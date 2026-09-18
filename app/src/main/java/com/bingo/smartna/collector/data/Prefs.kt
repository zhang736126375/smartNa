package com.bingo.smartna.collector.data

import android.content.Context

/** 登录态持久化：v1 只保存手机号。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val phone: String?
        get() = sp.getString(KEY_PHONE, null)

    val isLoggedIn: Boolean
        get() = !phone.isNullOrBlank()

    fun saveLogin(phone: String) {
        sp.edit().putString(KEY_PHONE, phone).apply()
    }

    fun clearLogin() {
        sp.edit().remove(KEY_PHONE).apply()
    }

    private companion object {
        const val PREFS_NAME = "smartna_collector_prefs"
        const val KEY_PHONE = "phone"
    }
}
