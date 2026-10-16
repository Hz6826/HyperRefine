package cn.ianzb.hyperrefine.hook.packageinstaller

import java.lang.reflect.Array as JavaArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolve only descriptors produced by the semantic discovery session. */
internal object InstallerReflection {
    fun type(name: String, loader: ClassLoader?): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        else -> if (name.endsWith("[]")) JavaArray.newInstance(type(name.dropLast(2), loader), 0).javaClass
            else Class.forName(name, false, loader)
    }

    fun method(data: InstallerMethod, loader: ClassLoader?): Method {
        val owner = type(data.owner, loader)
        val result = type(data.returnType, loader)
        val parameters = data.parameterTypes.map { type(it, loader) }.toTypedArray()
        val matches = owner.declaredMethods.filter {
            it.name == data.name && it.returnType == result && Modifier.isStatic(it.modifiers) == data.isStatic &&
                it.parameterTypes.contentEquals(parameters)
        }
        return uniqueInstallerMatch("reflection ${data.descriptor}", matches).apply { isAccessible = true }
    }
}
