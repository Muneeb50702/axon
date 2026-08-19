package dev.axon.bench

import dev.axon.core.model.AxonJson
import java.io.File

/**
 * Write the §14.1 corpus as JSON for the on-device runner (**E8**).
 *
 * ## Why the runner does not hard-code the tasks
 *
 * The device benchmark is driven from the host by a shell script, because a run
 * has to survive the app process being `SIGKILL`ed mid-task (E6b) and a driver
 * living inside that process cannot. That script needs the goals and the success
 * oracles.
 *
 * Retyping them into the script would create a second corpus that drifts from
 * this one silently, and the failure would be a benchmark scoring tasks the
 * system was never asked to do. Exporting keeps [BenchCorpus] the single source
 * and makes divergence impossible rather than merely discouraged — the same
 * argument as `TargetBy.wire` and `ExportGrammar`.
 */
object ExportCorpus {

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "build/axon-corpus.json")
        target.parentFile?.mkdirs()
        target.writeText(
            AxonJson.compact.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(BenchTask.serializer()),
                BenchCorpus.ALL,
            ),
        )
        println("wrote ${target.path} (${BenchCorpus.ALL.size} tasks)")
    }
}
