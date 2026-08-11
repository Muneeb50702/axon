package dev.axon.android.app.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import dev.axon.android.app.R

/**
 * The gateway foreground service (spec §7.9, §7.10, §16).
 *
 * Phase 6 gives this Binder/AIDL routing, session management and capability
 * checks. What it already does — and does from Phase 0 on purpose — is satisfy
 * §16's first guardrail: *"the agent runs as a foreground service with a
 * persistent notification. No covert/background operation."*
 *
 * That ordering is deliberate. Visible operation is not a feature to add once
 * the agent works; it is a property of how the agent runs at all. Building the
 * loop first and wrapping it in a notification later would mean every
 * intermediate build is one that operates the device invisibly, and the
 * architecture would have no place that *requires* the service to be showing.
 * Starting here means the only way to run the agent is through a path that is
 * already visible to the user.
 */
class AxonGatewayService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.gateway_idle)))
        // NOT_STICKY: if the OS kills the service, it must not silently come back.
        // A restarted agent the user did not ask for is exactly the covert
        // operation §16 forbids, so recovery is the user's decision, not ours.
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gateway_channel_name),
            // IMPORTANCE_LOW keeps it silent but permanently visible. Higher
            // would buzz on every task; lower would let the user hide the very
            // notification that makes operation visible.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.gateway_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        notificationBuilder(this)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private companion object {
        const val CHANNEL_ID = "axon_gateway"
        const val NOTIFICATION_ID = 1001

        fun notificationBuilder(context: Context): Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
    }
}
