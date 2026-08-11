package dev.axon.core.model

import kotlinx.serialization.json.Json

/**
 * The one JSON codec AXON uses (spec §11 kotlinx.serialization).
 *
 * Centralised because the settings are load-bearing, not cosmetic — the
 * grammar-constrained decoder and this parser must agree exactly on the wire
 * format, or C3's "structurally valid by construction" quietly becomes
 * "structurally valid, then rejected at parse". Each setting below is chosen
 * against that requirement.
 */
public object AxonJson {

    /**
     * Parser for model output.
     *
     * `ignoreUnknownKeys = false` is deliberate, and it is the opposite of the
     * usual default. The grammar cannot emit an unknown key, so if one appears
     * the grammar was not applied — a silently unconstrained run. Tolerating it
     * would make the ablation's "grammar on" and "grammar off" arms look alike
     * on the metric that is supposed to separate them (§14.3, valid-action rate).
     * Better to fail loudly and count it.
     */
    public val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        classDiscriminator = "action"
    }

    /**
     * Codec for data AXON wrote itself — traces, skills, manifests.
     *
     * Here `ignoreUnknownKeys = true` is right: a trace recorded by an older
     * build must still load after the schema gains a field, otherwise every
     * schema change throws away the episodic memory and the benchmark history
     * along with it.
     */
    public val persistence: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "action"
    }

    /**
     * Compact form for logs and hashing — same semantics as [persistence], no
     * whitespace.
     *
     * Declared standalone rather than derived from [persistence]: kotlinx
     * rejects a configuration that carries a custom `prettyPrintIndent` while
     * `prettyPrint` is off, so inheriting the pretty settings and switching one
     * flag throws at class-init.
     */
    public val compact: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = false
        classDiscriminator = "action"
    }
}
