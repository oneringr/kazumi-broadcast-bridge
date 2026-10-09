package io.github.kazumibridge

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.kazumibridge.hook.ForegroundLauncher
import io.github.kazumibridge.runtime.BridgeState
import io.github.kazumibridge.runtime.Settings
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.initialize(this)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                FrameworkConnection.onServiceBind(service)
            }
            override fun onServiceDied(service: XposedService) {
                FrameworkConnection.onServiceDied(service)
            }
        })
    }
}

object FrameworkConnection {
    @Volatile var service: XposedService? = null
    @Volatile var preferences: SharedPreferences? = null
    @Volatile var port = 0
    @Volatile var token = ""
    @Volatile var active = false
    private var foregroundRequest = ""
    private var foregroundRequestedAt = 0L
    private val statusLock = Any()
    private val refreshPending = AtomicBoolean(false)
    private val statusWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "KazumiBridge-framework").apply { isDaemon = true } }

    fun onServiceBind(bound: XposedService) {
        synchronized(statusLock) {
            service = bound
            preferences = null
            BridgeState.update { it.copy(framework = "正在读取框架状态", frameworkApi = 0, scopeKazumi = null, runningKazumi = null) }
        }
        statusWorker.execute {
            runCatching {
                val remote = bound.getRemotePreferences("bridge")
                val current = synchronized(statusLock) {
                    if (service !== bound) false else { preferences = remote; true }
                }
                if (current) publish()
            }.onFailure { error ->
                synchronized(statusLock) {
                    if (service === bound) BridgeState.error("无法连接 LSPosed 配置服务：${error.javaClass.simpleName}")
                }
            }
            readFrameworkStatus(bound)
        }
    }

    fun onServiceDied(dead: XposedService) {
        synchronized(statusLock) {
            if (service !== dead) return
            service = null
            preferences = null
            BridgeState.update { it.copy(framework = "框架连接已断开", frameworkApi = 0, scopeKazumi = null, runningKazumi = null) }
        }
    }

    /** Binder queries can block; refresh on page resume without running them on the UI thread. */
    fun refreshFrameworkStatus() {
        // A slow Binder must not accumulate a refresh task for every UI sampling tick.
        if (!refreshPending.compareAndSet(false, true)) return
        try {
            statusWorker.execute {
                try { service?.let(::readFrameworkStatus) }
                finally { refreshPending.set(false) }
            }
        } catch (failure: RuntimeException) {
            refreshPending.set(false)
            throw failure
        }
    }

    private fun readFrameworkStatus(bound: XposedService) {
        if (service !== bound) return
        val frameworkInfo = runCatching {
            val api = bound.apiVersion
            val name = bound.frameworkName
            val version = bound.frameworkVersion
            val code = bound.frameworkVersionCode
            "${name} ${version}${if (code > 0) " ($code)" else ""}" to api
        }.getOrElse {
            synchronized(statusLock) {
                if (service === bound) BridgeState.update { it.copy(framework = "框架信息暂不可用", frameworkApi = 0, scopeKazumi = null, runningKazumi = null) }
            }
            return
        }
        val scope = runCatching { bound.scope.contains(KAZUMI_PACKAGE) }.getOrNull()
        val running = if (frameworkInfo.second >= XposedService.API_102) runCatching {
            // The hook installs only in Kazumi's main process; child processes do not prove attachment.
            bound.runningTargets.any { target -> target.processName == KAZUMI_PACKAGE }
        }.getOrNull() else null
        synchronized(statusLock) {
            // A previous Binder query must never restore stale state after disconnection or rebind.
            if (service === bound) BridgeState.update {
                it.copy(framework = frameworkInfo.first, frameworkApi = frameworkInfo.second, scopeKazumi = scope, runningKazumi = running)
            }
        }
    }

    private const val KAZUMI_PACKAGE = "com.predidit.kazumi"

    @Synchronized fun requestForeground(): Boolean {
        if (!active || preferences == null) return false
        foregroundRequest = java.util.UUID.randomUUID().toString()
        foregroundRequestedAt = SystemClock.elapsedRealtime()
        return publish()
    }

    @Synchronized fun clearForegroundRequest(): Boolean {
        foregroundRequest = ""
        foregroundRequestedAt = 0L
        return publish()
    }

    @Synchronized fun publish(): Boolean {
        if (!active) { foregroundRequest = ""; foregroundRequestedAt = 0L }
        val written = preferences?.edit()?.putBoolean("enabled", active)?.putInt("port", port)?.putString("token", token)
            ?.putString(ForegroundLauncher.REQUEST_KEY, foregroundRequest)?.putLong(ForegroundLauncher.TIME_KEY, foregroundRequestedAt)?.commit()
        if (written == false) BridgeState.error("配置未能传入 Kazumi，请重新打开模块后重启 Kazumi。")
        return written == true
    }
}
