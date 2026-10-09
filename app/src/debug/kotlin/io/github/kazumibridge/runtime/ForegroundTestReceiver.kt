package io.github.kazumibridge.runtime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kazumibridge.FrameworkConnection

/** A DUMP-permission-only test entry point; absent from the release manifest and APK. */
class ForegroundTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = Activity.RESULT_CANCELED
        resultData = "unsupported"
        val action = intent.action
        if (action !in setOf(REQUEST_FOREGROUND, CLEAR_FOREGROUND)) return
        if (FrameworkConnection.preferences == null ||
            (action == REQUEST_FOREGROUND && !FrameworkConnection.active)) {
            resultData = "unavailable"
            return
        }
        runCatching {
            val accepted = if (action == REQUEST_FOREGROUND) {
                FrameworkConnection.requestForeground()
            } else {
                FrameworkConnection.clearForegroundRequest()
            }
            if (!accepted) {
                resultData = "rejected"
            } else {
                resultCode = Activity.RESULT_OK
                resultData = "accepted"
            }
        }.onFailure {
            resultCode = Activity.RESULT_CANCELED
            resultData = "failed:${it.javaClass.simpleName}"
        }
    }

    companion object {
        const val REQUEST_FOREGROUND = "io.github.kazumibridge.debug.REQUEST_FOREGROUND"
        const val CLEAR_FOREGROUND = "io.github.kazumibridge.debug.CLEAR_FOREGROUND"
    }
}
