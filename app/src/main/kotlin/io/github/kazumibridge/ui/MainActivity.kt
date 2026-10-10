package io.github.kazumibridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import io.github.kazumibridge.FrameworkConnection
import io.github.kazumibridge.runtime.BridgeService
import io.github.kazumibridge.runtime.BridgeState
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {
    private var requestedPage by mutableStateOf<Int?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBridge() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedPage = destination(intent)
        setContent {
            val theme = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = theme) { BridgeScreen() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPage = destination(intent)
    }

    override fun onResume() {
        super.onResume()
        FrameworkConnection.refreshFrameworkStatus()
    }

    private fun destination(intent: Intent?) = if (intent?.getStringExtra(LaunchDestination.EXTRA_PAGE) == LaunchDestination.CAST) 1 else 0

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

    private fun openKazumi() {
        val launch = packageManager.getLaunchIntentForPackage("com.predidit.kazumi")
        if (launch != null) startActivity(launch) else Toast.makeText(this, "请先安装 Kazumi", Toast.LENGTH_SHORT).show()
    }

    private fun recoveryAction(action: String, taskId: String) {
        if (!BridgeState.flow.value.running || taskId.isEmpty()) return
        runCatching { startService(Intent(this, BridgeService::class.java).setAction(action).putExtra(BridgeService.EXTRA_TASK_ID, taskId)) }
            .onFailure { Toast.makeText(this, "操作未执行，请检查服务状态。", Toast.LENGTH_SHORT).show() }
    }

    @Composable private fun BridgeScreen() {
        val state by BridgeState.flow.collectAsState()
        var selected by rememberSaveable { mutableIntStateOf(0) }
        val pages = rememberSaveableStateHolder()
        val labels = listOf("模块", "投屏", "设置", "关于")
        val icons = listOf(BridgeIcons.Home, BridgeIcons.Goggles, BridgeIcons.Settings, BridgeIcons.Info)
        val moduleTheme = remember {
            ThemeController(ColorSchemeMode.System,
                lightColors = lightColorScheme(primary = Color(0xFF236841), onPrimary = Color.White,
                    primaryVariant = Color(0xFF236841), primaryContainer = Color(0xFFDFF7E6),
                    onPrimaryContainer = Color(0xFF143821), onPrimaryVariant = Color.White,
                    surface = Color(0xFFF5F7F5), background = Color(0xFFF5F7F5), surfaceContainer = Color.White),
                darkColors = darkColorScheme(primary = Color(0xFF79DCA0), onPrimary = Color(0xFF102619),
                    primaryVariant = Color(0xFF79DCA0), primaryContainer = Color(0xFF21462F),
                    onPrimaryContainer = Color(0xFFCAF3D8), onPrimaryVariant = Color(0xFF102619),
                    surface = Color.Black, background = Color.Black, surfaceContainer = Color(0xFF242424)))
        }
        LaunchedEffect(requestedPage) {
            requestedPage?.let { selected = it; requestedPage = null }
        }
        LaunchedEffect(Unit) {
            while (true) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) FrameworkConnection.refreshFrameworkStatus()
                delay(5000)
            }
        }
        BackHandler(enabled = selected != 0) { selected = 0 }
        Scaffold(bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = selected == index, onClick = { selected = index }, icon = icons[index], label = label)
                }
            }
        }) { padding ->
            AnimatedContent(
                targetState = selected,
                contentKey = { it },
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds(),
                label = "bridge-pages",
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    ((slideInHorizontally(folmeSpring(damping = .9f, response = .3f)) { it / 12 * direction } +
                        fadeIn(folmeSpring(damping = 1f, response = .3f))) togetherWith
                        (slideOutHorizontally(folmeSpring(damping = .9f, response = .3f)) { -it / 16 * direction } +
                            fadeOut(folmeSpring(damping = 1f, response = .2f)))).using(null)
                },
            ) { page ->
                pages.SaveableStateProvider(page) {
                    val modifier = Modifier.fillMaxSize()
                    when (page) {
                        0 -> MiuixTheme(controller = moduleTheme) {
                            ModuleScreen(state, modifier.background(MiuixTheme.colorScheme.background))
                        }
                        1 -> CastScreen(state, { toggleService(state.running) }, ::openKazumi,
                            { recoveryAction(BridgeService.ACTION_RETRY, state.taskId) }, { recoveryAction(BridgeService.ACTION_FORCE, state.taskId) }, modifier)
                        2 -> SettingsScreen(state, modifier)
                        else -> AboutScreen(modifier)
                    }
                }
            }
        }
    }
}
