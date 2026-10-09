package io.github.kazumibridge.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.kazumibridge.BuildConfig
import io.github.kazumibridge.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.IOException

@Composable
internal fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<GitHubRelease?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNotes by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }

    fun checkUpdate(revealNotes: Boolean = false) {
        if (checking) return
        if (revealNotes) showNotes = true
        scope.launch {
            checking = true
            error = null
            try {
                release = GitHubReleases.latest()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                error = failure.message?.takeIf { it.startsWith("GitHub") || it.startsWith("更新信息") || it.startsWith("暂时没有") }
                    ?: "连接失败，请检查网络后重试。"
            } catch (_: Exception) {
                error = "更新信息暂时无法读取，请稍后重试。"
            } finally {
                checking = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageTitle("关于", "Kazumi 投屏桥 · ${BuildConfig.VERSION_NAME}") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 28.dp,
                insideMargin = PaddingValues(22.dp),
                onClick = { openAboutLink(context, "https://github.com/oneringr") },
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Image(
                        painter = painterResource(R.drawable.developer_avatar),
                        contentDescription = "OneRingR 的 GitHub 头像",
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("OneRingR", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("@oneringr", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 15.sp)
                        Text("开发者 · GitHub", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(0.dp)) {
                val available = release
                val comparison = available?.let { compareReleaseVersions(it.tag, BuildConfig.VERSION_NAME) }
                BasicComponent(
                    title = if (checking) "正在检查更新…" else "检查更新",
                    summary = when {
                        checking -> "正在获取 GitHub 最新稳定版本"
                        error != null -> error
                        available == null -> "当前版本 ${BuildConfig.VERSION_NAME} · 点按检查"
                        comparison == null -> "最新稳定版本 ${available.tag}"
                        comparison > 0 -> "发现新版本 ${available.tag}"
                        comparison == 0 -> "已是最新稳定版本 ${available.tag}"
                        else -> "最新稳定版本 ${available.tag} · 当前版本 ${BuildConfig.VERSION_NAME}"
                    },
                    enabled = !checking,
                    onClick = { checkUpdate() },
                )
                val releasePage = available?.pageUrl?.takeIf { error == null && GitHubReleases.isReleasePage(it) }
                    ?: GitHubReleases.LATEST_PAGE_URL
                TextButton(
                    text = if (error == null && comparison != null && comparison > 0) "前往 GitHub 下载" else "查看 GitHub Release",
                    onClick = { openAboutLink(context, releasePage) },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
                BasicComponent(
                    title = "更新日志",
                    summary = if (showNotes) "收起发布说明" else "查看最新稳定版本的发布说明",
                    enabled = !checking,
                    onClick = {
                        if (release == null) checkUpdate(revealNotes = true) else showNotes = !showNotes
                    },
                )
            }
        }
        if (showNotes && release != null) {
            item {
                val available = release!!
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(20.dp)) {
                    Text(available.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (available.publishedDate.isNotBlank()) {
                        Text(available.publishedDate, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                    }
                    SelectionContainer {
                        Text(available.notes, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = "反馈问题", summary = "前往 GitHub Issues 报告问题或提出建议",
                    onClick = { openAboutLink(context, "${GitHubReleases.REPOSITORY_URL}/issues") })
                BasicComponent(title = "参考与致谢", summary = "开源项目与第三方许可", onClick = { showCredits = !showCredits })
                BasicComponent(title = "项目源码", summary = "oneringr / kazumi-broadcast-bridge",
                    onClick = { openAboutLink(context, GitHubReleases.REPOSITORY_URL) })
            }
        }
        if (showCredits) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(0.dp)) {
                    AboutCredit("Kazumi", "提供播放器与投屏入口", "https://github.com/Predidit/Kazumi", "GPL-3.0", "https://www.gnu.org/licenses/gpl-3.0.html")
                    AboutCredit("libxposed", "API 102 与模块服务", "https://github.com/libxposed/api", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")
                    AboutCredit("miuix", "Compose 界面组件", "https://github.com/compose-miuix-ui/miuix", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")
                    AboutCredit("ShadowHook", "原生连接 Hook", "https://github.com/bytedance/android-inline-hook", "MIT", "https://opensource.org/license/mit")
                    AboutCredit("FFmpeg / FFmpegKit", "媒体探测、封装与转换 · Full-GPL", "https://github.com/sk3llo/ffmpeg_kit_flutter_android/tree/full-gpl", "GPL-3.0 / LGPL-3.0", "${GitHubReleases.REPOSITORY_URL}/blob/main/THIRD_PARTY_NOTICES.md")
                    AboutCredit("Best-README-Template", "项目文档结构参考", "https://github.com/othneildrew/Best-README-Template", "Unlicense", "https://github.com/othneildrew/Best-README-Template/blob/main/LICENSE.txt")
                    BasicComponent(title = "模块许可", summary = "GPL-3.0 · 查看完整许可",
                        onClick = { openAboutLink(context, "${GitHubReleases.REPOSITORY_URL}/blob/main/LICENSE") })
                }
            }
        }
        item {
            Text("检查更新时连接 GitHub；下载在浏览器中的 Release 页面完成。", fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun AboutCredit(name: String, purpose: String, source: String, license: String, licenseUrl: String) {
    val context = LocalContext.current
    BasicComponent(title = name, summary = purpose, onClick = { openAboutLink(context, source) })
    TextButton(text = license, onClick = { openAboutLink(context, licenseUrl) },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
}

private fun openAboutLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Toast.makeText(context, "无法打开链接，请安装浏览器后重试。", Toast.LENGTH_SHORT).show() }
}
