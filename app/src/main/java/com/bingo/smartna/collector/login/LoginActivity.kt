package com.bingo.smartna.collector.login

import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.bingo.smartna.MainActivity
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.databinding.ActivityLoginBinding
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ClickUtils

class LoginActivity : BaseActivity<ActivityLoginBinding, LoginViewModel>() {

    private var agreed = false
    private var enterpriseMode = false
    private var enterpriseRole = UserRole.STAFF

    override fun inflateBinding() = ActivityLoginBinding.inflate(layoutInflater)

    override fun initData() {
        if (viewModel.alreadyLoggedIn) {
            openMain()
            return
        }
        applySloganGradient()
        bindAgreeText()
        renderMode()
        refreshLoginEnabled()
    }

    override fun initViewObservable() {
        binding.etPhone.doAfterTextChanged { editable ->
            val filtered = editable?.toString()?.filter(Char::isDigit)?.take(11).orEmpty()
            if (filtered != editable?.toString()) {
                binding.etPhone.setText(filtered)
                binding.etPhone.setSelection(filtered.length)
                return@doAfterTextChanged
            }
            refreshCodeButton()
            refreshLoginEnabled()
        }
        binding.etCode.doAfterTextChanged { editable ->
            val filtered = editable?.toString()?.filter(Char::isDigit)?.take(4).orEmpty()
            if (filtered != editable?.toString()) {
                binding.etCode.setText(filtered)
                binding.etCode.setSelection(filtered.length)
                return@doAfterTextChanged
            }
            refreshLoginEnabled()
        }
        binding.etUsername.doAfterTextChanged { refreshLoginEnabled() }
        binding.etPassword.doAfterTextChanged { refreshLoginEnabled() }

        ClickUtils.applySingleDebouncing(binding.tvGetCode) {
            val phone = phone()
            if (viewModel.sendCode(phone)) {
                Toast.makeText(this, R.string.login_code_sent, Toast.LENGTH_SHORT).show()
            }
        }
        ClickUtils.applySingleDebouncing(binding.rowAgree) {
            agreed = !agreed
            binding.ivAgree.background = ContextCompat.getDrawable(
                this,
                if (agreed) R.drawable.bg_agree_checked else R.drawable.bg_agree_unchecked
            )
            binding.ivAgree.text = if (agreed) "✓" else ""
            refreshLoginEnabled()
        }
        ClickUtils.applySingleDebouncing(binding.btnLogin) { submit() }
        ClickUtils.applySingleDebouncing(binding.tvSwitchMode) {
            enterpriseMode = !enterpriseMode
            renderMode()
            refreshLoginEnabled()
        }
        ClickUtils.applySingleDebouncing(binding.tabStaff) {
            selectEnterpriseRole(UserRole.STAFF)
        }
        ClickUtils.applySingleDebouncing(binding.tabLead) {
            selectEnterpriseRole(UserRole.LEAD)
        }

        viewModel.countdown.observe(this) { seconds ->
            if (seconds > 0) {
                binding.tvGetCode.text = getString(R.string.login_code_countdown, seconds)
                binding.tvGetCode.setTextColor(ContextCompat.getColor(this, R.color.text_gray))
            } else {
                binding.tvGetCode.text = getString(R.string.login_get_code)
                refreshCodeButton()
            }
        }
    }

    private fun selectEnterpriseRole(role: UserRole) {
        if (enterpriseRole == role) return
        enterpriseRole = role
        renderEnterpriseRoleTabs()
        refreshLoginEnabled()
    }

    private fun submit() {
        if (enterpriseMode) {
            when (viewModel.loginByAccount(username(), password(), enterpriseRole)) {
                is AccountLoginResult.Success -> openMain()
                AccountLoginResult.AccountNotFound ->
                    Toast.makeText(this, R.string.login_account_not_found, Toast.LENGTH_SHORT).show()
                AccountLoginResult.PasswordTooShort ->
                    Toast.makeText(this, R.string.login_password_short, Toast.LENGTH_SHORT).show()
            }
        } else {
            viewModel.loginByPhone(phone())
            openMain()
        }
    }

    private fun renderMode() {
        binding.phonePanel.visibility = if (enterpriseMode) View.GONE else View.VISIBLE
        binding.accountPanel.visibility = if (enterpriseMode) View.VISIBLE else View.GONE
        binding.tvLoginSubtitle.visibility = if (enterpriseMode) View.GONE else View.VISIBLE
        binding.tvLoginTitle.setText(
            if (enterpriseMode) R.string.login_enterprise_title else R.string.login_title
        )
        binding.btnLogin.setText(
            if (enterpriseMode) R.string.login_account_submit else R.string.login_submit
        )
        binding.tvSwitchMode.setText(
            if (enterpriseMode) R.string.login_personal_switch else R.string.login_enterprise
        )
        if (enterpriseMode) {
            renderEnterpriseRoleTabs()
        }
    }

    private fun renderEnterpriseRoleTabs() {
        bindRoleTab(binding.tabStaff, enterpriseRole == UserRole.STAFF)
        bindRoleTab(binding.tabLead, enterpriseRole == UserRole.LEAD)
        binding.etUsername.setHint(
            if (enterpriseRole == UserRole.STAFF) {
                R.string.login_account_hint_staff
            } else {
                R.string.login_account_hint_lead
            }
        )
    }

    private fun bindRoleTab(tab: TextView, selected: Boolean) {
        tab.setBackgroundResource(
            if (selected) R.drawable.bg_login_mode_selected else android.R.color.transparent
        )
        tab.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.card_white else R.color.text_dark
            )
        )
        tab.paint.isFakeBoldText = selected
    }

    private fun openMain() {
        startActivity(MainActivity.freshStart(this))
        finish()
    }

    private fun phone() = binding.etPhone.text?.toString().orEmpty()
    private fun code() = binding.etCode.text?.toString().orEmpty()
    private fun username() = binding.etUsername.text?.toString().orEmpty()
    private fun password() = binding.etPassword.text?.toString().orEmpty()

    private fun refreshCodeButton() {
        val counting = (viewModel.countdown.value ?: 0) > 0
        val enabled = !counting && phone().length == 11
        binding.tvGetCode.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.brand_orange else R.color.text_gray)
        )
    }

    private fun refreshLoginEnabled() {
        val enabled = agreed && if (enterpriseMode) {
            username().isNotBlank() && password().length >= 4
        } else {
            phone().length == 11 && code().length == 4
        }
        binding.btnLogin.isEnabled = enabled
        binding.btnLogin.setBackgroundResource(
            if (enabled) R.drawable.bg_btn_login else R.drawable.bg_btn_disabled
        )
        binding.btnLogin.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.card_white else R.color.btn_disabled_text
            )
        )
    }

    private fun bindAgreeText() {
        val full = getString(R.string.login_agree_prefix) +
            getString(R.string.login_agree_user) +
            getString(R.string.login_agree_and) +
            getString(R.string.login_agree_privacy)
        val span = SpannableString(full)
        val orange = ContextCompat.getColor(this, R.color.brand_orange)
        val user = getString(R.string.login_agree_user)
        val privacy = getString(R.string.login_agree_privacy)
        val userStart = full.indexOf(user)
        span.setSpan(ForegroundColorSpan(orange), userStart, userStart + user.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val privacyStart = full.indexOf(privacy)
        span.setSpan(
            ForegroundColorSpan(orange),
            privacyStart,
            privacyStart + privacy.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvAgree.text = span
    }

    private fun applySloganGradient() {
        val view = binding.tvSlogan
        view.post {
            if (view.width <= 0 || view.height <= 0) return@post
            view.paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                view.height.toFloat(),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.brand_orange_light),
                    ContextCompat.getColor(this, R.color.brand_orange)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            view.invalidate()
        }
    }
}
