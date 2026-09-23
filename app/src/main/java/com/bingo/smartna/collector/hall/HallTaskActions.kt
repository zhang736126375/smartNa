package com.bingo.smartna.collector.hall

import android.content.Context
import com.bingo.smartna.MainActivity
import com.bingo.smartna.R
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.capture.CaptureActivity
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.UserTask

object HallTaskActions {

    fun tryClaim(context: Context, viewModel: CollectorViewModel, task: Task): ClaimResult {
        if (!Prefs(context).hasConnectedDevice) {
            return ClaimResult.NeedDevice
        }
        viewModel.claim(task)
        return ClaimResult.Success
    }

    fun openDeviceTab(context: Context) {
        context.startActivity(MainActivity.intentForTab(context, R.id.nav_device))
    }

    fun openCapture(context: Context, task: Task) {
        CaptureActivity.start(context, task, demoMode = true)
    }

    fun openCapture(context: Context, userTask: UserTask) {
        CaptureActivity.start(context, userTask.task, demoMode = true)
    }
}

enum class ClaimResult {
    Success,
    NeedDevice
}
