package io.github.kazumibridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kazumibridge.runtime.BridgeSnapshot
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun CastScreen(state: BridgeSnapshot, toggleService: () -> Unit, openKazumi: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("投屏", "DJI Goggles 2") }
        item {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(24.dp)) {
                val color = if (state.error != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(BridgeIcons.Goggles, null, Modifier.size(124.dp).padding(vertical = 10.dp), tint = color)
                    Text(if (!state.running && state.error == null) "服务未开启" else state.stage, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Text(state.detail, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 10.dp))
                    state.progress?.let { progress ->
                        Canvas(Modifier.fillMaxWidth().padding(top = 18.dp).height(5.dp)) {
                            drawLine(color.copy(alpha = .15f), Offset.Zero, Offset(size.width, 0f), size.height)
                            drawLine(color, Offset.Zero, Offset(size.width * progress.toFloat().coerceIn(0f, 1f), 0f), size.height)
                        }
                        Text("${(progress * 100).toInt()}%", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp), color = color)
                    }
                    if (state.convertedBytes > 0) Text("已生成 ${megabytes(state.convertedBytes)} MB", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = toggleService, modifier = Modifier.weight(1f)) { Text(if (state.running) "停止服务" else "开启服务") }
                TextButton("打开 Kazumi", onClick = openKazumi, modifier = Modifier.weight(1f))
            }
        }
        if (state.sourceHost.isNotEmpty() || state.receiver.isNotEmpty() || state.memoryCacheBytes > 0) item {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                if (state.sourceHost.isNotEmpty()) BasicComponent(title = "视频来源", summary = state.sourceHost)
                if (state.receiver.isNotEmpty()) BasicComponent(title = "接收端地址", summary = state.receiver)
                if (state.memoryCacheBytes > 0) BasicComponent(title = "内存缓存", summary = "${megabytes(state.memoryCacheBytes)} MB")
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(20.dp)) {
                Text("处理日志", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("最近 16 条记录", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp))
                SelectionContainer {
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.events.isEmpty()) Text("暂无记录。开启服务后，在 Kazumi 中选择远程投屏。", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        else state.events.asReversed().forEach { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                }
            }
        }
        item { Text("手机与眼镜需连接同一局域网，眼镜停留在无线串流页面。", fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}
