package com.bingo.smartna.collector.data.mock

import androidx.annotation.DrawableRes
import com.bingo.smartna.R

/**
 * 任务封面。字段形态对齐接口返回的图片 URL；
 * 当前没有图床，Mock 用固定地址，列表再解析到本地 mipmap。
 */
object TaskCover {
    const val GREENHOUSE = "https://cdn.mock.smartna/task/icon_greenhouse.png"
    const val KITCHEN_STORAGE = "https://cdn.mock.smartna/task/icon_kitchen_storage.png"
    const val BEDROOM_STORAGE = "https://cdn.mock.smartna/task/icon_bedroom_storage.png"
    const val SUPERMARKET_STORAGE = "https://cdn.mock.smartna/task/icon_supermarket_storage.png"

    @DrawableRes
    fun localRes(coverUrl: String): Int? = when (coverUrl) {
        GREENHOUSE -> R.mipmap.icon_greenhouse
        KITCHEN_STORAGE -> R.mipmap.icon_kitchen_storage
        BEDROOM_STORAGE -> R.mipmap.icon_bedroom_storage
        SUPERMARKET_STORAGE -> R.mipmap.icon_supermarket_storage
        else -> null
    }

    fun ofScene(scene: String, title: String): String {
        val text = "$scene $title"
        return when {
            text.contains("大棚") || text.contains("温室") -> GREENHOUSE
            text.contains("超市") -> SUPERMARKET_STORAGE
            text.contains("厨房") || text.contains("餐具") -> KITCHEN_STORAGE
            text.contains("卧室") || text.contains("收纳") -> BEDROOM_STORAGE
            else -> ""
        }
    }
}
