package com.bingo.smartna.collector.hall

import com.bingo.smartna.collector.data.model.HallCategory

interface HallCategoryNavigator {
    fun openCategory(category: HallCategory)
    fun closeCategory()
}
