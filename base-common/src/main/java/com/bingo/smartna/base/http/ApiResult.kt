package com.bingo.smartna.base.http

sealed class ApiResult<out T> {
    data class OnSuccess<T>(val data: T) : ApiResult<T>()
    data class OnFailure(val exception: ApiException) : ApiResult<Nothing>()
}

class ApiException(
    message: String,
    val code: Int = -1,
    cause: Throwable? = null
) : Exception(message, cause)

inline fun <T> ApiResult<T>.onSuccess(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.OnSuccess) block(data)
    return this
}

inline fun <T> ApiResult<T>.onFailure(block: (ApiException) -> Unit): ApiResult<T> {
    if (this is ApiResult.OnFailure) block(exception)
    return this
}
