package com.bingo.smartna.collector.device

import android.app.Application
import com.bingo.smartna.base.ui.BaseViewModel

class DeviceConnectViewModel(application: Application) : BaseViewModel(application) {
    var selectedKit: DeviceKit? = null
}
