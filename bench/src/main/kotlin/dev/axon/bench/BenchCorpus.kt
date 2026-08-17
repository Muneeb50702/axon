package dev.axon.bench

import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType.APP_FOREGROUND
import dev.axon.core.model.PostConditionType.NODE_ABSENT
import dev.axon.core.model.PostConditionType.NODE_PRESENT
import dev.axon.core.model.PostConditionType.TEXT_MATCHES

/**
 * **AXON-Bench** — the §14.1 task corpus. Contribution **C5**.
 *
 * Twenty tasks in three tiers: ten core, five robustness variants, five
 * long-horizon. Each ships an initial condition, a goal phrased the way a person
 * would say it, and a machine-checkable success oracle.
 *
 * ## Why these tasks
 *
 * §14.1 names the categories — message, call, alarm, navigation, settings,
 * calendar. The specific choices here follow §2.1's user: someone in South Asia
 * on a sub-$150 handset, for whom WhatsApp is the messaging layer and data is
 * expensive. That is not decoration. A benchmark of Gmail and Google Calendar
 * flows would measure an agent on apps this user does not open, and the tasks
 * that actually matter — WhatsApp, the dialer, the alarm clock, mobile-data
 * toggles — are also the ones with the least forgiving UIs.
 *
 * Two tasks are deliberately *read-only* (`battery_level`, `find_setting`).
 * An agent benchmark composed entirely of state changes cannot distinguish
 * "navigated correctly" from "changed something and got lucky", and read-only
 * tasks have oracles that cannot be satisfied by accident.
 *
 * ## What the oracles can and cannot express
 *
 * Every oracle is a [PostCondition] — the same type the planner emits and the
 * verifier evaluates (see [BenchTask]). That reuse is load-bearing and it is
 * also the corpus's main limitation: an oracle can only assert things visible in
 * the accessibility tree. "The alarm is set for 7am" is checked by the alarm
 * appearing in the list, not by reading the system alarm database. A task that
 * navigated to the right screen and *displayed* the right text without
 * committing the change would pass.
 *
 * That is stated rather than engineered around, because the alternative —
 * per-task native assertions against app databases — needs privileged access
 * AXON deliberately does not have (D7, §6.3), and a benchmark that required more
 * permission than the system under test would be measuring something else.
 *
 * ## Optimal step counts are hand-counted, and that is a judgement
 *
 * `optimalSteps` is the denominator of §14.2's step efficiency, and there is no
 * oracle for it — it is what a competent person needs, counted by hand on the
 * target device. Where the count is arguable the comment says so. Reviewers
 * should treat step efficiency as the softest metric in the table for exactly
 * this reason; TSR and LLM-calls-per-task have no such judgement in them.
 */
public object BenchCorpus {

    // ---------------------------------------------------------------- core --

    /**
     * §14.1's core tier: ten single-intent tasks.
     *
     * Ordered roughly by the number of decisions each requires, so a config that
     * collapses partway through the tier fails on the harder tasks rather than
     * at random — which makes a partial run still informative (E6: the OEM power
     * manager will terminate long runs, so partial runs are the normal case).
     */
    public val CORE: List<BenchTask> = listOf(
        BenchTask(
            id = "open_whatsapp",
            tier = BenchTier.CORE,
            goal = "open whatsapp",
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(PostCondition(APP_FOREGROUND, "com.whatsapp")),
            // One action: launch_app. E21 collapses the grammar to exactly this,
            // so the task doubles as a regression test for that mechanism —
            // anything above 1 step means the collapse did not fire (E21c).
            optimalSteps = 1,
            requiredApps = listOf("com.whatsapp"),
        ),

        BenchTask(
            id = "battery_level",
            tier = BenchTier.CORE,
            goal = "show me the battery percentage",
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(
                PostCondition(APP_FOREGROUND, "com.android.settings"),
                // Any percentage. Asserting a specific one would make the task
                // fail as the battery drains during a benchmark run.
                PostCondition(TEXT_MATCHES, "[0-9]+%"),
            ),
            // Settings → Battery. Read-only: nothing can be changed by accident,
            // so a pass means the agent navigated rather than got lucky.
            optimalSteps = 2,
        ),

        BenchTask(
            id = "open_camera",
            tier = BenchTier.CORE,
            goal = "open the camera",
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(PostCondition(TEXT_MATCHES, "camera")),
            optimalSteps = 1,
        ),

        BenchTask(
            id = "toggle_wifi",
            tier = BenchTier.CORE,
            goal = "turn on wifi",
            initialCondition = InitialCondition(
                setup = listOf("Start on the home screen", "Wi-Fi OFF"),
            ),
            successOracle = listOf(
                PostCondition(APP_FOREGROUND, "com.android.settings"),
                PostCondition(NODE_PRESENT, "Wi-Fi"),
            ),
            intermediateConditions = listOf(PostCondition(APP_FOREGROUND, "com.android.settings")),
            // Settings → Network → Wi-Fi → toggle. Arguable: the quick-settings
            // tile is one gesture, but it is not reachable through the
            // accessibility tree the same way, so the count reflects the route
            // the agent can actually take.
            optimalSteps = 4,
        ),

        BenchTask(
            id = "find_setting",
            tier = BenchTier.CORE,
            goal = "find the display settings",
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(
                PostCondition(APP_FOREGROUND, "com.android.settings"),
                PostCondition(TEXT_MATCHES, "brightness|display"),
            ),
            // Read-only, and specifically tests search-then-navigate rather than
            // memorised menu position — the thing that breaks across OEM skins.
            optimalSteps = 3,
        ),

        BenchTask(
            id = "set_alarm",
            tier = BenchTier.CORE,
            goal = "set an alarm for 7 am",
            params = mapOf("time" to "7:00 AM"),
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(
                PostCondition(TEXT_MATCHES, "7:00|7 ?am"),
                // The dialog must be gone: a time picker showing 7:00 is not a
                // set alarm, and this is the clearest case where NODE_ABSENT
                // catches a task that "looks" finished.
                PostCondition(NODE_ABSENT, "Cancel"),
            ),
            intermediateConditions = listOf(PostCondition(TEXT_MATCHES, "alarm")),
            // Clock → Alarm tab → add → set hour → set minute → save.
            optimalSteps = 6,
        ),

        BenchTask(
            id = "add_calendar_event",
            tier = BenchTier.CORE,
            goal = "add a calendar event called dentist tomorrow",
            params = mapOf("title" to "dentist"),
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(PostCondition(TEXT_MATCHES, "dentist")),
            intermediateConditions = listOf(PostCondition(TEXT_MATCHES, "calendar|event")),
            optimalSteps = 5,
        ),

        BenchTask(
            id = "navigate_to_place",
            tier = BenchTier.CORE,
            goal = "navigate to Liberty Market",
            params = mapOf("place" to "Liberty Market"),
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(
                PostCondition(APP_FOREGROUND, "com.google.android.apps.maps"),
                PostCondition(TEXT_MATCHES, "liberty"),
            ),
            optimalSteps = 4,
            requiredApps = listOf("com.google.android.apps.maps"),
        ),

        BenchTask(
            id = "whatsapp_send_message",
            tier = BenchTier.CORE,
            goal = "send on my way to Ammi on whatsapp",
            params = mapOf("contact" to "Ammi", "message" to "on my way"),
            initialCondition = InitialCondition(
                setup = listOf("Start on the home screen", "A contact named Ammi exists"),
            ),
            successOracle = listOf(
                PostCondition(APP_FOREGROUND, "com.whatsapp"),
                PostCondition(NODE_PRESENT, "on my way"),
            ),
            intermediateConditions = listOf(PostCondition(APP_FOREGROUND, "com.whatsapp")),
            // launch → search → pick contact → focus box → type → send.
            optimalSteps = 6,
            requiredApps = listOf("com.whatsapp"),
            // §16: sending is irreversible. Scored only with the gate opened,
            // and the results table must say so.
            requiresConfirmation = true,
        ),

        BenchTask(
            id = "call_contact",
            tier = BenchTier.CORE,
            goal = "call Ammi",
            params = mapOf("contact" to "Ammi"),
            initialCondition = InitialCondition(
                setup = listOf("Start on the home screen", "A contact named Ammi exists"),
            ),
            successOracle = listOf(PostCondition(TEXT_MATCHES, "ammi")),
            intermediateConditions = listOf(PostCondition(TEXT_MATCHES, "dial|contact|phone")),
            optimalSteps = 4,
            // §16: placing a call is irreversible and costs money.
            requiresConfirmation = true,
        ),
    )

    // ---------------------------------------------------------- robustness --

    /**
     * §14.1's robustness tier: the same tasks, perturbed.
     *
     * Each variant shares its parent's oracle and differs **only** in the
     * perturbation, so a drop between the two is attributable to the
     * perturbation rather than to a differently-written task. That is why these
     * are derived from [CORE] by copy rather than written out.
     */
    public val ROBUSTNESS: List<BenchTask> = listOf(
        core("open_whatsapp").perturbed(
            "open_whatsapp_from_settings",
            Perturbation.DIFFERENT_START_SCREEN,
            listOf("Start with Settings in the foreground, not the launcher"),
        ),
        core("toggle_wifi").perturbed(
            "toggle_wifi_from_whatsapp",
            Perturbation.DIFFERENT_START_SCREEN,
            listOf("Start with WhatsApp in the foreground"),
        ),
        core("set_alarm").perturbed(
            "set_alarm_with_notification",
            Perturbation.INTERVENING_NOTIFICATION,
            listOf("Fire a notification once the clock app is open"),
        ),
        core("whatsapp_send_message").perturbed(
            "whatsapp_send_with_permission_dialog",
            Perturbation.PERMISSION_DIALOG,
            listOf("Revoke WhatsApp's contacts permission so a dialog interrupts"),
        ),
        // The variant that probes C1 most directly: a compiled skill's
        // assertions should fail here, the per-step fallback should repair the
        // diverged step, and the skill should re-compile. §17 calls drift
        // something to recover from, so the benchmark has to contain the case.
        core("open_whatsapp").perturbed(
            "open_whatsapp_layout_variant",
            Perturbation.LAYOUT_VARIANT,
            listOf("Move WhatsApp to a different home-screen page or folder"),
        ),
    )

    // -------------------------------------------------------- long-horizon --

    /**
     * §14.1's long-horizon tier: five compositions of six or more steps.
     *
     * The tier C1′ exists for. At ~60 s per planning step (E2), a nine-step task
     * is a nine-minute cold run — past the ~7-minute window the OEM power
     * manager allows (E6). **Some of these tasks are not completable by the PLAN
     * path on this hardware at all**, which is not a flaw in the benchmark but
     * the finding it is designed to expose: compilation is what makes them
     * finish, and that is the difference between an optimisation and a viability
     * mechanism.
     */
    public val LONG_HORIZON: List<BenchTask> = listOf(
        BenchTask(
            id = "lh_message_then_alarm",
            tier = BenchTier.LONG_HORIZON,
            goal = "message Ammi that I'll be late and set an alarm for 6 am",
            params = mapOf("contact" to "Ammi", "message" to "I'll be late", "time" to "6:00 AM"),
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(
                PostCondition(NODE_PRESENT, "I'll be late"),
                PostCondition(TEXT_MATCHES, "6:00|6 ?am"),
            ),
            intermediateConditions = listOf(PostCondition(APP_FOREGROUND, "com.whatsapp")),
            optimalSteps = 11,
            requiredApps = listOf("com.whatsapp"),
            requiresConfirmation = true,
        ),
        BenchTask(
            id = "lh_wifi_then_message",
            tier = BenchTier.LONG_HORIZON,
            goal = "turn on wifi then tell Ammi on whatsapp that I'm online",
            params = mapOf("contact" to "Ammi", "message" to "I'm online"),
            initialCondition = InitialCondition(setup = listOf("Start on the home screen", "Wi-Fi OFF")),
            successOracle = listOf(
                PostCondition(APP_FOREGROUND, "com.whatsapp"),
                PostCondition(NODE_PRESENT, "I'm online"),
            ),
            optimalSteps = 10,
            requiredApps = listOf("com.whatsapp"),
            requiresConfirmation = true,
        ),
        BenchTask(
            id = "lh_find_then_remind",
            tier = BenchTier.LONG_HORIZON,
            // §14.1's own worked example.
            goal = "find Ammi's last message and set an alarm an hour from now about it",
            params = mapOf("contact" to "Ammi"),
            initialCondition = InitialCondition(
                setup = listOf("Start on the home screen", "Ammi has sent at least one message"),
            ),
            successOracle = listOf(PostCondition(TEXT_MATCHES, "alarm")),
            intermediateConditions = listOf(PostCondition(APP_FOREGROUND, "com.whatsapp")),
            optimalSteps = 9,
            requiredApps = listOf("com.whatsapp"),
        ),
        BenchTask(
            id = "lh_calendar_then_navigate",
            tier = BenchTier.LONG_HORIZON,
            goal = "add a calendar event called clinic and then navigate to Liberty Market",
            params = mapOf("title" to "clinic", "place" to "Liberty Market"),
            initialCondition = InitialCondition(setup = listOf("Start on the home screen")),
            successOracle = listOf(
                PostCondition(APP_FOREGROUND, "com.google.android.apps.maps"),
                PostCondition(TEXT_MATCHES, "liberty"),
            ),
            intermediateConditions = listOf(PostCondition(TEXT_MATCHES, "clinic")),
            optimalSteps = 9,
            requiredApps = listOf("com.google.android.apps.maps"),
        ),
        BenchTask(
            id = "lh_settings_tour",
            tier = BenchTier.LONG_HORIZON,
            goal = "turn on wifi, then check the battery percentage, then go back home",
            initialCondition = InitialCondition(setup = listOf("Start on the home screen", "Wi-Fi OFF")),
            successOracle = listOf(
                // Ends at the launcher: the only task whose success is a *return*
                // to where it started, which catches an agent that completes the
                // work and abandons the user three screens deep.
                PostCondition(NODE_ABSENT, "Wi-Fi"),
            ),
            intermediateConditions = listOf(
                PostCondition(APP_FOREGROUND, "com.android.settings"),
                PostCondition(TEXT_MATCHES, "[0-9]+%"),
            ),
            optimalSteps = 8,
        ),
    )

    /** The whole benchmark, in tier order. */
    public val ALL: List<BenchTask> = CORE + ROBUSTNESS + LONG_HORIZON

    /**
     * Tasks runnable without opening §16's confirmation gate.
     *
     * The subset that measures the system users actually get. Reporting only
     * `ALL` would overstate what AXON does unattended; reporting only this would
     * understate what it can do. Both belong in the table, labelled.
     */
    public val UNGATED: List<BenchTask> = ALL.filterNot { it.requiresConfirmation }

    public fun byId(id: String): BenchTask =
        ALL.firstOrNull { it.id == id } ?: error("no bench task '$id'")

    private fun core(id: String) = CORE.first { it.id == id }

    /** Same task, same oracle, one perturbation. */
    private fun BenchTask.perturbed(
        newId: String,
        how: Perturbation,
        setup: List<String>,
    ) = copy(
        id = newId,
        tier = BenchTier.ROBUSTNESS,
        perturbation = how,
        initialCondition = initialCondition.copy(setup = initialCondition.setup + setup),
    )
}
