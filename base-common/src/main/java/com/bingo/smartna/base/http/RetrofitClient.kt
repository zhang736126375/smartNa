package com.bingo.smartna.base.http

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private const val DEFAULT_TIMEOUT_SEC = 15L

    private var retrofit: Retrofit? = null

    fun init(baseUrl: String, debug: Boolean = true) {
        val logging = HttpLoggingInterceptor().apply {
            level = if (debug) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        val gson = GsonBuilder()
            .registerTypeAdapterFactory(BaseResponseTypeAdapterFactory())
            .create()
        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    fun <T> create(service: Class<T>): T {
        val instance = retrofit
            ?: throw IllegalStateException("RetrofitClient 未初始化，请在 Application.onCreate 中调用 init()")
        return instance.create(service)
    }

    inline fun <reified T> create(): T = create(T::class.java)
}
