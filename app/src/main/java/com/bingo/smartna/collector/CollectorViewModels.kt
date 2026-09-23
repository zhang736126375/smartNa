package com.bingo.smartna.collector

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import com.bingo.smartna.SmartNaApplication

/** 全 App 共享的 CollectorViewModel，保证详情页/采集页与 MainActivity 状态一致。 */
object CollectorViewModels {

    fun get(application: Application): CollectorViewModel {
        val app = application as SmartNaApplication
        return ViewModelProvider(
            app,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[CollectorViewModel::class.java]
    }
}
