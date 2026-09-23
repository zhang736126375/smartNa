package com.bingo.smartna.collector.hall

import android.app.Dialog
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bingo.smartna.R
import com.bingo.smartna.collector.DemoCaptureConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object TeachVideoDialog {

    fun show(activity: AppCompatActivity) {
        val dialog = Dialog(activity, R.style.Theme_SmartNa)
        dialog.setContentView(R.layout.dialog_teach_video)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        val tvCounter = dialog.findViewById<TextView>(R.id.tvTeachCounter)
        val tvStatus = dialog.findViewById<TextView>(R.id.tvTeachStatus)
        dialog.findViewById<TextView>(R.id.btnTeachClose).setOnClickListener { dialog.dismiss() }
        dialog.show()

        activity.lifecycleScope.launch {
            tvStatus.setText(R.string.teach_video_playing)
            for (number in 1..DemoCaptureConfig.STEP_COUNT) {
                tvCounter.text = number.toString()
                delay(DemoCaptureConfig.STEP_MS)
            }
            tvStatus.setText(R.string.teach_video_done)
        }
    }
}
