package io.github.kazumibridge.ui

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.BasicComponent

@Composable
internal fun BackgroundProtection() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var standbyRestricted by remember { mutableStateOf(false) }
    DisposableEffect(owner, context) {
        fun refresh() {
            exempt = power.isIgnoringBatteryOptimizations(context.packageName)
            standbyRestricted = Build.VERSION.SDK_INT >= 34 && power.isLowPowerStandbyEnabled && !power.isExemptFromLowPowerStandby
        }
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun open(intent: Intent, fallback: Intent? = null) {
        runCatching { context.startActivity(intent) }.onFailure {
            if (fallback == null || runCatching { context.startActivity(fallback) }.isFailure)
                Toast.makeText(context, "无法打开系统设置，请在应用信息中手动设置。", Toast.LENGTH_LONG).show()
        }
    }
    BasicComponent(
        title = "电池优化",
        summary = if (exempt) "已豁免；投屏期间保持 CPU 运行" else "尚未豁免；点击申请，减少息屏后的网络限制",
        enabled = !exempt,
        onClick = {
            open(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
    )
    BasicComponent(title = "后台运行设置", summary = "建议设为“无限制”并允许后台运行；投屏结束后停止服务。",
        onClick = { open(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) })
    if (standbyRestricted) BasicComponent(title = "系统低功耗待机已开启", summary = "当前可能在息屏后限制网络，请检查系统省电设置。",
        onClick = { open(Intent(AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS)) })
}
