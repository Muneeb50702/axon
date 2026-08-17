package dev.axon.bench

/**
 * §14.3's results table — *"the money table"*. Contribution **C5**.
 *
 * ```
 *   config  model  grammar  verifier  replay   expectation
 *   A       small  ✗        ✗         ✗        low TSR, low valid-action
 *   B       small  ✓        ✗         ✗        valid-action ≈100%, TSR up
 *   C       small  ✓        ✓         ✗        TSR up, recovery > 0
 *   D       small  ✓        ✓         ✓        best TSR, LLM calls collapse
 *   E       large  ✗        ✗         ✗        the baseline D must beat
 * ```
 *
 * The claim §14.3 exists to test: **D meets or beats E on task success while
 * using a fraction of the compute**, and on repeated tasks approaches zero model
 * calls.
 *
 * ## Rendering is part of the argument, not decoration
 *
 * A results table that hides its denominators invites the reader to assume the
 * best ones. Every row here reports how many tasks were attempted and how many
 * were skipped, because an arm that completed four of twenty and an arm that
 * completed twenty of twenty should not print the same-looking percentage.
 *
 * The footnotes are mandatory rather than optional for the same reason. On this
 * hardware an arm is *expected* to be cut short — the OEM power manager
 * terminates sustained compute after about seven minutes (E6) — so a table
 * without coverage stated is a table that reads as complete when it is not.
 */
public object AblationTable {

    /**
     * Render the §14.3 comparison from each arm's scored tasks.
     *
     * Arms with no data are printed rather than dropped. An arm that was killed
     * before its first case is a fact about the deployment substrate (E6, C5),
     * and silently omitting it would turn a finding into a gap.
     */
    public fun render(results: Map<AblationConfig, List<TaskScore>>): String {
        val rows = AblationConfig.ALL
            .filter { it in results }
            .map { config -> config to BenchMetrics.of(config.id, results.getValue(config)) }

        return buildString {
            appendLine("§14.3 ABLATION — AXON-Bench")
            appendLine("=".repeat(96))
            appendLine(header())
            appendLine("-".repeat(96))
            rows.forEach { (config, m) -> appendLine(row(config, m)) }
            appendLine("=".repeat(96))
            appendLine()
            append(verdict(rows.toMap()))
            appendLine()
            append(caveats(rows.map { it.second }))
        }
    }

    private fun header() = buildString {
        append("cfg".padEnd(4))
        append("gram".padEnd(6))
        append("ver".padEnd(5))
        append("rep".padEnd(5))
        append("n".padEnd(6))
        append("TSR".padEnd(9))
        append("valid".padEnd(9))
        append("LLM/task".padEnd(10))
        append("steps".padEnd(8))
        append("recov".padEnd(8))
        append("median")
    }

    private fun row(config: AblationConfig, m: BenchMetrics) = buildString {
        append(config.id.padEnd(4))
        append(tick(config.grammar).padEnd(6))
        append(tick(config.verifier).padEnd(5))
        append(tick(config.skillReplay).padEnd(5))
        append("${m.attempted}".padEnd(6))
        append(pct(m.taskSuccessRate).padEnd(9))
        append(pct(m.validActionRate).padEnd(9))
        append(num(m.llmCallsPerTask).padEnd(10))
        append((m.stepEfficiency?.let { num(it) + "x" } ?: "—").padEnd(8))
        append((m.recoveryRate?.let { pct(it) } ?: "n/a").padEnd(8))
        append("${m.medianLatencyMs / 1000}s")
    }

    /**
     * State whether the headline claim held, in the table's own numbers.
     *
     * Written to be falsifiable. If D does not beat E it says so — a results
     * renderer that can only print success is a renderer nobody should trust,
     * and the whole value of this project's evidence rests on the reader
     * believing it would have reported the other outcome.
     */
    private fun verdict(rows: Map<AblationConfig, BenchMetrics>): String {
        val d = rows[AblationConfig.D_FULL_AXON]
        val e = rows[AblationConfig.E_NAIVE_LARGE]

        if (d == null || e == null) {
            return "HEADLINE CLAIM: not evaluable — " +
                "need both D (full AXON) and E (naive larger model); " +
                "have ${rows.keys.joinToString(", ") { it.id }}.\n"
        }
        if (d.attempted == 0 || e.attempted == 0) {
            return "HEADLINE CLAIM: not evaluable — an arm completed no tasks.\n"
        }

        val beatsOnSuccess = d.taskSuccessRate >= e.taskSuccessRate
        val cheaper = d.llmCallsPerTask <= e.llmCallsPerTask

        return buildString {
            appendLine("HEADLINE CLAIM (§14.3): D meets or beats E on task success, at a fraction of the compute.")
            appendLine(
                "  task success   D ${pct(d.taskSuccessRate)} vs E ${pct(e.taskSuccessRate)}   " +
                    if (beatsOnSuccess) "HELD" else "**DID NOT HOLD**",
            )
            appendLine(
                "  model calls    D ${num(d.llmCallsPerTask)} vs E ${num(e.llmCallsPerTask)}   " +
                    if (cheaper) "HELD" else "**DID NOT HOLD**",
            )
        }
    }

    /**
     * What the table does not say.
     *
     * Printed with the results rather than left to the prose, because a table
     * gets pasted into a slide and the caveats do not follow it.
     */
    private fun caveats(rows: List<BenchMetrics>): String = buildString {
        appendLine("READ WITH:")
        val skipped = rows.sumOf { it.skipped }
        if (skipped > 0) {
            appendLine("  · $skipped task-runs skipped (required app absent) — excluded from every rate, not failed.")
        }
        val short = rows.filter { it.attempted in 1 until BenchCorpus.ALL.size }
        if (short.isNotEmpty()) {
            appendLine(
                "  · incomplete arms: " + short.joinToString(", ") {
                    "${it.config} (${it.attempted}/${BenchCorpus.ALL.size})"
                } + " — the OEM power manager terminates sustained compute (E6); partial runs are expected here.",
            )
        }
        appendLine("  · TSR is the task's own oracle, never the runtime's self-reported outcome.")
        appendLine("  · step efficiency averages successful tasks only, so failing early cannot flatter it.")
        appendLine("  · gated tasks (§16 confirmation) run with the gate opened and measure competence, not field behaviour.")
    }

    private fun tick(on: Boolean) = if (on) "yes" else "no"
    private fun pct(v: Double) = "${(v * 1000).toInt() / 10.0}%"
    private fun num(v: Double) = "${(v * 100).toInt() / 100.0}"
}
