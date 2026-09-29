package com.bingo.smartna.collector.hall

import android.content.Context
import android.content.Intent
import com.bingo.smartna.MainActivity
import com.bingo.smartna.collector.CollectorViewModel
import com.bingo.smartna.collector.capture.CaptureActivity
import com.bingo.smartna.collector.claim.ClaimSuccessActivity
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.UserTask
import com.bingo.smartna.collector.device.CameraDebugActivity
import com.bingo.smartna.collector.device.DevicePageActivity

object HallTaskActions {

    fun claimAndGuide(context: Context, viewModel: CollectorViewModel, task: Task) {
        if (viewModel.ui.value?.claimedIds?.contains(task.id) == true) {
            openNextStep(context, task)
            return
        }
        viewModel.claim(task)
        ClaimSuccessActivity.start(context, task.id)
    }

    fun openNextStep(context: Context, task: Task) {
        if (Prefs(context).hasConnectedDevice) {
            CameraDebugActivity.start(context, task)
        } else {
            DevicePageActivity.start(context, task.id)
        }
    }

    fun openDevicePage(context: Context, taskId: String? = null) {
        DevicePageActivity.start(context, taskId)
    }

    fun openCapture(context: Context, task: Task) {
        if (Prefs(context).hasConnectedDevice) {
            CameraDebugActivity.start(context, task)
        } else {
            DevicePageActivity.start(context, task.id)
        }
    }

    fun openCapture(context: Context, userTask: UserTask) {
        openCapture(context, userTask.task)
    }

    fun openTasksTab(context: Context) {
        context.startActivity(MainActivity.intentForTab(context, com.bingo.smartna.R.id.nav_tasks))
    }
}
