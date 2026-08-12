package dev.axon.bench

import dev.axon.core.inference.ActionGrammar
import java.io.File

/**
 * Write the §10.6 action grammar to a `.gbnf` file.
 *
 * The grammar's single source of truth is [ActionGrammar.SOURCE] in `:core`,
 * because that is what the app actually hands to the sampler. Checking a second
 * copy into the repo would create exactly the drift `ActionGrammarTest` exists to
 * prevent — the file could pass validation while the compiled constant that
 * reaches the device did not.
 *
 * So the file is *generated*, and `tools/check-grammar.sh` validates the
 * generated artefact with llama.cpp's own parser. What runs in CI is what runs
 * on the phone.
 *
 * The exported file is also the thesis appendix: the grammar in a form a reader
 * can inspect and an examiner can re-validate.
 *
 * ```
 * ./gradlew :bench:exportGrammar        # → build/axon-action.gbnf
 * ./tools/check-grammar.sh              # export + validate
 * ```
 */
object ExportGrammar {

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "build/axon-action.gbnf")
        target.parentFile?.mkdirs()
        target.writeText(ActionGrammar.SOURCE + "\n")

        // Also export a *specialised* grammar, because that is what the planner
        // actually hands the sampler at run time (E18). Validating only the base
        // grammar would leave the generated one — the one built from live,
        // untrusted app labels — unchecked, and a label containing a quote
        // produces a parse failure that llama.cpp reports by silently declining
        // to constrain generation at all.
        val worstCase = dev.axon.core.bench.ScreenCorpus.ALL
            .maxByOrNull { it.state.elements.size }!!
        val screen = File(target.parentFile, "axon-screen.gbnf")
        screen.writeText(
            dev.axon.core.inference.ScreenGrammar.forScreen(worstCase.state).source + "\n",
        )
        println("wrote ${screen.path} (from corpus screen '${worstCase.id}')")

        val rules = ActionGrammar.SOURCE.lineSequence()
            .count { Regex("^\\s*[a-z][a-z0-9-]*\\s*::=").containsMatchIn(it) }

        println("wrote ${target.path} (${target.length()} bytes, $rules rules)")
    }
}
