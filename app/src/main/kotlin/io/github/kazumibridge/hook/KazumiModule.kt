package io.github.kazumibridge.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KazumiModule : XposedModule() {
    private val installed = AtomicBoolean(false)
    private var targetProcess = false
    private var prefs: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var foreground: ForegroundLauncher? = null
    @Volatile private var lastDiagnostic = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        targetProcess = !param.isSystemServer && param.processName == "com.predidit.kazumi"
        if (!targetProcess) detach()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!targetProcess || param.packageName != "com.predidit.kazumi") return
        val applicationAttach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(applicationAttach).setId("kazumi.bridge.attach").intercept { chain ->
            val result = chain.proceed()
            if (installed.compareAndSet(false, true)) initialize(chain.thisObject as Application)
            result
        }
        log(Log.INFO, "KazumiBridge", "API $apiVersion module ready for Kazumi")
    }

    private fun initialize(application: Application) {
        try {
            NativeBridge.load(moduleApplicationInfo.sourceDir, moduleApplicationInfo.nativeLibraryDir)
            val status = NativeBridge.install()
            log(if (status == 0) Log.INFO else Log.ERROR, "KazumiBridge", "Native connect hook ${if (status == 0) "installed" else "failed: $status"}; ${NativeBridge.diagnostic()}")
            prefs = getRemotePreferences("bridge")
            foreground = ForegroundLauncher(application, prefs!!)
            listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> synchronize(status) }
            prefs!!.registerOnSharedPreferenceChangeListener(listener)
            synchronize(status)
            val heartbeat = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "KazumiBridge-status").apply { isDaemon = true } }
            heartbeat.scheduleWithFixedDelay({ synchronize(status) }, 0, 3, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            log(Log.ERROR, "KazumiBridge", "Bridge initialization failed", error)
        }
    }

    private fun synchronize(status: Int) {
        runCatching {
            foreground?.refresh()
            val diagnostic = NativeBridge.diagnostic()
            if (diagnostic != lastDiagnostic) {
                lastDiagnostic = diagnostic
                Log.i("KazumiBridgeDiagnostics", diagnostic)
            }
            val preferences = prefs ?: return
            val port = preferences.getInt("port", 0)
            val token = preferences.getString("token", "") ?: ""
            val enabled = preferences.getBoolean("enabled", false) && status == 0
            val bytes = if (token.length == 64) token.chunked(2).map { it.toInt(16).toByte() }.toByteArray() else byteArrayOf()
            NativeBridge.configure(port, bytes, enabled)
            if (port == 0 || token.length != 64 || !preferences.getBoolean("enabled", false)) return
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 700)
                socket.soTimeout = 700
                val body = "{\"nativeStatus\":$status,\"redirects\":${NativeBridge.redirectCount()}}".toByteArray(Charsets.UTF_8)
                val header = "POST /hook/$token HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().write(body)
                socket.getOutputStream().flush()
                socket.getInputStream().read()
            }
        }
    }

    // Native libraries and the status worker remain owned by this generation until Kazumi exits.
    override fun onHotReloading(param: HotReloadingParam): Boolean = false
}
