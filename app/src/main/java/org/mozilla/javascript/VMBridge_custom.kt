package org.mozilla.javascript

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Custom VMBridge implementation for Android in Kotlin.
 *
 * Mozilla Rhino's [VMBridge.makeInstance] dynamically searches for "org.mozilla.javascript.VMBridge_custom"
 * as its very first candidate before falling back to JDK reflection.
 *
 * Implementing this directly in the project ensures:
 * 1. Rhino ALWAYS finds and instantiates a working VMBridge on Android runtime without dynamic reflection.
 * 2. Complete immunity to R8/ProGuard minification issues across all Android versions (including API 34-37+).
 */
@Suppress("ClassName", "UNCHECKED_CAST")
class VMBridge_custom : VMBridge() {

    companion object {
        private val contextLocal = ThreadLocal<Array<Any?>>()
    }

    override fun getThreadContextHelper(): Any {
        var storage = contextLocal.get()
        if (storage == null) {
            storage = arrayOfNulls(1)
            contextLocal.set(storage)
        }
        return storage
    }

    override fun getContext(contextHelper: Any?): Context? {
        val storage = contextHelper as? Array<*> ?: return null
        return storage.getOrNull(0) as? Context
    }

    override fun setContext(contextHelper: Any?, cx: Context?) {
        val storage = contextHelper as? Array<Any?> ?: return
        if (storage.isNotEmpty()) {
            storage[0] = cx
        }
    }

    override fun tryToMakeAccessible(accessibleObject: AccessibleObject?): Boolean {
        if (accessibleObject == null) return false
        return try {
            if (!accessibleObject.isAccessible) {
                accessibleObject.isAccessible = true
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun getInterfaceProxyHelper(cf: ContextFactory?, interfaces: Array<out Class<*>>): Any {
        val cl = interfaces[0].classLoader
        val proxyClass = Proxy.getProxyClass(cl, *interfaces)
        return try {
            proxyClass.getConstructor(InvocationHandler::class.java)
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException(e)
        }
    }

    override fun newInterfaceProxy(
        proxyHelper: Any?,
        cf: ContextFactory?,
        adapter: InterfaceAdapter?,
        target: Any?,
        topScope: Scriptable?
    ): Any {
        val constructor = proxyHelper as Constructor<*>
        val handler = InvocationHandler { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                val methodName = method.name
                when (methodName) {
                    "equals" -> return@InvocationHandler target === args?.getOrNull(0)
                    "hashCode" -> return@InvocationHandler System.identityHashCode(target)
                    "toString" -> return@InvocationHandler "Proxy[RhinoScriptable]"
                }
            }
            val argsArray = args ?: emptyArray()
            adapter?.invoke(cf, target, topScope, proxy, method, argsArray)
        }
        return try {
            constructor.newInstance(handler)
        } catch (e: InvocationTargetException) {
            throw Context.throwAsScriptRuntimeEx(e.targetException ?: e)
        } catch (e: Throwable) {
            throw IllegalStateException(e)
        }
    }
}
