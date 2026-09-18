package com.bingo.smartna.base.http

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import org.json.JSONException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object ApiExceptionMapper {

    const val UNKNOWN = 1000
    const val PARSE_ERROR = 1001
    const val NETWORK_ERROR = 1002
    const val SSL_ERROR = 1005
    const val TIMEOUT_ERROR = 1006

    fun map(throwable: Throwable): ApiException {
        if (throwable is ApiException) return throwable
        return when (throwable) {
            is HttpException -> httpException(throwable)
            is JsonParseException,
            is MalformedJsonException,
            is JSONException -> ApiException("解析错误", PARSE_ERROR, throwable)
            is ConnectException -> ApiException("连接失败", NETWORK_ERROR, throwable)
            is SocketTimeoutException -> ApiException("连接超时", TIMEOUT_ERROR, throwable)
            is UnknownHostException -> ApiException("主机地址未知", TIMEOUT_ERROR, throwable)
            is SSLException -> ApiException("证书验证失败", SSL_ERROR, throwable)
            is IOException -> ApiException("网络异常", NETWORK_ERROR, throwable)
            else -> ApiException(throwable.message ?: "未知错误", UNKNOWN, throwable)
        }
    }

    private fun httpException(e: HttpException): ApiException {
        val message = when (e.code()) {
            401 -> "操作未授权"
            403 -> "请求被拒绝"
            404 -> "资源不存在"
            408 -> "服务器执行超时"
            500 -> "服务器内部错误"
            503 -> "服务器不可用"
            else -> "网络错误"
        }
        return ApiException(message, e.code(), e)
    }
}
