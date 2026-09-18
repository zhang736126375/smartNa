package com.bingo.smartna.base.http

/**
 * 通用 API 响应包装，字段可按后端约定调整。
 */
data class BaseResponse<T>(
    val code: Int = 0,
    val message: String? = null,
    val result: T? = null
) {
    fun isOk(): Boolean = code == 0
}
