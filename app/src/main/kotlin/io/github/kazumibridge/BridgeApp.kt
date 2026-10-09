package io.github.kazumibridge

import android.app.Application
import android.content.SharedPreferences
import io.github.kazumibridge.runtime.BridgeState
import io.github.kazumibridge.runtime.Settings
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.initialize(this)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                runCatching {
                    FrameworkConnection.service = service
                    FrameworkConnection.preferences = service.getRemotePreferences("bridge")
                    FrameworkConnection.publish()
                    BridgeState.update { it.copy(framework = "${service.frameworkName} ${service.frameworkVersion}", frameworkApi = service.apiVersion) }
                }.onFailure { BridgeState.error("无法连接 LSPosed 配置服务：${it.javaClass.simpleName}") }
            }
            override fun onServiceDied(service: XposedService) {
                if (FrameworkConnection.service === service) {
                    FrameworkConnection.service = null
                    FrameworkConnection.preferences = null
                    BridgeState.update { it.copy(framework = "框架连接已断开", frameworkApi = 0) }
                }
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

    @Synchronized fun publish() {
        val written = preferences?.edit()?.putBoolean("enabled", active)?.putInt("port", port)?.putString("token", token)?.commit()
        if (written == false) BridgeState.error("配置未能传入 Kazumi，请重新打开模块后重启 Kazumi。")
    }
}
