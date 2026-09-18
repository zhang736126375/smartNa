package com.bingo.smartna.base.ui

import java.lang.reflect.ParameterizedType

/**
 * 从类声明的第二个泛型参数解析 ViewModel 类型。
 * 未声明时退回 [BaseViewModel]
 */
@Suppress("UNCHECKED_CAST")
fun <VM : BaseViewModel> resolveViewModelClass(instance: Any): Class<VM> {
    var clazz: Class<*>? = instance.javaClass
    while (clazz != null && clazz != Any::class.java) {
        val type = clazz.genericSuperclass
        if (type is ParameterizedType) {
            val vmType = type.actualTypeArguments.getOrNull(1)
            if (vmType is Class<*>) {
                return vmType as Class<VM>
            }
        }
        clazz = clazz.superclass
    }
    return BaseViewModel::class.java as Class<VM>
}
