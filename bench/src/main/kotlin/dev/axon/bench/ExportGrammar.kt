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

        val rules = ActionGrammar.SOURCE.lineSequence()
            .count { Regex("^\\s*[a-z][a-z0-9-]*\\s*::=").containsMatchIn(it) }

        println("wrote ${target.path} (${target.length()} bytes, $rules rules)")
    }
}
