package io.github.kazumibridge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kazumibridge.core.CacheStorage
import io.github.kazumibridge.core.CastMode
import io.github.kazumibridge.runtime.BridgeSettings
import io.github.kazumibridge.runtime.BridgeSnapshot
import io.github.kazumibridge.runtime.MediaCache
import io.github.kazumibridge.runtime.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun SettingsScreen(state: BridgeSnapshot, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Settings.read()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var referer by rememberSaveable { mutableStateOf(settings.referer) }
    var cookie by rememberSaveable { mutableStateOf(settings.cookie) }
    var userAgent by rememberSaveable { mutableStateOf(settings.userAgent) }
    var cacheBytes by remember { mutableLongStateOf(0) }
    val directory = remember { File(context.cacheDir, "cast-media") }
    LaunchedEffect(state.running) { cacheBytes = withContext(Dispatchers.IO) { MediaCache.usage(directory) } }
    fun saved(value: BridgeSettings) { Settings.save(value); settings = value }
    LazyColumn(modifier.imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("设置", "配置变更在下一次投屏生效") }
        item { SectionTitle("播放与下载") }
        item {
            Card(cornerRadius = 28.dp) {
                SuperDropdown(items = listOf("完整模式", "边转换模式"), selectedIndex = if (settings.mode == CastMode.COMPLETE) 0 else 1,
                    title = "投屏模式", summary = "完整模式先处理完再播放；边转换模式需接收端支持分片 MP4。",
                    onSelectedIndexChange = { saved(settings.copy(mode = if (it == 0) CastMode.COMPLETE else CastMode.STREAM)) })
                SuperDropdown(items = Settings.hlsParallelismOptions.map { "$it 路" },
                    selectedIndex = Settings.hlsParallelismOptions.indexOf(settings.hlsParallelism).coerceAtLeast(0), title = "HLS 下载并发",
                    summary = "仅用于完整模式的有限 HLS；来源限速时可降低并发。",
                    onSelectedIndexChange = { saved(settings.copy(hlsParallelism = Settings.hlsParallelismOptions[it])) })
                SuperSwitch(checked = settings.cacheMp4, onCheckedChange = { saved(settings.copy(cacheMp4 = it)) }, title = "缓存 MP4",
                    summary = "先完整缓存再投屏，减少来源网络波动造成的中断。")
                SuperSwitch(checked = settings.forceH264, onCheckedChange = { saved(settings.copy(forceH264 = it)) }, title = "强制使用 H.264",
                    summary = "将 H.265 转为 H.264；重编码会增加耗电和发热。")
            }
        }
        item { SectionTitle("缓存") }
        item {
            Card(cornerRadius = 28.dp) {
                SuperDropdown(items = Settings.cacheStorageOptions.map { if (it == CacheStorage.DISK) "磁盘" else "内存" },
                    selectedIndex = Settings.cacheStorageOptions.indexOf(settings.cacheStorage).coerceAtLeast(0), title = "缓存位置",
                    summary = "内存缓存不会自动回退到磁盘。",
                    onSelectedIndexChange = { saved(settings.copy(cacheStorage = Settings.cacheStorageOptions[it])) })
                if (settings.cacheStorage == CacheStorage.MEMORY) {
                    SuperDropdown(items = Settings.memoryCacheMiBOptions.map { "$it MB" },
                        selectedIndex = Settings.memoryCacheMiBOptions.indexOf(settings.memoryCacheMiB).coerceAtLeast(0), title = "内存缓存阈值",
                        summary = "分片、清单与最终 MP4 合计达到阈值即停止报错。",
                        onSelectedIndexChange = { saved(settings.copy(memoryCacheMiB = Settings.memoryCacheMiBOptions[it])) })
                } else {
                    SuperDropdown(items = Settings.diskCacheGiBOptions.map { "$it GB" },
                        selectedIndex = Settings.diskCacheGiBOptions.indexOf(settings.cacheGiB).coerceAtLeast(0), title = "单次磁盘缓存上限",
                        summary = "分片与 MP4 达到上限或剩余空间不足时停止转换。",
                        onSelectedIndexChange = { saved(settings.copy(cacheGiB = Settings.diskCacheGiBOptions[it])) })
                }
                BasicComponent(title = "清理磁盘缓存", summary = "当前 ${megabytes(cacheBytes)} MB；先停止服务即可清理。",
                    enabled = !state.running, onClick = {
                        runCatching { MediaCache.clear(directory); cacheBytes = MediaCache.usage(directory) }
                            .onSuccess { Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "清理失败：${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show() }
                    })
            }
        }
        item { SectionTitle("来源请求头") }
        item {
            Card(cornerRadius = 28.dp) {
                BasicComponent(title = "手工覆盖请求头", summary = if (advanced) "点击收起" else "留空沿用播放器；手工填写优先覆盖", onClick = { advanced = !advanced })
                if (advanced) Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value = referer, onValueChange = { referer = it }, label = "Referer", singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = cookie, onValueChange = { cookie = it }, label = "Cookie", singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    TextField(value = userAgent, onValueChange = { userAgent = it }, label = "User-Agent", singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("仅保存在本机，投屏时用于读取来源。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextButton("保存请求头", onClick = {
                        runCatching { saved(settings.copy(referer = referer, cookie = cookie, userAgent = userAgent)) }
                            .onSuccess { Toast.makeText(context, "已保存，下次投屏生效", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    })
                }
            }
        }
    }
}

@Composable private fun SectionTitle(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}
