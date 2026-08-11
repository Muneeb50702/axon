package dev.axon.core.perception

/**
 * Decides which UI nodes AXON refuses to read (spec §16, guardrail 3).
 *
 * > *"Never read sensitive credential fields. Explicitly skip/ignore OTP and
 * > authenticator nodes and password fields in perception. (Note:
 * > `isAccessibilityDataSensitive` exists since Android 14 but is inconsistently
 * > applied by apps — so AXON must itself refuse to capture these, not rely on
 * > the app flag.)"*
 *
 * ## Why this lives in `:core` and not in the Android driver
 *
 * It is the guardrail that most needs to be *demonstrable*. §16 opens by noting
 * that AccessibilityService is the substrate of a documented ~$145M stalkerware
 * industry, and that a panel will ask about it. "We filter credential fields"
 * carries very little weight; a pure, deterministic function with a test corpus
 * that anyone can run, in the platform-agnostic module, is an artefact — and it
 * transfers unchanged to a Linux or Windows driver, which a policy buried in
 * Android code would not.
 *
 * ## Refuse at capture, not after
 *
 * The driver consults this *before* reading a node's text, so a password never
 * enters AXON's process memory at all. Filtering afterwards would mean the value
 * had been read, held, and possibly written to a trace — and §16's requirement is
 * about not capturing, not about not displaying.
 *
 * ## Deliberately over-broad
 *
 * The two errors are not symmetric. A false positive costs a hidden field, and
 * the planner is told one was hidden ([dev.axon.core.model.CompactState] renders
 * the count) so it can ask the user rather than loop. A false negative means a
 * password in a log. The heuristics below therefore err heavily toward refusing,
 * and the thesis should state that trade openly rather than claim precision the
 * method does not have.
 */
public object SensitivePolicy {

    /**
     * Should this node's content be refused?
     *
     * @param className platform widget class, e.g. `android.widget.EditText`.
     * @param viewId view id resource name, if any.
     * @param contentDescription accessibility label, if any.
     * @param hintText the field's hint/placeholder, if any.
     * @param isPassword the platform's own password flag (`isPassword` on
     *   Android). Trusted when set — never trusted when unset, per §16.
     * @param appFlaggedSensitive the app's `isAccessibilityDataSensitive`
     *   (Android 14+). Same asymmetry: honoured when `true`, and its absence
     *   proves nothing, because §16 records that apps apply it inconsistently.
     *
     * Note that the node's *text* is not a parameter. Deciding from the value
     * would require reading it first, which is the thing being avoided. Only
     * metadata is inspected.
     */
    public fun isSensitive(
        className: String? = null,
        viewId: String? = null,
        contentDescription: String? = null,
        hintText: String? = null,
        isPassword: Boolean = false,
        appFlaggedSensitive: Boolean = false,
    ): Boolean {
        if (isPassword || appFlaggedSensitive) return true

        val haystack = listOfNotNull(viewId, contentDescription, hintText)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return false

        // The class gate is an *exclusion* list, not an inclusion list, and the
        // direction matters. Requiring a recognised text-entry class
        // (`EditText`, `TextField`, …) would silently pass every Compose,
        // Flutter and React Native field, because those report class names this
        // module has never heard of — a false negative on the majority of
        // modern UIs. Unknown classes are therefore treated as possible
        // credential holders.
        //
        // What *is* excluded is the small set of widgets that cannot hold a
        // secret. A button labelled "Forgot password?" is a navigation
        // affordance, and hiding it would break an ordinary flow while
        // protecting nothing.
        if (className != null && NON_CREDENTIAL_CLASSES.any { className.endsWith(it) }) {
            return false
        }

        return SENSITIVE_TERMS.any { it in haystack }
    }

    /**
     * Terms that mark a field as credential-bearing.
     *
     * Covers the English surface forms plus Urdu/Roman-Urdu and common
     * transliterations — AXON's stated user base is South Asian mid-range
     * Android (§2.1), and a filter that only recognises English would fail
     * exactly the users the project claims to serve. That is a correctness
     * requirement here, not a localisation nicety.
     */
    private val SENSITIVE_TERMS: List<String> = listOf(
        // credentials
        "password", "passwd", "passcode", "pwd", "pass_word",
        "pin", "passphrase", "secret", "credential",
        // one-time codes / 2FA
        "otp", "one_time", "onetime", "one-time", "verification_code",
        "verificationcode", "auth_code", "authcode", "2fa", "mfa",
        "totp", "authenticator", "security_code", "securitycode",
        // payment instruments
        "cvv", "cvc", "card_number", "cardnumber", "iban", "account_number",
        // recovery
        "seed_phrase", "seedphrase", "mnemonic", "recovery_phrase", "private_key",
        // Urdu / Roman Urdu
        "raaz", "khufia", "ramz",
    )

    /**
     * Widgets that cannot hold a credential, so a credential term in their
     * label is part of an affordance rather than a secret.
     *
     * Note what is *not* here: `TextView`. A label reading "Your code is 481920"
     * is an OTP in plain text, and §16 says to skip "OTP and authenticator
     * nodes" — nodes, not only input fields. Reading a code out of a TextView is
     * precisely the OTP-harvesting behaviour the guardrail exists to prevent, so
     * a TextView whose metadata mentions one is refused like any other node.
     */
    private val NON_CREDENTIAL_CLASSES: List<String> = listOf(
        "Button", "ImageButton", "ImageView", "CheckBox", "Switch",
        "RadioButton", "ProgressBar", "SeekBar", "Spinner",
    )
}
