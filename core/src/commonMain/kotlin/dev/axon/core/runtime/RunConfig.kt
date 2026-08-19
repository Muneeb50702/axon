package dev.axon.core.runtime

/**
 * Which of AXON's own mechanisms are active for one run (§14.3, E24c).
 *
 * ## Why this has to exist
 *
 * Every number that says a mechanism *helps* is a comparison against the same
 * system with that mechanism off. §14.3's table is five such comparisons, and
 * the trace schema has carried a `config` column since Phase 5 to hold the
 * answer — but nothing ever wrote anything but the default, because there was no
 * way to ask the runtime for a different arm. The comparison was expressible in
 * storage and unreachable from the app.
 *
 * E24c is the case that forced it. E24b measured a compound goal composed from
 * two known skills at **0 model calls in 4.4 s**. The obvious question — what
 * does the *same* goal cost with no skills to compose from — had only bad
 * answers available:
 *
 * - delete the two skills, and the user loses what the phone spent ~4 minutes of
 *   planning to learn, to answer one question;
 * - swap the database underneath a live SQLite connection, which is what an
 *   earlier attempt did, and it corrupted the app's state badly enough that the
 *   run was voided and the store restored from backup;
 * - use a *different* compound goal, which answers a different question, because
 *   the cold and composed arms would then differ in the goal as well as the path.
 *
 * All three are methodology failures. The fix is to make the arm a property of
 * the *request* rather than of the stored state: the skills stay exactly where
 * they are, and one run is told not to consult them.
 *
 * ## All three switches, each with a device path
 *
 * §14.3 switches three things — grammar, verifier, skill replay — and this
 * declares all three. It began with **only** `skillReplay`, deliberately, because
 * a config field that reads as a working switch and silently changes nothing is
 * E29's bug wearing a disguise: there, three §16 safety claims were false
 * precisely because a mechanism existed with no path from it to the user.
 *
 * The other two were added when their paths were built, not before:
 *
 * | switch | how it is applied | arms |
 * |---|---|---|
 * | `grammar` | `ConstrainedPlanner(constrained = false)` | A |
 * | `verifier` | `DefaultExecutor(verify = false)` | A, B |
 * | `skillReplay` | COMPOSE and REPLAY skipped in `AxonRuntime` | A, B, C |
 *
 * The grammar previously had a device path only through the instrumented
 * acceptance test behind E4/E4b, which measures *generations* rather than tasks;
 * the verifier had none at all. So arms A and B existed in the matrix and could
 * not be run over the corpus — the row was there and unreachable, the same shape
 * as E24c's cold arm before this file existed.
 */
public data class RunConfig(
    /**
     * The §14.3 arm id, recorded on every trace this run produces.
     *
     * Not decoration. It is what lets one database hold E24c's cold trace next to
     * the ordinary ones and still be grouped apart afterwards — which is what
     * `trace.config` was added for, and what would otherwise require the runs to
     * be separated by a directory convention the reader has to take on trust.
     */
    public val id: String = "D",

    /**
     * Consult the skill store at all — both COMPOSE and REPLAY.
     *
     * `false` is the cold path: the run plans from scratch even for a goal a
     * compiled skill would serve instantly. Both branches are gated together and
     * not separately, because "reuse what was learned" is one mechanism with two
     * entry points; an arm that kept composition but dropped replay would measure
     * a configuration this system does not have.
     *
     * Note what this does **not** turn off: the run still records its trace, and
     * a clean one still counts toward compilation. Learning is what the cold path
     * *produces*; the arm controls whether learning is *consumed*. Suppressing
     * the recording as well would make the measurement destructive — asking what
     * a cold run costs would cost the user a lesson their phone had already paid
     * for.
     */
    public val skillReplay: Boolean = true,

    /**
     * Constrain action emission with the GBNF grammar (C3). §14.3 arm A: false.
     *
     * The grammar had a device path only through the instrumented acceptance
     * test that produced E4/E4b — which measures *generations*, not tasks — so
     * arms A and B could never be run over the corpus. Adding it here is what
     * makes the §14.3 matrix reachable from the benchmark rather than from a
     * separate harness answering a narrower question.
     */
    public val grammar: Boolean = true,

    /**
     * Check post-conditions after acting (C2). §14.3 arms A and B: false.
     *
     * Was the only switch in the matrix with **no device path at all**: the row
     * existed and was unreachable, the same shape as E29's capability check and
     * E24c's cold arm before this file existed.
     */
    public val verifier: Boolean = true,
) {
    public companion object {
        /** The shipping configuration: everything on (§14.3 arm D). */
        public val DEFAULT: RunConfig = RunConfig(id = "D", skillReplay = true)

        /** §14.3 arm A — naive: no grammar, no verifier, no reuse. */
        public val A: RunConfig =
            RunConfig(id = "A", skillReplay = false, grammar = false, verifier = false)

        /** §14.3 arm B — grammar only. */
        public val B: RunConfig =
            RunConfig(id = "B", skillReplay = false, grammar = true, verifier = false)

        /** §14.3 arm C — grammar and verifier, no reuse. */
        public val C: RunConfig =
            RunConfig(id = "C", skillReplay = false, grammar = true, verifier = true)

        /**
         * Plan from scratch, ignoring anything learned (§14.3 arms A–C).
         *
         * Named for what it does to the run rather than for an arm letter,
         * because the arm letters also encode the grammar and verifier switches
         * this does not touch. Calling it `A` here would claim more than it does.
         */
        public val COLD: RunConfig = RunConfig(id = "COLD", skillReplay = false)

        /**
         * Parse an arm id supplied from outside the process.
         *
         * Unknown ids fall back to [DEFAULT] rather than throwing: this is
         * reachable from an `adb` extra, and the failure mode of a typo should be
         * "ran the shipping configuration" and not "the gateway crashed". The
         * caller is expected to log what it resolved, so a silent fallback is
         * still a visible one.
         */
        public fun of(id: String?): RunConfig = when (id?.uppercase()) {
            null, "", "D" -> DEFAULT
            "A" -> A
            "B" -> B
            "C" -> C
            // Named for what it does to the run rather than for an arm letter.
            // Identical to C in switches; kept because every cold measurement
            // taken before the grammar and verifier switches existed used this
            // name, and silently re-labelling them would rewrite their
            // provenance.
            "COLD" -> COLD
            else -> DEFAULT
        }
    }
}
