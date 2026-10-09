package io.github.kazumibridge.hook

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference

/** Launch from Kazumi's visible Activity rather than the module's background service. */
class ForegroundLauncher(application: Application, private val preferences: SharedPreferences) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val keyguard = application.getSystemService(KeyguardManager::class.java)
    private var resumed = WeakReference<Activity>(null)
    // A freshly started Kazumi process must not replay a previous cast's ticket.
    private var handled = preferences.getString(REQUEST_KEY, "").orEmpty()

    init { application.registerActivityLifecycleCallbacks(this) }

    fun refresh() { main.post { showIfRequested() } }

    private fun showIfRequested() {
        // Read again on the main thread: Stop may have cleared a queued request.
        val values = preferences.all
        val request = values[REQUEST_KEY] as? String ?: return
        val requestedAt = values[TIME_KEY] as? Long ?: return
        if (values["enabled"] != true || request.isEmpty() || request == handled ||
            SystemClock.elapsedRealtime() - requestedAt !in 0..MAX_AGE_MS || keyguard?.isKeyguardLocked == true) return
        val activity = resumed.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            activity.startActivity(Intent().setComponent(ComponentName("io.github.kazumibridge", "io.github.kazumibridge.ui.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            handled = request
            Log.i("KazumiBridgeDiagnostics", "Opened module UI for a new casting request")
        } catch (error: Exception) {
            Log.w("KazumiBridgeDiagnostics", "Could not open module UI: ${error.javaClass.simpleName}")
        }
    }

    override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity); showIfRequested() }
    override fun onActivityPaused(activity: Activity) { if (resumed.get() === activity) resumed.clear() }
    override fun onActivityDestroyed(activity: Activity) { if (resumed.get() === activity) resumed.clear() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

    companion object {
        const val REQUEST_KEY = "foregroundRequest"
        const val TIME_KEY = "foregroundRequestedAt"
        private const val MAX_AGE_MS = 10000L
    }
}
