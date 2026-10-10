package io.github.kazumibridge.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.wifi.WifiManager
import io.github.kazumibridge.FrameworkConnection
import io.github.kazumibridge.R
import io.github.kazumibridge.ui.LaunchDestination
import io.github.kazumibridge.ui.MainActivity
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

class BridgeService : Service() {
    private val lifecycle = Executors.newSingleThreadExecutor { task -> Thread(task, "KazumiBridge-service") }
    private var relay: RelayServer? = null
    private var media: MediaServer? = null
    private var engine: MediaEngine? = null
    private var controller: RendererController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val powerLock = Any()
    private val powerHandler = Handler(Looper.getMainLooper())
    private var powerActive = false
    private val renewPower = object : Runnable {
        override fun run() = synchronized(powerLock) {
            if (powerActive && !stopping) {
                acquireCpuLease()
                powerHandler.postDelayed(this, POWER_RENEW_MS)
            }
            Unit
        }
    }
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action in setOf(ACTION_RETRY, ACTION_FORCE)) {
            if (!stopping) lifecycle.execute {
                if (stopping) return@execute
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()
                val accepted = runCatching {
                    when {
                        taskId.isEmpty() -> false
                        intent?.action == ACTION_RETRY -> controller?.retryLast(taskId) == true
                        intent?.action == ACTION_FORCE -> controller?.forceLast(taskId) == true
                        else -> false
                    }
                }.onFailure { BridgeState.error("恢复投屏失败：${it.javaClass.simpleName}。") }.getOrDefault(false)
                if (!accepted) BridgeState.event("当前任务已变化，操作未执行。")
                if (controller == null && relay == null) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel("cast", getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(LaunchDestination.EXTRA_PAGE, LaunchDestination.CAST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "cast").setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(getString(R.string.notification_title)).setContentText(getString(R.string.notification_body))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.stop_service), stop).build()).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (relay == null) lifecycle.execute {
            if (relay != null || stopping) return@execute
            try {
                engine = MediaEngine(this, File(cacheDir, "cast-media"))
                media = MediaServer()
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KazumiBridge:cast").apply { setReferenceCounted(false) }
                @Suppress("DEPRECATION")
                val wifiMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                // API 34+ maps HIGH_PERF to LOW_LATENCY, which has no screen-off effect.
                if (Build.VERSION.SDK_INT < 34) {
                    wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                        ?.createWifiLock(wifiMode, "KazumiBridge:wifi")?.apply { setReferenceCounted(false) }
                }
                controller = RendererController(engine!!, media!!) { active ->
                    if (active) FrameworkConnection.requestForeground() else FrameworkConnection.clearForegroundRequest()
                    updatePowerLocks(active)
                }
                val token = ByteArray(32).also(SecureRandom()::nextBytes)
                relay = RelayServer(token, controller!!)
                FrameworkConnection.port = relay!!.port
                FrameworkConnection.token = token.joinToString("") { "%02x".format(it.toInt() and 255) }
                FrameworkConnection.active = true
                FrameworkConnection.publish()
                BridgeState.update { it.copy(running = true, error = null, stage = "等待投屏", detail = "请在 Kazumi 中选择大疆眼镜 2。首次启用模块后需要重启 Kazumi。") }
                BridgeState.event("投屏服务已启动")
            } catch (error: Throwable) {
                BridgeState.error("服务启动失败：${error.javaClass.simpleName}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        updatePowerLocks(false)
        FrameworkConnection.active = false
        FrameworkConnection.publish()
        lifecycle.execute {
            runCatching { controller?.close() }
            runCatching { relay?.close() }
            runCatching { media?.close() }
            runCatching { engine?.close() }
            updatePowerLocks(false)
            BridgeState.update { it.copy(running = false, progress = null, memoryCacheBytes = 0, sourceUrl = "", sourceHost = "", taskId = "",
                canRetry = false, canForce = false, stage = "服务已停止", detail = "重新开启服务即可继续投屏。") }
            BridgeState.event("投屏服务已停止")
        }
        lifecycle.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun updatePowerLocks(active: Boolean) = synchronized(powerLock) {
        powerActive = active && !stopping
        powerHandler.removeCallbacks(renewPower)
        if (active && !stopping) {
            acquireCpuLease()
            powerHandler.postDelayed(renewPower, POWER_RENEW_MS)
            runCatching { wifiLock?.let { if (!it.isHeld) it.acquire() } }
                .onFailure { BridgeState.event("Wi-Fi 保活锁不可用：${it.javaClass.simpleName}") }
        } else {
            runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        }
    }
    private fun acquireCpuLease() {
        runCatching { wakeLock?.acquire(POWER_LEASE_MS) }
            .onFailure { BridgeState.event("CPU 保活锁不可用：${it.javaClass.simpleName}") }
    }
    companion object {
        const val ACTION_STOP = "io.github.kazumibridge.STOP"
        const val ACTION_RETRY = "io.github.kazumibridge.RETRY"
        const val ACTION_FORCE = "io.github.kazumibridge.FORCE"
        const val EXTRA_TASK_ID = "io.github.kazumibridge.TASK_ID"
        private const val POWER_LEASE_MS = 2 * 60 * 60 * 1000L
        private const val POWER_RENEW_MS = 30 * 60 * 1000L
    }
}
