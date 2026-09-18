package com.bingo.smartna.base.http

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.reflect.ParameterizedType

/**
 * 解析 [BaseResponse]：
 * - 正式接口：`{ code, message, result }`
 * - 裸 data JSON（如 jsonplaceholder）：整段作为 result，code = 0
 */
class BaseResponseTypeAdapterFactory : TypeAdapterFactory {

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != BaseResponse::class.java) return null
        val parameterized = type.type as? ParameterizedType ?: return null
        val resultAdapter = gson.getAdapter(TypeToken.get(parameterized.actualTypeArguments[0]))
        val elementAdapter = gson.getAdapter(JsonElement::class.java)

        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<BaseResponse<Any?>>() {
            override fun write(out: JsonWriter, value: BaseResponse<Any?>?) {
                out.nullValue()
            }

            override fun read(reader: JsonReader): BaseResponse<Any?> {
                val element = elementAdapter.read(reader)
                if (!element.isJsonObject) {
                    return BaseResponse(code = 0, result = resultAdapter.fromJsonTree(element))
                }
                val obj = element.asJsonObject
                return if (isWrapped(obj)) {
                    BaseResponse(
                        code = obj.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1,
                        message = obj.get("message")?.takeUnless { it.isJsonNull }?.asString,
                        result = obj.get("result")?.takeUnless { it.isJsonNull }?.let {
                            resultAdapter.fromJsonTree(it)
                        }
                    )
                } else {
                    BaseResponse(code = 0, result = resultAdapter.fromJsonTree(element))
                }
            }

            private fun isWrapped(obj: JsonObject): Boolean {
                return obj.has("code") && (obj.has("result") || obj.has("message"))
            }
        }.nullSafe() as TypeAdapter<T>
    }
}
