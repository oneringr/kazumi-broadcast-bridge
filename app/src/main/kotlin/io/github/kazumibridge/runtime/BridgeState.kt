package io.github.kazumibridge.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class BridgeSnapshot(
    val running: Boolean = false,
    val framework: String = "等待连接 LSPosed",
    val frameworkApi: Int = 0,
    val hookSeenAt: Long = 0,
    val nativeStatus: Int? = null,
    val redirects: Long = 0,
    val stage: String = "等待投屏",
    val detail: String = "开启服务后，在 Kazumi 播放页选择远程投屏。",
    val progress: Double? = null,
    val sourceHost: String = "",
    val receiver: String = "",
    val convertedBytes: Long = 0,
    val memoryCacheBytes: Long = 0,
    val error: String? = null,
    val events: List<String> = emptyList(),
)

object BridgeState {
    val flow = MutableStateFlow(BridgeSnapshot())
    fun update(change: (BridgeSnapshot) -> BridgeSnapshot) = flow.update(change)
    fun event(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        update { it.copy(events = (it.events + "$stamp  $message").takeLast(16)) }
    }
    fun error(message: String) { update { it.copy(error = message, stage = "处理失败", detail = message, progress = null) }; event(message) }
}
