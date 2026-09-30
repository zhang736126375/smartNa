package com.bingo.smartna.collector.device

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.device.ego.EgoNetDeviceFoundActivity
import com.bingo.smartna.databinding.ActivityScanDeviceBinding
import com.blankj.utilcode.util.ClickUtils
import com.huawei.hms.hmsscankit.RemoteView
import com.huawei.hms.ml.scan.HmsScan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScanDeviceActivity : BaseActivity<ActivityScanDeviceBinding, BaseViewModel>() {

    private lateinit var kit: DeviceKit
    private var remoteView: RemoteView? = null
    private var handled = false
    private var createState: Bundle? = null
    private var demoScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        createState = savedInstanceState
        super.onCreate(savedInstanceState)
    }

    override fun inflateBinding() = ActivityScanDeviceBinding.inflate(layoutInflater)

    override fun initParam() {
        kit = DeviceKit.fromId(intent.getStringExtra(EXTRA_KIT_ID)) ?: DeviceKit.EGO
    }

    override fun initData() {
        binding.tvScanHint.text = if (returnQr) {
            getString(R.string.device_scan_qr_name_hint)
        } else {
            getString(R.string.device_scan_hint, getString(kit.scanPartRes))
        }
        binding.ivGuide.setImageResource(
            if (kit == DeviceKit.GRIPPER) R.drawable.ic_kit_gripper else R.drawable.ic_kit_ego
        )
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        if (DEMO_MOCK_SCAN && !returnQr) {
            setupDemoScan()
        } else {
            binding.demoScanPanel.visibility = View.GONE
            binding.scanFrame.visibility = View.VISIBLE
            binding.remoteContainer.post { attachRemoteView() }
        }
    }

    private fun setupDemoScan() {
        binding.demoScanPanel.visibility = View.VISIBLE
        binding.scanFrame.visibility = View.GONE
        binding.tvDemoHint.text = getString(R.string.device_scan_demo_hint)
        if (demoScheduled) return
        demoScheduled = true
        lifecycleScope.launch {
            delay(DEMO_DISCOVER_MS)
            if (!isFinishing && !handled) {
                showDeviceFoundDialog()
            }
        }
    }

    private fun showDeviceFoundDialog() {
        DeviceFoundDialog().apply {
            this.kit = this@ScanDeviceActivity.kit
            onConnect = { onScanSuccess(DEMO_QR_VALUE) }
        }.show(supportFragmentManager, DeviceFoundDialog.TAG)
    }

    private fun attachRemoteView() {
        if (isFinishing || remoteView != null) return
        val box = scanBoxOnScreen()
        val view = RemoteView.Builder()
            .setContext(this)
            .setBoundingBox(box)
            .setFormat(HmsScan.QRCODE_SCAN_TYPE)
            .build()
        view.onCreate(createState)
        view.setOnResultCallback { results ->
            val value = results?.firstOrNull()?.originalValue.orEmpty()
            if (value.isNotBlank()) onScanSuccess(value)
        }
        binding.remoteContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        remoteView = view
        view.onStart()
        view.onResume()
    }

    private fun scanBoxOnScreen(): Rect {
        val frame = binding.scanFrame
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
    }

    private fun onScanSuccess(value: String) {
        if (handled) return
        handled = true
        if (returnQr) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_QR_VALUE, value))
            finish()
            return
        }
        startActivity(Intent(this, EgoNetDeviceFoundActivity::class.java))
        finish()
    }

    override fun onStart() {
        super.onStart()
        remoteView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        remoteView?.onResume()
    }

    override fun onPause() {
        remoteView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        remoteView?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        remoteView?.onDestroy()
        remoteView = null
        super.onDestroy()
    }

    private val returnQr: Boolean
        get() = intent.getBooleanExtra(EXTRA_RETURN_QR, false)

    companion object {
        const val EXTRA_KIT_ID = "kit_id"
        const val EXTRA_RETURN_QR = "return_qr"
        const val EXTRA_QR_VALUE = "qr_value"
        private const val DEMO_MOCK_SCAN = false
        private const val DEMO_DISCOVER_MS = 3000L
        private const val DEMO_QR_VALUE = "demo-qr"
    }
}
