package dev.axon.android.app.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.axon.android.app.AxonAgent
import dev.axon.android.app.R
import dev.axon.core.model.Goal
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The gateway foreground service (spec §7.9, §16).
 *
 * ## Why this is the component that turns AXON into an OS-level agent
 *
 * Everything before this ran inside an Activity, which meant AXON could only
 * ever perceive *its own screen*: the moment another app came to the front, AXON
 * was backgrounded and the OEM power manager began its countdown. Experiment
 * E18 is a direct consequence — the planner was asked to open WhatsApp and spent
 * three attempts hunting for a WhatsApp icon on AXON's own UI, because that was
 * the only screen it could see.
 *
 * A foreground service inverts that. The agent runs while the user's app is in
 * front, so `observe()` returns the screen being operated rather than the
 * operator. This is the difference between an agent that can demonstrate a loop
 * and one that can drive the device.
 *
 * ## Visibility is the mechanism, not a side effect
 *
 * §16's first guardrail: *"Visible, user-initiated, foreground operation only…
 * No covert/background operation. No hiding the service."*
 *
 * The persistent notification is usually described as a cost of using a
 * foreground service. Here it is the point. The same API that lets AXON read any
 * screen is the one a stalkerware app would use, and the only structural
 * difference available is that AXON *cannot* run without announcing itself:
 * Android tears down a foreground service that fails to post its notification.
 * The guarantee is enforced by the platform rather than promised by the app.
 *
 * Three further properties follow from the same reasoning:
 *
 *  - **`START_NOT_STICKY`** — a killed agent stays dead. An agent the OS silently
 *    revives is an agent the user did not ask for.
 *  - **A Stop action on the notification** — §16 requires the user to be able to
 *    end the run, and it must be reachable while another app is in front, which
 *    is precisely when AXON's own UI is not.
 *  - **Cancellation lands on a step boundary**, never mid-gesture. Aborting a
 *    half-dispatched swipe leaves the UI somewhere neither party expects.
 */
class AxonGatewayService : LifecycleService() {

    private var agent: AxonAgent? = null
    private var currentTask: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        agent = AxonAgent(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopTask()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RUN -> {
                val utterance = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                if (utterance.isNotBlank()) startTask(utterance)
            }
        }

        startForeground(NOTIFICATION_ID, notification(getString(R.string.gateway_idle)))

        // NOT_STICKY: if the OS kills this, it must not silently come back. A
        // restarted agent the user did not ask for is exactly the covert
        // operation §16 forbids, so recovery is the user's decision.
        return START_NOT_STICKY
    }

    private fun startTask(utterance: String) {
        if (currentTask?.isActive == true) {
            Log.w(TAG, "a task is already running; ignoring '$utterance'")
            return
        }

        currentTask = lifecycleScope.launch {
            val a = agent ?: return@launch

            if (!a.isModelLoaded) {
                update("loading model…")
                a.load().onFailure {
                    update("model failed to load")
                    return@launch
                }
            }

            update("running: $utterance")

            // The step budget is deliberately small for interactive use. At ~60 s
            // per planning step on this hardware, a generous budget means a task
            // that has gone wrong keeps going wrong for ten minutes while the
            // user watches. Better to escalate early and let them redirect.
            val result = a.run(Goal(utterance, stepBudget = 6))

            result
                .onSuccess { update("${it.outcome} — ${it.steps.size} steps, ${it.totalMs / 1000}s") }
                .onFailure { update("error: ${it.message}") }
        }
    }

    private fun stopTask() {
        currentTask?.cancel()
        currentTask = null
    }

    override fun onDestroy() {
        stopTask()
        agent?.close()
        agent = null
        super.onDestroy()
    }

    // -----------------------------------------------------------------

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, AxonGatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return notificationBuilder(this)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            // Reachable while another app is in front — which is exactly when
            // AXON's own UI is not, and exactly when the user most needs a way
            // to stop it.
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.gateway_stop), stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gateway_channel_name),
            // LOW keeps it silent but permanently visible. Higher would buzz on
            // every task; lower would let the user hide the very notification
            // that makes operation visible, which §16 does not permit.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.gateway_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AxonGateway"
        private const val CHANNEL_ID = "axon_gateway"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_RUN = "dev.axon.action.RUN"
        const val ACTION_STOP = "dev.axon.action.STOP"
        const val EXTRA_GOAL = "goal"

        /**
         * Start a task from anywhere — the app UI, a quick-settings tile, or
         * `adb shell am start-foreground-service`.
         *
         * That last one is not a debugging convenience: §6.2 requires a second
         * client type, and an intent-driven entry point is what makes the agent
         * *a service other things talk to* rather than an app (§7.9).
         */
        fun run(context: Context, utterance: String) {
            val intent = Intent(context, AxonGatewayService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_GOAL, utterance)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, AxonGatewayService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun notificationBuilder(context: Context): Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
    }
}
