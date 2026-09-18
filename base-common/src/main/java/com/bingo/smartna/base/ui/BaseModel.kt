package com.bingo.smartna.base.ui

import com.bingo.smartna.base.http.ApiException
import com.bingo.smartna.base.http.ApiExceptionMapper
import com.bingo.smartna.base.http.ApiResult
import com.bingo.smartna.base.http.BaseResponse
import com.bingo.smartna.base.http.RetrofitClient

/**
 * Model 基类：请求统一走 [BaseResponse] 包装，再转为 [ApiResult]。
 */
open class BaseModel {

    protected inline fun <reified T> api(): T = RetrofitClient.create()

    protected suspend fun <T> requestApi(
        block: suspend () -> BaseResponse<T>
    ): ApiResult<T> {
        return try {
            val resp = block()
            when {
                !resp.isOk() -> failure(resp.message ?: "业务失败", resp.code)
                resp.result == null -> failure("数据为空", resp.code)
                else -> ApiResult.OnSuccess(resp.result)
            }
        } catch (e: Exception) {
            ApiResult.OnFailure(ApiExceptionMapper.map(e))
        }
    }

    private fun <T> failure(message: String, code: Int): ApiResult<T> {
        return ApiResult.OnFailure(ApiException(message, code))
    }
}
