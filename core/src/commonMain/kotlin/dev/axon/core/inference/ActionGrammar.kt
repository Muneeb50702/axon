package dev.axon.core.inference

/**
 * The GBNF action grammar (spec §10.6) — contribution **C3**.
 *
 * Every action the planner emits is sampled under this grammar. At each token
 * llama.cpp masks every continuation that would leave the grammar, so a
 * malformed or out-of-vocabulary action is not *rejected* — it is unreachable.
 * A 2B model cannot invent an action type, a selector kind, or a field that does
 * not belong, because no token path leads there.
 *
 * ## How this differs from the §10.6 reference grammar, and why
 *
 * §10.6 presents a starting grammar and instructs: *"refine to match §10.1
 * exactly."* Its `root` is one object shape with optional fields, which admits
 * combinations §10.1 never intended:
 *
 * ```
 * {"action":"tap","direction":"up", ...}      ← grammar-valid, meaningless
 * {"action":"launch_app","text":"hello", ...} ← grammar-valid, meaningless
 * ```
 *
 * Those parse. The executor would then reject them, the step would be wasted,
 * and the "valid-action rate" reported in §14.2 would be measuring something
 * weaker than C3 claims. So `root` here is a **discriminated union**: one
 * production per action type, each carrying exactly the fields §10.1 permits for
 * that action. Field–action coherence moves from a runtime check into the
 * sampler.
 *
 * Two further refinements over the reference grammar:
 *
 *  - **`package-name` is structurally constrained.** `launch_app` cannot take an
 *    arbitrary string; it must match Android package syntax. The model cannot
 *    emit `{"action":"launch_app","app":"WhatsApp"}` — the sampler will not
 *    produce a capital letter there. This is the cleanest single illustration of
 *    what constrained decoding buys, and a good one for the defence.
 *  - **Control characters are excluded from strings**, matching llama.cpp's own
 *    `json.gbnf`. A raw newline inside a JSON string is invalid JSON, and a
 *    grammar that permits one produces output the parser then rejects — exactly
 *    the failure C3 exists to eliminate.
 *
 * The wire format is unchanged from §10.1, so §10.1 remains normative for any
 * reader of the JSON. Only the impossible combinations were removed.
 *
 * ## What a grammar does not do
 *
 * Per §7.4, two limits are handled elsewhere rather than pretended away:
 *
 *  1. **It is not injected into the prompt.** The prompt describes the screen
 *     and the available actions in natural language; the grammar acts at the
 *     sampler. [Gbnf] is a distinct type from `String` so a grammar cannot be
 *     passed where a prompt is expected.
 *  2. **It constrains shape, not completion.** If the model exhausts its token
 *     budget mid-object, the output is a valid *prefix* and nothing more. The
 *     planner must treat a truncated parse as retryable — see
 *     [InferenceEngine.generate].
 *
 * And one limit worth stating plainly in the thesis: a grammar guarantees the
 * action is *well-formed*, never that it is *correct*. `{"action":"tap",
 * "target":{"by":"text","value":"Send"}}` is perfectly valid on a screen with no
 * Send button. That case belongs to the executor's precondition gate (§7.5), and
 * the fact that it needs a second mechanism is why AXON's reliability argument is
 * architectural rather than a claim about decoding alone.
 */
public object ActionGrammar {

    /**
     * The canonical grammar source.
     *
     * Hand-written rather than generated from the model classes, on purpose:
     * this text is a research artefact that goes in the thesis appendix and must
     * read clearly. The risk of hand-writing — that it silently drifts from the
     * Kotlin enums — is covered by `ActionGrammarTest`, which asserts every enum
     * value appears here and every literal here maps to an enum. Drift becomes a
     * CI failure instead of a mystery at defence time.
     */
    public val SOURCE: String = """
        # ============================================================
        # AXON action grammar (GBNF) — spec §10.6, contribution C3
        #
        # Discriminated union: one production per action type, each
        # carrying exactly the fields §10.1 allows for that action.
        # Illegal field/action combinations have no token path.
        # ============================================================

        root ::= tap | long-press | input-text | swipe | scroll | launch-app | press-key | wait

        # --- element-directed actions -------------------------------

        tap ::=
          "{" ws "\"action\"" ws ":" ws "\"tap\""
          ws "," ws target-f
          ws "," ws expect-f ws "}"

        long-press ::=
          "{" ws "\"action\"" ws ":" ws "\"long_press\""
          ws "," ws target-f
          ws "," ws expect-f ws "}"

        # Only input-text may carry a "text" field.
        input-text ::=
          "{" ws "\"action\"" ws ":" ws "\"input_text\""
          ws "," ws target-f
          ws "," ws text-f
          ws "," ws expect-f ws "}"

        # --- directional actions ------------------------------------
        # Only swipe/scroll may carry "direction". Target is optional:
        # present to act within an element, absent for the whole screen.

        swipe ::=
          "{" ws "\"action\"" ws ":" ws "\"swipe\""
          ws "," ws direction-f
          ( ws "," ws target-f )?
          ws "," ws expect-f ws "}"

        scroll ::=
          "{" ws "\"action\"" ws ":" ws "\"scroll\""
          ws "," ws direction-f
          ( ws "," ws target-f )?
          ws "," ws expect-f ws "}"

        # --- device-directed actions --------------------------------
        # These address the device, not an element, so no target field.

        launch-app ::=
          "{" ws "\"action\"" ws ":" ws "\"launch_app\""
          ws "," ws app-f
          ws "," ws expect-f ws "}"

        press-key ::=
          "{" ws "\"action\"" ws ":" ws "\"press_key\""
          ws "," ws key-f
          ws "," ws expect-f ws "}"

        wait ::=
          "{" ws "\"action\"" ws ":" ws "\"wait\""
          ws "," ws expect-f ws "}"

        # --- fields --------------------------------------------------

        target-f ::= "\"target\"" ws ":" ws target
        target   ::= "{" ws "\"by\"" ws ":" ws by
                     ws "," ws "\"value\"" ws ":" ws string ws "}"

        by ::= "\"text\"" | "\"id\"" | "\"content_desc\"" | "\"class\"" | "\"coord\""

        expect-f ::= "\"expect\"" ws ":" ws expect
        expect   ::= "{" ws "\"type\"" ws ":" ws etype
                     ws "," ws "\"value\"" ws ":" ws string ws "}"

        etype ::= "\"node_present\"" | "\"node_absent\"" | "\"text_matches\""
                | "\"screen_title\"" | "\"app_foreground\""

        text-f      ::= "\"text\"" ws ":" ws string
        app-f       ::= "\"app\"" ws ":" ws package-name
        key-f       ::= "\"key\"" ws ":" ws key
        direction-f ::= "\"direction\"" ws ":" ws direction

        key       ::= "\"home\"" | "\"back\"" | "\"enter\""
        direction ::= "\"up\"" | "\"down\"" | "\"left\"" | "\"right\""

        # --- terminals ------------------------------------------------

        # Android package syntax: lowercase segments, at least one dot.
        # The model cannot emit "WhatsApp" here — no token path reaches an
        # uppercase letter in this position.
        package-name ::= "\"" pkg-seg ( "." pkg-seg )+ "\""
        pkg-seg      ::= [a-z] [a-z0-9_]*

        # Control characters are excluded, per llama.cpp's own json.gbnf:
        # a raw newline inside a JSON string is invalid JSON, and a grammar
        # that allows one emits output the parser then rejects.
        string ::= "\"" char* "\""
        char   ::= [^"\\\x00-\x1F\x7F] | "\\" escape
        escape ::= ["\\/bfnrt] | "u" hex hex hex hex
        hex    ::= [0-9a-fA-F]

        ws ::= [ \t\n]*
    """.trimIndent()

    /** The grammar as the value type [InferenceEngine.generate] accepts. */
    public val GBNF: Gbnf = Gbnf(SOURCE)

    /**
     * All quoted literals in the grammar — used by the drift test to prove the
     * grammar and the Kotlin enums still describe the same action space.
     */
    public fun literals(): Set<String> =
        LITERAL.findAll(SOURCE).map { it.groupValues[1] }.toSet()

    // Matches a GBNF string literal such as  \"content_desc\"  in SOURCE.
    private val LITERAL = Regex("\\\\\"([a-z_]+)\\\\\"")
}
