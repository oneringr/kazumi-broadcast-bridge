package io.github.kazumibridge.hook

import dalvik.system.PathClassLoader

interface NativeBackend {
    fun install(): Int
    fun configure(port: Int, token: ByteArray, active: Boolean)
    fun redirectCount(): Long
    fun diagnostic(): String
}

object NativeBridge {
    private lateinit var backend: NativeBackend

    fun load(apk: String, directory: String) {
        val loader = NativeLoader(apk, directory, NativeBridge::class.java.classLoader!!)
        backend = loader.loadClass("io.github.kazumibridge.hook.NativeRuntime")
            .getDeclaredConstructor().newInstance() as NativeBackend
    }

    fun install(): Int = backend.install()
    fun configure(port: Int, token: ByteArray, active: Boolean) = backend.configure(port, token, active)
    fun redirectCount(): Long = backend.redirectCount()
    fun diagnostic(): String = backend.diagnostic()

    // The helper and ShadowHook must belong to this loader so Android creates a native
    // namespace with the module's directory, including libshadowhook_nothing.so.
    private class NativeLoader(apk: String, directory: String, parent: ClassLoader) : PathClassLoader(apk, directory, parent) {
        @Synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "io.github.kazumibridge.hook.NativeRuntime" || name.startsWith("com.bytedance.shadowhook.")) {
                val type = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(type)
                return type
            }
            return super.loadClass(name, resolve)
        }
    }
}
