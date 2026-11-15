package com.example.sensitune

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * "Nhả cảm ứng" from the notification or the Quick Settings tile.
 * Note: if our app process was killed, the UserService (daemon=false) is gone too,
 * so the kernel already released the grab; the call below is then a harmless no-op.
 */
class ReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Actions.RELEASE) return
        LogBuffer.warn("ReleaseReceiver", "release requested from ${intent.`package` ?: "unknown"}")
        try {
            val prefs = Prefs(context)
            prefs.enabled = false
            prefs.onlyInSelectedApps = false
            ShizukuHelper.init(context)
            ShizukuHelper.ensureBound()
            ShizukuHelper.emergencyRelease("notification/tile")
            context.stopService(Intent(context, SensiService::class.java))
            Toast.makeText(context, R.string.released_toast, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            LogBuffer.error("ReleaseReceiver", "release failed", t)
            Toast.makeText(context, R.string.release_failed, Toast.LENGTH_LONG).show()
        }
    }
}
