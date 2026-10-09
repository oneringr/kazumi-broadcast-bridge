package io.github.kazumibridge.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kazumibridge.BuildConfig
import io.github.kazumibridge.runtime.BridgeSnapshot
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun ModuleScreen(state: BridgeSnapshot, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val heartbeatFresh = state.hookSeenAt > 0 && now - state.hookSeenAt in 0..15000
    val hooked = heartbeatFresh && state.nativeStatus == 0
    val nativeFailed = heartbeatFresh && state.nativeStatus != null && state.nativeStatus != 0
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val active = state.frameworkApi >= 102 && !nativeFailed && (state.runningKazumi == true || hooked)
    val title = when {
        state.frameworkApi in 1..101 -> "框架需更新"
        nativeFailed -> "接入异常"
        active -> "模块已激活"
        state.frameworkApi == 0 -> "等待连接"
        state.scopeKazumi == false -> "请配置作用域"
        else -> "等待 Kazumi"
    }
    val summary = when {
        nativeFailed -> "Hook 错误 ${state.nativeStatus}"
        active -> "已接入 Kazumi"
        state.frameworkApi in 1..101 -> "需要 libxposed API 102"
        state.frameworkApi == 0 -> "请检查 LSPosed 模块状态"
        state.scopeKazumi == false -> "在 LSPosed 勾选 Kazumi"
        else -> "框架服务已连接"
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Kazumi 投屏桥") }
        item {
            val status = when {
                nativeFailed -> "异常"
                state.runningKazumi == true || hooked -> "已接入"
                state.scopeKazumi == false -> "未配置"
                else -> "待接入"
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tileSize = if (largeText) maxWidth else (maxWidth - 12.dp) / 2
                val heroColor = if (active) Color(0xFFDFF7E6) else MiuixTheme.colorScheme.surfaceContainer
                val heroText = if (active) Color(0xFF143821) else MiuixTheme.colorScheme.onSurface
                @Composable fun hero() {
                    Card(modifier = Modifier.size(tileSize), cornerRadius = 28.dp,
                        colors = CardDefaults.defaultColors(color = heroColor, contentColor = heroText)) {
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            Icon(BridgeIcons.Goggles, null, Modifier.size(tileSize * .74f).align(Alignment.BottomEnd).offset(x = 24.dp, y = 24.dp),
                                tint = if (active) Color(0xFF53CC7D) else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .22f))
                            Column(Modifier.padding(18.dp)) {
                                Text(title, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = heroText)
                                Text(summary, fontSize = 14.sp, color = heroText.copy(alpha = .7f), modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                if (largeText) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    hero()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusTile("投屏服务", if (state.running) "运行中" else "未开启", Modifier.weight(1f), state.running)
                        StatusTile("Kazumi 接入", status, Modifier.weight(1f), active)
                    }
                } else Row(Modifier.fillMaxWidth().height(tileSize), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    hero()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusTile("投屏服务", if (state.running) "运行中" else "未开启", Modifier.weight(1f), state.running)
                        StatusTile("Kazumi 接入", status, Modifier.weight(1f), active)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(22.dp)) {
                DeviceInfo("系统版本", Build.DISPLAY)
                DeviceInfo("应用版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                DeviceInfo("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                DeviceInfo("LSPosed 版本", if (state.frameworkApi > 0) "${state.framework}, API ${state.frameworkApi}" else state.framework)
                DeviceInfo("构建时间", BuildConfig.BUILD_TIME)
                DeviceInfo("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}", last = true)
            }
        }
        if (!active) item {
            Text("在 LSPosed 中启用模块并勾选 Kazumi，再完全退出、重新打开 Kazumi。投屏服务可在“投屏”页开启。",
                fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable private fun StatusTile(label: String, value: String, modifier: Modifier, positive: Boolean) {
    Card(modifier = modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(12.dp)) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp),
            color = if (positive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
    }
}

@Composable private fun DeviceInfo(label: String, value: String, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 26.dp)) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 5.dp))
    }
}
