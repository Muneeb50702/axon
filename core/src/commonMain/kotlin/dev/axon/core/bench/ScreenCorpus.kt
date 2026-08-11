package dev.axon.core.bench

import dev.axon.core.model.Bounds
import dev.axon.core.model.CompactState
import dev.axon.core.model.Goal
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree

/**
 * Synthetic screens for measuring the planner without a live device
 * (spec §13 Phase 1 acceptance, §14).
 *
 * ## Why this lives in `commonMain` rather than a test source set
 *
 * It is not a test fixture, it is a research artefact. Phase 1's acceptance
 * criterion — *"500 generations → 100% schema-valid actions"* — needs 500
 * distinct prompts, and the §14.3 ablation needs the *same* prompts across all
 * five configs or the arms are not comparable. Shipping the corpus in the main
 * module means the instrumented test, the benchmark harness and anyone
 * reproducing the results all draw from one source rather than three
 * approximations of it.
 *
 * ## What these screens are, and what they are not
 *
 * Each entry is a hand-written approximation of a real Android screen, in the
 * shape an accessibility tree actually produces: labels that are terse and
 * sometimes ambiguous, content descriptions that are missing as often as not,
 * decoration mixed in with controls.
 *
 * They are **not** a substitute for real app traversal, and the thesis should
 * not present them as one. Their job is narrow and worth stating precisely: to
 * measure whether the model emits *structurally valid, screen-grounded* actions
 * — which is exactly what C3 claims and exactly what does not require a live
 * device. Whether the chosen action actually accomplishes the task on a real
 * phone is a Phase 3 question, answered on hardware.
 *
 * The payoff of drawing that line is reproducibility: this half of the
 * evaluation runs identically on any machine, so an examiner can re-run it
 * without owning the handset.
 */
public object ScreenCorpus {

    /** One (screen, goal) pair the planner must act on. */
    public data class Case(
        val id: String,
        val goal: Goal,
        val tree: UiTree,

        /**
         * Labels that would be a sensible target here.
         *
         * Not a single gold answer — several actions are often reasonable on a
         * screen, and scoring against one would measure agreement with the
         * author rather than competence. Used for a weaker, honest check:
         * did the model aim at *something that exists*? Hallucinated targets
         * are the failure mode the precondition gate (§7.5) exists to catch,
         * and this is where their rate is measured.
         */
        public val plausibleTargets: Set<String> = emptySet(),
    ) {
        val state: CompactState get() = CompactState.from(tree)
    }

    /** Every case, in stable order so runs are comparable across configs. */
    public val ALL: List<Case> by lazy {
        listOf(
            launcher(), whatsappChatList(), whatsappConversation(), whatsappSearch(),
            clockAlarmList(), clockTimePicker(), settingsRoot(), settingsWifi(),
            dialerKeypad(), dialerContacts(), mapsHome(), mapsSearchResult(),
            permissionDialog(), loadingScreen(), messagesInbox(), calendarMonth(),
            calendarNewEvent(), settingsBluetooth(), whatsappAttachSheet(), emptyCanvas(),
            settingsSearch(), contactDetail(), clockAlarmLabel(), galleryGrid(),
        )
    }

    /**
     * Cycle the corpus to reach [n] prompts.
     *
     * The acceptance criterion is 500 generations against ~24 distinct screens,
     * so screens repeat. That is acceptable *because of what is being measured*:
     * valid-action rate is a property of the decoder, and repetition with a
     * changing sampler seed still exercises it. It would not be acceptable for a
     * task-success measurement, where repeats would inflate the number.
     */
    public fun sample(n: Int): List<Case> =
        List(n) { i -> ALL[i % ALL.size] }

    // ---------------------------------------------------------------------
    // Screens
    // ---------------------------------------------------------------------

    private fun launcher() = Case(
        id = "launcher",
        goal = Goal("send a message to Ammi on whatsapp"),
        tree = tree("com.android.launcher3", "Home") {
            icon(0, "Phone"); icon(1, "Messages"); icon(2, "WhatsApp")
            icon(3, "Camera"); icon(4, "Settings"); icon(5, "Clock")
            icon(6, "Maps"); icon(7, "Gallery")
        },
        plausibleTargets = setOf("WhatsApp"),
    )

    private fun whatsappChatList() = Case(
        id = "whatsapp_chat_list",
        goal = Goal("send \"on my way\" to Ammi on whatsapp"),
        tree = tree("com.whatsapp", "WhatsApp") {
            button(0, "Search", desc = "Search")
            button(1, "More options", desc = "More options")
            row(2, "Ammi")
            row(3, "Baba")
            row(4, "Class Group 2026")
            row(5, "Hira")
            button(6, "New chat", desc = "New chat")
        },
        plausibleTargets = setOf("Ammi", "Search"),
    )

    private fun whatsappConversation() = Case(
        id = "whatsapp_conversation",
        goal = Goal("send \"on my way\" to Ammi on whatsapp", mapOf("message" to "on my way")),
        tree = tree("com.whatsapp", "Ammi") {
            button(0, "Back", desc = "Navigate up")
            text(1, "Ammi")
            button(2, "Video call", desc = "Video call")
            button(3, "Call", desc = "Call")
            editable(4, hint = "Message", desc = "Message")
            button(5, "Attach", desc = "Attach")
            button(6, "Camera", desc = "Camera")
            button(7, "Voice message", desc = "Voice message")
        },
        plausibleTargets = setOf("Message"),
    )

    private fun whatsappSearch() = Case(
        id = "whatsapp_search",
        goal = Goal("send a message to Hira on whatsapp"),
        tree = tree("com.whatsapp", "Search") {
            button(0, "Back", desc = "Navigate up")
            editable(1, hint = "Search…", desc = "Search")
            row(2, "Hira")
            row(3, "Hiring Updates")
        },
        plausibleTargets = setOf("Search", "Hira"),
    )

    private fun whatsappAttachSheet() = Case(
        id = "whatsapp_attach_sheet",
        goal = Goal("send my location to Ammi on whatsapp"),
        tree = tree("com.whatsapp", "Ammi") {
            button(0, "Document", desc = "Document")
            button(1, "Camera", desc = "Camera")
            button(2, "Gallery", desc = "Gallery")
            button(3, "Audio", desc = "Audio")
            button(4, "Location", desc = "Location")
            button(5, "Contact", desc = "Contact")
        },
        plausibleTargets = setOf("Location"),
    )

    private fun clockAlarmList() = Case(
        id = "clock_alarm_list",
        goal = Goal("set an alarm for 6:30 am", mapOf("time" to "6:30 am")),
        tree = tree("com.google.android.deskclock", "Alarm") {
            tab(0, "Alarm"); tab(1, "Clock"); tab(2, "Timer"); tab(3, "Stopwatch")
            toggleRow(4, "7:00 AM", on = true)
            toggleRow(5, "8:15 AM", on = false)
            button(6, "Add alarm", desc = "Add alarm")
        },
        plausibleTargets = setOf("Add alarm"),
    )

    private fun clockTimePicker() = Case(
        id = "clock_time_picker",
        goal = Goal("set an alarm for 6:30 am", mapOf("time" to "6:30 am")),
        tree = tree("com.google.android.deskclock", "Set alarm time") {
            editable(0, hint = "Hour", desc = "Hour")
            editable(1, hint = "Minute", desc = "Minute")
            button(2, "AM", desc = "AM")
            button(3, "PM", desc = "PM")
            button(4, "Cancel", desc = "Cancel")
            button(5, "OK", desc = "OK")
        },
        plausibleTargets = setOf("Hour", "Minute", "AM", "OK"),
    )

    private fun clockAlarmLabel() = Case(
        id = "clock_alarm_label",
        goal = Goal("label the alarm \"medicine\"", mapOf("label" to "medicine")),
        tree = tree("com.google.android.deskclock", "Alarm") {
            editable(0, hint = "Add label", desc = "Add label")
            button(1, "Cancel", desc = "Cancel")
            button(2, "OK", desc = "OK")
        },
        plausibleTargets = setOf("Add label"),
    )

    private fun settingsRoot() = Case(
        id = "settings_root",
        goal = Goal("turn on wifi", mapOf("setting" to "wifi", "state" to "on")),
        tree = tree("com.android.settings", "Settings") {
            editable(0, hint = "Search settings", desc = "Search settings")
            row(1, "Network & internet")
            row(2, "Connected devices")
            row(3, "Apps")
            row(4, "Notifications")
            row(5, "Battery")
            row(6, "Display")
            row(7, "Sound & vibration")
        },
        plausibleTargets = setOf("Network & internet", "Search settings"),
    )

    private fun settingsWifi() = Case(
        id = "settings_wifi",
        goal = Goal("turn on wifi", mapOf("setting" to "wifi", "state" to "on")),
        tree = tree("com.android.settings", "Network & internet") {
            toggleRow(0, "Wi-Fi", on = false)
            toggleRow(1, "Mobile data", on = true)
            row(2, "Airplane mode")
            row(3, "Hotspot & tethering")
        },
        plausibleTargets = setOf("Wi-Fi"),
    )

    private fun settingsBluetooth() = Case(
        id = "settings_bluetooth",
        goal = Goal("turn off bluetooth", mapOf("setting" to "bluetooth", "state" to "off")),
        tree = tree("com.android.settings", "Connected devices") {
            toggleRow(0, "Bluetooth", on = true)
            row(1, "Pair new device")
            row(2, "Previously connected devices")
        },
        plausibleTargets = setOf("Bluetooth"),
    )

    private fun settingsSearch() = Case(
        id = "settings_search",
        goal = Goal("turn on dark theme", mapOf("setting" to "dark theme", "state" to "on")),
        tree = tree("com.android.settings", "Search settings") {
            editable(0, hint = "Search settings", desc = "Search settings")
            button(1, "Back", desc = "Navigate up")
        },
        plausibleTargets = setOf("Search settings"),
    )

    private fun dialerKeypad() = Case(
        id = "dialer_keypad",
        goal = Goal("call Baba", mapOf("contact" to "Baba")),
        tree = tree("com.google.android.dialer", "Phone") {
            tab(0, "Favourites"); tab(1, "Recents"); tab(2, "Contacts")
            editable(3, hint = "Search contacts", desc = "Search contacts")
            button(4, "Keypad", desc = "Keypad")
        },
        plausibleTargets = setOf("Contacts", "Search contacts"),
    )

    private fun dialerContacts() = Case(
        id = "dialer_contacts",
        goal = Goal("call Baba", mapOf("contact" to "Baba")),
        tree = tree("com.google.android.dialer", "Contacts") {
            editable(0, hint = "Search contacts", desc = "Search contacts")
            row(1, "Ammi")
            row(2, "Baba")
            row(3, "Bilal")
            row(4, "Doctor Sahib")
        },
        plausibleTargets = setOf("Baba"),
    )

    private fun contactDetail() = Case(
        id = "contact_detail",
        goal = Goal("call Baba", mapOf("contact" to "Baba")),
        tree = tree("com.google.android.contacts", "Baba") {
            button(0, "Back", desc = "Navigate up")
            text(1, "Baba")
            button(2, "Call", desc = "Call")
            button(3, "Text", desc = "Text")
            button(4, "Video", desc = "Video")
            row(5, "+92 300 1234567")
        },
        plausibleTargets = setOf("Call"),
    )

    private fun mapsHome() = Case(
        id = "maps_home",
        goal = Goal("navigate to Liberty Market", mapOf("place" to "Liberty Market")),
        tree = tree("com.google.android.apps.maps", "Maps") {
            editable(0, hint = "Search here", desc = "Search here")
            button(1, "Your location", desc = "Your location")
            tab(2, "Explore"); tab(3, "Go"); tab(4, "Saved")
        },
        plausibleTargets = setOf("Search here"),
    )

    private fun mapsSearchResult() = Case(
        id = "maps_search_result",
        goal = Goal("navigate to Liberty Market", mapOf("place" to "Liberty Market")),
        tree = tree("com.google.android.apps.maps", "Liberty Market") {
            text(0, "Liberty Market")
            text(1, "Shopping mall · Gulberg")
            button(2, "Directions", desc = "Directions")
            button(3, "Start", desc = "Start")
            button(4, "Save", desc = "Save")
            button(5, "Share", desc = "Share")
        },
        plausibleTargets = setOf("Directions", "Start"),
    )

    private fun messagesInbox() = Case(
        id = "messages_inbox",
        goal = Goal("text Hira that I will be late"),
        tree = tree("com.google.android.apps.messaging", "Messages") {
            editable(0, hint = "Search conversations", desc = "Search conversations")
            row(1, "Hira")
            row(2, "Bank Alerts")
            row(3, "Jazz")
            button(4, "Start chat", desc = "Start chat")
        },
        plausibleTargets = setOf("Hira", "Start chat"),
    )

    private fun calendarMonth() = Case(
        id = "calendar_month",
        goal = Goal("add a calendar event for Friday"),
        tree = tree("com.google.android.calendar", "August 2026") {
            button(0, "Menu", desc = "Show Calendar List and Settings drawer")
            button(1, "Search", desc = "Search")
            button(2, "Today", desc = "Today")
            button(3, "Create new event", desc = "Create new event and more")
        },
        plausibleTargets = setOf("Create new event and more"),
    )

    private fun calendarNewEvent() = Case(
        id = "calendar_new_event",
        goal = Goal("add a calendar event called Viva"),
        tree = tree("com.google.android.calendar", "New event") {
            editable(0, hint = "Add title", desc = "Add title")
            row(1, "All-day")
            row(2, "Fri, 14 Aug")
            button(3, "Save", desc = "Save")
            button(4, "Close", desc = "Close")
        },
        plausibleTargets = setOf("Add title"),
    )

    private fun galleryGrid() = Case(
        id = "gallery_grid",
        goal = Goal("share the latest photo with Ammi"),
        tree = tree("com.google.android.apps.photos", "Photos") {
            button(0, "Photo taken on Aug 11", desc = "Photo taken on Aug 11")
            button(1, "Photo taken on Aug 10", desc = "Photo taken on Aug 10")
            button(2, "Photo taken on Aug 9", desc = "Photo taken on Aug 9")
            tab(3, "Photos"); tab(4, "Search"); tab(5, "Library")
        },
        plausibleTargets = setOf("Photo taken on Aug 11"),
    )

    /**
     * A runtime permission dialog.
     *
     * Present because §14.1 lists it as a robustness perturbation, and because it
     * is the screen where a naive agent does the most damage — the most
     * "helpful" action is to tap Allow, which is precisely the decision §16
     * reserves for the user. What the planner does here is worth measuring
     * separately from ordinary navigation.
     */
    private fun permissionDialog() = Case(
        id = "permission_dialog",
        goal = Goal("send \"on my way\" to Ammi on whatsapp"),
        tree = tree("com.google.android.permissioncontroller", "Allow WhatsApp to access your contacts?") {
            text(0, "Allow WhatsApp to access your contacts?")
            button(1, "Allow", desc = "Allow")
            button(2, "Don't allow", desc = "Don't allow")
        },
        plausibleTargets = setOf("Allow", "Don't allow"),
    )

    /** A screen still loading — the case `wait` exists for (§10.1). */
    private fun loadingScreen() = Case(
        id = "loading",
        goal = Goal("navigate to Liberty Market", mapOf("place" to "Liberty Market")),
        tree = tree("com.google.android.apps.maps", null) {
            text(0, "Loading…")
        },
        plausibleTargets = emptySet(),
    )

    /**
     * An empty accessibility tree — a canvas, game or DRM-protected surface.
     *
     * §17 names this as a Med/Med risk with a screenshot+VLM fallback as the
     * mitigation. Until that exists, the correct behaviour is to press back or
     * relaunch rather than to invent an element, and this case is how that gets
     * measured instead of assumed.
     */
    private fun emptyCanvas() = Case(
        id = "empty_canvas",
        goal = Goal("set an alarm for 6:30 am", mapOf("time" to "6:30 am")),
        tree = UiTree.empty("com.example.game", capturedAtMs = 0),
        plausibleTargets = emptySet(),
    )

    // ---------------------------------------------------------------------
    // Tiny DSL — keeps the screens above readable as screens
    // ---------------------------------------------------------------------

    private class Builder(val pkg: String, val title: String?) {
        val nodes = mutableListOf<UiNode>()
        private var y = 100

        private fun bounds(): Bounds {
            val b = Bounds(48, y, 1032, y + 140)
            y += 150
            return b
        }

        fun button(i: Int, label: String, desc: String? = null) {
            nodes += UiNode(i, "button", text = label, contentDescription = desc,
                bounds = bounds(), clickable = true)
        }

        fun icon(i: Int, label: String) {
            nodes += UiNode(i, "button", text = label, contentDescription = label,
                bounds = bounds(), clickable = true)
        }

        fun row(i: Int, label: String) {
            nodes += UiNode(i, "list_item", text = label, bounds = bounds(), clickable = true)
        }

        fun tab(i: Int, label: String) {
            nodes += UiNode(i, "tab", text = label, contentDescription = label,
                bounds = bounds(), clickable = true)
        }

        fun toggleRow(i: Int, label: String, on: Boolean) {
            nodes += UiNode(i, "switch", text = label, contentDescription = label,
                bounds = bounds(), clickable = true, checked = on)
        }

        fun editable(i: Int, hint: String, desc: String? = null) {
            nodes += UiNode(i, "edit_text", text = null, contentDescription = desc ?: hint,
                bounds = bounds(), clickable = true, editable = true)
        }

        /** Non-interactive label. Present so pruning (§10.2) has something to prune. */
        fun text(i: Int, label: String) {
            nodes += UiNode(i, "text", text = label, bounds = bounds())
        }
    }

    private fun tree(pkg: String, title: String?, build: Builder.() -> Unit): UiTree {
        val b = Builder(pkg, title).apply(build)
        return UiTree(
            foregroundPackage = pkg,
            screenTitle = title,
            nodes = b.nodes,
            capturedAtMs = 0,
        )
    }
}
