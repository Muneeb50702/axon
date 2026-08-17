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
import dev.axon.core.executor.ConfirmationReason
import dev.axon.core.model.Goal
import dev.axon.core.runtime.RunConfig
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
 * difference available is that AXON *cannot* run without announcing itself.
 *
 * **An earlier version of this comment claimed the platform enforced that. It
 * does not.** Since Android 13 `POST_NOTIFICATIONS` is a runtime permission, and
 * a foreground service whose notification is suppressed still runs — invisibly.
 * That build shipped and did exactly that: the permission was declared in the
 * manifest, never requested, and the agent drove the device with nothing on the
 * notification bar. "Foreground service" does not imply "visible".
 *
 * So the guarantee is enforced *here*, by [canBeSeen], and the enforcement is a
 * refusal: if the user cannot see AXON operating, AXON does not operate. That is
 * the same shape as every other guardrail in this project — make the unwanted
 * behaviour impossible rather than discouraged — and it is worth stating in the
 * thesis, because "we use a foreground service" is a weaker claim than it sounds
 * on modern Android.
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
                // §14.3's arm, defaulting to the shipping configuration. The
                // only caller that ever passes it is a measurement harness over
                // adb; a user tapping "run" gets D and cannot get anything else.
                val config = RunConfig.of(intent.getStringExtra(EXTRA_CONFIG))
                if (utterance.isNotBlank()) startTask(utterance, config)
            }

            // §16: the user's answer to an irreversible action. Delivered as a
            // service intent because the notification is the only surface
            // reachable while AXON is driving *another* app — which is exactly
            // when an irreversible action gets proposed.
            ACTION_APPROVE -> agent?.approvePending()
            ACTION_DENY -> agent?.denyPending()
        }

        startForeground(NOTIFICATION_ID, notification(getString(R.string.gateway_idle)))

        // NOT_STICKY: if the OS kills this, it must not silently come back. A
        // restarted agent the user did not ask for is exactly the covert
        // operation §16 forbids, so recovery is the user's decision.
        return START_NOT_STICKY
    }

    private fun startTask(utterance: String, config: RunConfig = RunConfig.DEFAULT) {
        if (currentTask?.isActive == true) {
            Log.w(TAG, "a task is already running; ignoring '$utterance'")
            return
        }

        // §16, guardrail 1, enforced rather than assumed.
        //
        // Since Android 13, POST_NOTIFICATIONS is a *runtime* permission, and a
        // foreground service whose notification is suppressed still runs — it
        // simply runs invisibly. That is exactly the covert operation §16
        // forbids, and an earlier build permitted it: the permission was declared
        // in the manifest, never requested, and the agent drove the device with
        // nothing on the notification bar.
        //
        // "Foreground service" therefore does not imply "visible" on modern
        // Android. The guarantee has to be checked, and the only honest response
        // to failing it is to refuse the work. AXON does not get to operate the
        // device on the user's behalf while hiding from them.
        if (!canBeSeen()) {
            Log.e(TAG, "refusing to run: notifications are blocked, so operation would be invisible")
            stopSelf()
            return
        }

        // §16 guardrail: irreversible actions are asked, not assumed.
        //
        // The gate was DENY and nothing replaced it, so AXON refused every call,
        // message and payment outright. That is the right way to fail with no UI
        // and it is not what §16 asks for, which is that the user be *asked*.
        agent?.let { a -> a.confirmationGate = a.interactiveGate(::askToConfirm) }

        currentTask = lifecycleScope.launch {
            val a = agent ?: return@launch

            // E22d. Ask the skill store *before* paying for the model.
            //
            // Loading the GGUF costs ~40 s on this hardware. Doing it
            // unconditionally meant a task a compiled skill could serve without
            // the model still waited for the model — so a run that genuinely
            // cost **zero model calls** cost a full model load anyway, and the
            // whole user-visible benefit of C1′ was thrown away here, at the app
            // layer, while the measurement inside the loop looked perfect.
            //
            // This is the difference between "replay is free" as a property of
            // the runtime and as something the person holding the phone
            // experiences.
            val goal = Goal(utterance, stepBudget = 6)
            // The cold arm declines to consult the store, so a matching skill no
            // longer means the model can be skipped — it means the opposite: the
            // run is about to plan something it already knows, which is the whole
            // measurement (E24c).
            val replayable = config.skillReplay && a.canReplay(goal)

            if (!replayable && !a.isModelLoaded) {
                update("loading model…")
                a.load().onFailure {
                    update("model failed to load")
                    return@launch
                }
            }

            update(if (replayable) "replaying: $utterance" else "running: $utterance")

            // The step budget is deliberately small for interactive use. At ~60 s
            // per planning step on this hardware, a generous budget means a task
            // that has gone wrong keeps going wrong for ten minutes while the
            // user watches. Better to escalate early and let them redirect.
            val result = a.run(goal, config = config)

            result
                .onSuccess { update("${it.outcome} — ${it.steps.size} steps, ${it.totalMs / 1000}s") }
                .onFailure { update("error: ${it.message}") }
        }
    }

    /**
     * Put an irreversible action in front of the user (§16).
     *
     * Replaces the ongoing notification with one carrying Approve and Deny, at
     * HIGH importance so it surfaces over whatever app AXON is driving. The
     * executor is suspended at this moment with the action one dispatch away, so
     * an unanswered prompt is a stalled task rather than a silent act — which is
     * the correct failure direction and the reason the gate blocks rather than
     * defaulting.
     *
     * The text names the *effect*, not the mechanism: "place a call to Ammi",
     * not "dispatch a tap on element 7". A confirmation the user cannot evaluate
     * is a dialog they will learn to dismiss.
     */
    private fun askToConfirm(reason: ConfirmationReason) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, confirmationNotification(reason))
    }

    private fun confirmationNotification(reason: ConfirmationReason): Notification {
        fun action(name: String) = PendingIntent.getService(
            this,
            name.hashCode(),
            Intent(this, AxonGatewayService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return notificationBuilder(this)
            .setContentTitle(getString(R.string.confirm_title))
            .setContentText(getString(R.string.confirm_text, reason.effect, reason.label))
            .setStyle(
                Notification.BigTextStyle().bigText(
                    getString(R.string.confirm_text, reason.effect, reason.label),
                ),
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_send, getString(R.string.confirm_allow), action(ACTION_APPROVE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.confirm_deny), action(ACTION_DENY))
            .build()
    }

    /**
     * Can the user actually see that AXON is running?
     *
     * `areNotificationsEnabled()` covers both the runtime permission and the
     * user having muted the channel. Either way the answer is the same: if
     * operation would be invisible, it does not happen.
     */
    private fun canBeSeen(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
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
        const val ACTION_APPROVE = "dev.axon.action.APPROVE"
        const val ACTION_DENY = "dev.axon.action.DENY"
        const val ACTION_STOP = "dev.axon.action.STOP"
        const val EXTRA_GOAL = "goal"

        /**
         * The §14.3 ablation arm, for measurement only (E24c).
         *
         * Absent means the shipping configuration. Nothing in the UI sets it and
         * nothing should: the arms exist so an experiment can turn one of AXON's
         * mechanisms off and measure the difference, not so a user can run a
         * degraded agent without being told they are.
         */
        const val EXTRA_CONFIG = "config"

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
