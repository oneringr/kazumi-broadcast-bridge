package io.github.kazumibridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.kazumibridge.core.CastMode
import io.github.kazumibridge.runtime.BridgeService
import io.github.kazumibridge.runtime.BridgeState
import io.github.kazumibridge.runtime.MediaCache
import io.github.kazumibridge.runtime.Settings
import java.io.File
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBridge() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = theme) { BridgeScreen() }
        }
    }

    private fun startBridge() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java)) }
            .onFailure { BridgeState.error("无法启动服务：${it.javaClass.simpleName}") }
    }

    private fun toggleService(running: Boolean) {
        if (running) { stopService(Intent(this, BridgeService::class.java)); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startBridge()
    }

    @Composable private fun BridgeScreen() {
        val state by BridgeState.flow.collectAsState()
        var settings by remember { mutableStateOf(Settings.read()) }
        var advanced by remember { mutableStateOf(false) }
        var referer by remember { mutableStateOf(settings.referer) }
        var cookie by remember { mutableStateOf(settings.cookie) }
        var userAgent by remember { mutableStateOf(settings.userAgent) }
        var cacheBytes by remember { mutableLongStateOf(cacheUsage()) }
        fun saved(value: io.github.kazumibridge.runtime.BridgeSettings) { Settings.save(value); settings = value }
        Scaffold(topBar = { TopAppBar(title = "Kazumi 投屏桥") }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val color = if (state.error != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
                        Canvas(Modifier.size(104.dp, 64.dp)) {
                            val path = Path().apply {
                                moveTo(size.width * .08f, size.height * .16f); lineTo(size.width * .92f, size.height * .16f)
                                lineTo(size.width * .88f, size.height * .75f); lineTo(size.width * .64f, size.height * .75f)
                                lineTo(size.width * .55f, size.height * .55f); lineTo(size.width * .45f, size.height * .55f)
                                lineTo(size.width * .36f, size.height * .75f); lineTo(size.width * .12f, size.height * .75f); close()
                            }
                            drawPath(path, color, style = Stroke(3.dp.toPx()))
                            drawLine(color, Offset(size.width * .08f, size.height * .35f), Offset(0f, size.height * .35f), 3.dp.toPx())
                            drawLine(color, Offset(size.width * .92f, size.height * .35f), Offset(size.width, size.height * .35f), 3.dp.toPx())
                        }
                        Text(state.stage, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("DJI Goggles 2", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp))
                        Text(state.detail, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        state.progress?.let { value ->
                            Canvas(Modifier.fillMaxWidth().padding(top = 16.dp).height(4.dp)) {
                                drawLine(color.copy(alpha = .15f), Offset.Zero, Offset(size.width, 0f), size.height)
                                drawLine(color, Offset.Zero, Offset(size.width * value.toFloat(), 0f), size.height)
                            }
                            Text("${(value * 100).toInt()}%  ·  ${megabytes(state.convertedBytes)} MB", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { toggleService(state.running) }, modifier = Modifier.weight(1f)) {
                            Text(if (state.running) "停止服务" else "开启服务")
                        }
                        TextButton("打开 Kazumi", onClick = {
                            val launch = packageManager.getLaunchIntentForPackage("com.predidit.kazumi")
                            if (launch != null) startActivity(launch) else Toast.makeText(this@MainActivity, "请先安装 Kazumi", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.weight(1f))
                    }
                }
                item {
                    Card(insideMargin = PaddingValues(0.dp)) {
                        val hooked = state.hookSeenAt > 0 && System.currentTimeMillis() - state.hookSeenAt < 15000
                        BasicComponent(title = "模块连接", summary = when {
                            state.frameworkApi in 1..101 -> "当前框架 API ${state.frameworkApi}，需要 API 102。"
                            state.nativeStatus != null && state.nativeStatus != 0 -> "Native Hook 失败，错误码 ${state.nativeStatus}。查看 LSPosed 日志。"
                            hooked -> "已接入 Kazumi，转发连接 ${state.redirects} 次"
                            else -> "在 LSPosed 启用本模块并勾选 Kazumi，然后重启 Kazumi。"
                        })
                        BasicComponent(title = "LSPosed", summary = if (state.frameworkApi > 0) "${state.framework} / API ${state.frameworkApi}" else state.framework)
                        if (state.sourceHost.isNotEmpty()) BasicComponent(title = "当前来源", summary = state.sourceHost)
                    }
                }
                item {
                    Card(insideMargin = PaddingValues(0.dp)) {
                        SuperDropdown(items = listOf("完整转换后投屏", "边转换边投屏"), selectedIndex = if (settings.mode == CastMode.COMPLETE) 0 else 1,
                            title = "投屏模式", summary = "切换后在下一次投屏生效", onSelectedIndexChange = { index -> saved(settings.copy(mode = if (index == 0) CastMode.COMPLETE else CastMode.STREAM)) })
                        Text(if (settings.mode == CastMode.COMPLETE) "等待完整 MP4 生成，接收端兼容性更好，并支持按字节读取和拖动进度。" else
                            "生成首个完整 MP4 分片后起播。眼镜需要支持分片 MP4 和流式 HTTP；生成期间无法任意拖动进度。若无法播放，请切回完整模式。",
                            fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        SuperDropdown(items = listOf("1 路", "2 路", "4 路", "8 路"),
                            selectedIndex = listOf(1, 2, 4, 8).indexOf(settings.hlsParallelism).coerceAtLeast(0),
                            title = "HLS 下载并发", summary = "仅用于完整模式的有限视频；下一次投屏生效，来源限速时可降低并发。",
                            onSelectedIndexChange = { saved(settings.copy(hlsParallelism = listOf(1, 2, 4, 8)[it])) })
                        SuperSwitch(checked = settings.forceH264, onCheckedChange = { saved(settings.copy(forceH264 = it)) }, title = "强制使用 H.264",
                            summary = "开启后将 H.265 转为 H.264；重编码会增加耗电和发热。")
                    }
                }
                item {
                    Card(insideMargin = PaddingValues(0.dp)) {
                        SuperDropdown(items = listOf("1 GB", "2 GB", "4 GB", "8 GB"), selectedIndex = listOf(1, 2, 4, 8).indexOf(settings.cacheGiB).coerceAtLeast(0),
                            title = "单次缓存上限", summary = "分片与 MP4 合计达到上限或剩余空间不足时停止转换", onSelectedIndexChange = { saved(settings.copy(cacheGiB = listOf(1, 2, 4, 8)[it])) })
                        BasicComponent(title = "清理缓存", summary = "当前 ${megabytes(cacheBytes)} MB。先停止服务即可清理。", enabled = !state.running, onClick = {
                            MediaCache.clear(File(cacheDir, "cast-media"))
                            cacheBytes = cacheUsage()
                            Toast.makeText(this@MainActivity, "缓存已清理", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
                item {
                    Card(insideMargin = PaddingValues(0.dp)) {
                        BasicComponent(title = "来源请求头", summary = "留空沿用播放器；手工填写优先覆盖", onClick = { advanced = !advanced })
                        if (advanced) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextField(value = referer, onValueChange = { referer = it }, label = "Referer", singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextField(value = cookie, onValueChange = { cookie = it }, label = "Cookie", singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            TextField(value = userAgent, onValueChange = { userAgent = it }, label = "User-Agent（留空自动）", singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("请求头保存在本机，投屏时用于读取来源。这里只能处理服务允许访问的视频。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TextButton("保存请求头", onClick = {
                                runCatching { saved(settings.copy(referer = referer, cookie = cookie, userAgent = userAgent)) }
                                    .onSuccess { Toast.makeText(this@MainActivity, "已保存，下次投屏生效", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
                            })
                        }
                    }
                }
                if (state.events.isNotEmpty()) {
                    item { Text("最近处理记录", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp)) }
                    items(state.events.asReversed()) { text -> Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                }
                item { Text("连接同一 Wi-Fi，并在眼镜中打开无线串流。手机锁屏后请允许本模块继续在后台运行。", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }

    private fun cacheUsage() = MediaCache.usage(File(cacheDir, "cast-media"))
    private fun megabytes(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024))
}
