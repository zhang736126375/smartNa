package com.bingo.smartna.collector.login

import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.bingo.smartna.MainActivity
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.databinding.ActivityLoginBinding
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ClickUtils

class LoginActivity : BaseActivity<ActivityLoginBinding, LoginViewModel>() {

    private var agreed = false

    override fun inflateBinding() = ActivityLoginBinding.inflate(layoutInflater)

    override fun initData() {
        if (viewModel.alreadyLoggedIn) {
            openMain()
            return
        }
        applySloganGradient(binding.tvSlogan1)
        applySloganGradient(binding.tvSlogan2)
        bindAgreeText()
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
        ClickUtils.applySingleDebouncing(binding.btnLogin) {
            viewModel.login(phone())
            openMain()
        }
        ClickUtils.applySingleDebouncing(binding.tvEnterprise) {
            Toast.makeText(this, R.string.login_enterprise_toast, Toast.LENGTH_SHORT).show()
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

    private fun openMain() {
        ActivityUtils.startActivity(MainActivity::class.java)
        finish()
    }

    private fun phone() = binding.etPhone.text?.toString().orEmpty()
    private fun code() = binding.etCode.text?.toString().orEmpty()

    private fun refreshCodeButton() {
        val counting = (viewModel.countdown.value ?: 0) > 0
        val enabled = !counting && phone().length == 11
        binding.tvGetCode.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.blue_text else R.color.text_gray)
        )
    }

    private fun refreshLoginEnabled() {
        val enabled = phone().length == 11 && code().length == 4 && agreed
        binding.btnLogin.isEnabled = enabled
        binding.btnLogin.setBackgroundResource(
            if (enabled) R.drawable.bg_btn_primary else R.drawable.bg_btn_disabled
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
        val blue = ContextCompat.getColor(this, R.color.blue_text)
        val user = getString(R.string.login_agree_user)
        val privacy = getString(R.string.login_agree_privacy)
        val userStart = full.indexOf(user)
        span.setSpan(ForegroundColorSpan(blue), userStart, userStart + user.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val privacyStart = full.indexOf(privacy)
        span.setSpan(
            ForegroundColorSpan(blue),
            privacyStart,
            privacyStart + privacy.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvAgree.text = span
    }

    private fun applySloganGradient(view: TextView) {
        view.post {
            val width = view.width.takeIf { it > 0 } ?: return@post
            view.paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                view.textSize,
                intArrayOf(
                    ContextCompat.getColor(this, R.color.blue_light),
                    ContextCompat.getColor(this, R.color.blue_deep)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            view.invalidate()
        }
    }
}
