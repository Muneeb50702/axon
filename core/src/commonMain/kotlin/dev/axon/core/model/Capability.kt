package dev.axon.core.model

import kotlinx.serialization.Serializable

/**
 * A permission a skill must declare and the user must grant (spec §7.10, §16).
 *
 * Capabilities are strings of the form `domain:verb` — `contacts:read`,
 * `whatsapp:send`, `settings:write`, `shizuku:shell`. An open vocabulary is
 * deliberate: §7.7/O5 require that a new skill installs as a *folder* with no
 * core code change, and a closed enum would mean every new skill needing a new
 * capability had to edit core. The trade-off — typos become silent new
 * capabilities rather than compile errors — is bought back by [isWellFormed],
 * which the skill installer enforces at load time.
 */
@Serializable
@JvmInline
value class Capability(val id: String) {

    val domain: String get() = id.substringBefore(':')
    val verb: String get() = id.substringAfter(':', "")

    /**
     * `domain:verb`, both non-empty, lowercase, no spaces.
     *
     * Enforced when a skill folder is installed, so a manifest asking for
     * `Contacts Read` is rejected at install rather than quietly never matching
     * a granted capability and failing mid-task.
     */
    fun isWellFormed(): Boolean {
        val parts = id.split(':')
        if (parts.size != 2) return false
        return parts.all { p -> p.isNotEmpty() && p.none { it.isWhitespace() } && p == p.lowercase() }
    }

    override fun toString(): String = id

    companion object {
        val CONTACTS_READ = Capability("contacts:read")
        val SETTINGS_WRITE = Capability("settings:write")
        val SHIZUKU_SHELL = Capability("shizuku:shell")

        /** Dispatch touch gestures — the baseline every UI-driving skill needs. */
        val UI_GESTURE = Capability("ui:gesture")

        /** Read the accessibility tree. */
        val UI_OBSERVE = Capability("ui:observe")

        /** Bring an app to the foreground. */
        val APP_LAUNCH = Capability("app:launch")

        /**
         * Capabilities that may cause irreversible, outward-facing effects and
         * therefore always require an explicit per-invocation confirmation from
         * the user, even when already granted (§16: "destructive/irreversible
         * actions require explicit confirmation").
         *
         * A standing grant means "you may do this kind of thing"; it does not
         * mean "send this particular message to this particular person without
         * showing me". The two are separated on purpose.
         */
        val ALWAYS_CONFIRM: Set<String> = setOf(
            "send", "pay", "delete", "post", "call", "purchase",
        )
    }
}

/** Does this capability need per-invocation confirmation regardless of grant? */
fun Capability.requiresConfirmation(): Boolean =
    verb in Capability.ALWAYS_CONFIRM

/**
 * Thermal headroom, as reported by the platform (spec §9.3, §7.3).
 *
 * Instrumented from Phase 1 rather than bolted on later, per §7.3: "demos run
 * 10s, real tasks run minutes". A benchmark run that silently crossed into
 * throttling produces latency numbers that mean nothing, so §14.2 makes thermal
 * ceiling a reported metric and the runtime degrades deliberately rather than
 * letting the OS do it unpredictably.
 *
 * Ordinals mirror Android's `PowerManager.THERMAL_STATUS_*` so the Android
 * implementation is a direct mapping.
 */
@Serializable
enum class ThermalState {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN;

    /**
     * Whether the heavy planner model may be invoked at this thermal level.
     *
     * At SEVERE and above AXON stops issuing planner calls and prefers compiled
     * skills, which need no model at all — the C1 replay path is not only faster,
     * it is the graceful-degradation story when the phone is hot.
     */
    val allowsHeavyInference: Boolean get() = ordinal < SEVERE.ordinal

    /** Below this the device is too hot to run the agent at all. */
    val allowsAnyWork: Boolean get() = ordinal < CRITICAL.ordinal
}
