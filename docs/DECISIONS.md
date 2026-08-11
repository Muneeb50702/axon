# AXON — Decision Log

Every place the implementation departs from `PROJECT_AXON_FYP_SPEC.md`, with the
evidence that forced it and what it would take to reverse it.

The spec anticipates this. §11: *"Do not hard-code versions from this doc —
resolve current at implementation."* §20.2: *"Re-run these checks close to
submission — the on-device model field moves quarterly."* A decision log is how
that instruction is discharged rather than merely acknowledged, and it is the
document to reread before the defence: a panel that finds a divergence will ask
whether it was a choice or an accident.

Each entry: **what the spec said**, **what we found**, **what we did**, **how to
undo it**.

---

## D1 — Own JNI bridge instead of Llamatik

*Date: 2026-08-11. Affects: §7.3, §11, §12 (`android/inference`), C3.*

**Spec.** §11 lists inference as *"llama.cpp (GGUF) via Llamatik (KMP, Maven
Central) or llama.android JNI"*, and §7.3 repeats Llamatik as the primary route.

**Found.** Llamatik is real, maintained and on Maven Central (`com.llamatik:library:1.7.0`,
Android minSdk 26). Its generation surface is:

```kotlin
generateJson(prompt: String, jsonSchema: String? = null): String
generateJsonStream(prompt: String, jsonSchema: String? = null, callback: GenStream)
```

Constrained decoding is exposed **only** as a JSON Schema. There is no way to
pass a raw GBNF grammar. Two consequences, either of which is disqualifying:

1. **It blocks C3.** §7.4 and §20.2 are explicit that AXON uses the *raw-grammar*
   path, and §10.6 records why: GBNF converts only a subset of JSON-Schema Draft 7,
   and PCRE shorthands break the converter. AXON's grammar is also a discriminated
   union (see [D3](#d3--action-grammar-as-a-discriminated-union)), which JSON
   Schema expresses through `oneOf` — precisely the construct the converter
   handles least well. Routing C3 through a schema converter would mean the
   contribution's mechanism is a third party's translation layer, not ours.
2. **It breaks on the primary model.** llama.cpp issue
   [#22396](https://github.com/ggml-org/llama.cpp/issues/22396) reports
   `--json-schema` failing with *"Failed to initialize samplers"* on
   `gemma3n`-architecture models — the architecture of both Gemma 3n and Gemma 4
   E2B — while `--grammar` on the same model works. The schema path is broken on
   exactly the model AXON runs.

**Did.** Write a thin JNI layer over llama.cpp directly, binding
`llama_sampler_init_grammar`. The sampler chain puts the grammar sampler at the
**head**, before temperature and top-p, so the constraint holds regardless of
sampling settings; placed after them, a high temperature could surface a token
the grammar had already excluded. Cost is a few hundred lines and an NDK/CMake
step already required by §18. The benefit is that the mechanism the thesis
defends lives in the repository and can be shown to a panel.

**Undo.** If Llamatik later exposes a raw grammar parameter, `LlamaEngine` is a
single implementation of `InferenceEngine` (§9.3) and swapping it touches no
other module. This is the seam working as intended.

---

## D2 — Gemma 4 E2B as the primary model

*Date: 2026-08-11. Affects: §11, §18, §20.2.*

**Spec.** §11 names Gemma 3n E2B (Q4_K_M, ~2 GB) as primary. §18 sets ≥8 GB RAM
as the practical floor for 3B–4B class models.

**Found.** Gemma 4 was released 2026-04-02, after the spec was written. The E2B
edge variant continues the same Per-Layer-Embeddings lineage:

| | Gemma 3n E2B (spec) | Gemma 4 E2B (now) |
|---|---|---|
| Q4_K_M size | ~2 GB | ~1.3 GB |
| Effective params | ~2 B | ~2.3 B |
| Throughput | — | 12–20 tok/s, recent Snapdragon |
| RAM floor | 8 GB per §18 | fits 6 GB |
| Agentic training | general instruct | native function-calling |

**Did.** Gemma 4 E2B Q4_K_M is the primary model; Gemma 3n E2B is retained as a
comparison point. Model ids are resolved at run time via `axon.model.small` /
`axon.model.large` rather than compiled in (`bench/BenchTask.kt`), so §20.2's
"re-run these checks close to submission" is a config change, not a code change.

**Why it matters beyond a version bump.** §2.1 frames AXON as being for users on
mid-range phones, and §18 warns *"Don't only demo on a flagship; the whole point
is cheap phones."* Under the spec's own numbers the primary model needed 8 GB —
a flagship — and the low-resource claim rested on a smaller fallback model. At
1.3 GB the *primary* model runs on the 6 GB device the thesis is about. The
central claim stops being a concession and becomes the default configuration.

**Undo.** Set `axon.model.small` back to a Gemma 3n GGUF. No code change.

---

## D3 — Action grammar as a discriminated union

*Date: 2026-08-11. Affects: §10.1, §10.6, C3.*

**Spec.** §10.6 gives a reference grammar whose `root` is a single object with
optional fields, and instructs: *"This is a starting grammar; refine to match
§10.1 exactly."*

**Found.** The reference grammar admits combinations §10.1 never intended:

```json
{"action":"tap","direction":"up", ...}       ← grammar-valid, meaningless
{"action":"launch_app","text":"hello", ...}  ← grammar-valid, meaningless
{"action":"launch_app","app":"WhatsApp"}     ← not a package name
```

These parse. The executor would then reject them, the step would be wasted, and
the valid-action rate reported in §14.2 would be measuring something weaker than
C3 claims — syntactic validity rather than the structural validity the
contribution asserts.

**Did.** Took up the spec's own instruction to refine. `root` is a discriminated
union: one production per action type, each carrying exactly the fields §10.1
permits for it. Field–action coherence moves out of runtime checking and into the
sampler. Two further refinements:

- `launch_app` takes a `package-name` production (lowercase segments, at least
  one dot) rather than a free string, so `{"action":"launch_app","app":"WhatsApp"}`
  has no token path. This is the clearest single demonstration of what
  constrained decoding buys and is worth showing at the defence.
- Strings exclude control characters, matching llama.cpp's own `json.gbnf`. A raw
  newline inside a JSON string is invalid JSON, so a grammar permitting one emits
  output the parser then rejects — the exact failure C3 exists to eliminate.

The wire format is unchanged, so §10.1 stays normative for anything reading the
JSON. `DeviceActionWireFormatTest` asserts this; `ActionGrammarTest` asserts the
grammar and the Kotlin enums still describe the same action space, so hand-written
grammar drift is a CI failure rather than a silent capability gap.

**Kotlin mirrors the grammar.** `DeviceAction` is a sealed hierarchy with one
variant per action type, so illegal field combinations do not compile — the same
constraint expressed at all three layers (types, wire format, sampler).

**Undo.** Not advisable; this strengthens the contribution the ablation measures.

---

## D4 — Deviations from the §9 interface signatures

*Date: 2026-08-11. Affects: §9.2.*

§9 states: *"Types are illustrative but the shape is required."* Three signatures
keep the shape and widen the return or parameter list. Each is recorded because
§9 is normative and a reader must be able to see that the widening is deliberate.

| §9.2 | Implemented | Why |
|---|---|---|
| `Planner.nextAction(state, goal, menu): DeviceAction` | returns `PlanDecision`; optional `failure` param | `PlanDecision` carries `llmCalls`. "LLM calls per task" is C1's headline metric (§14.2) and the point of replay is driving it to zero — a planner that cannot report its own cost makes the central claim unmeasurable. `failure` carries §7.6's self-heal context; without it, re-planning repeats the failed action. |
| `Verifier.verify(expected, actual)` | plus a `(expected, before, actual)` overload | An unchanged tree means the action hit *nothing*, which needs a different target rather than a retry. The §9.2 form is kept and delegates to it. |
| `SkillStore.match(goal): CompiledSkill?` | returns `SkillMatch?` | A match must also bind the goal's slots and report confidence, or the caller cannot apply `MIN_CONFIDENCE` and a near-miss replays the wrong skill against a live device. |

---

## D5 — `:core` targets JVM as well as Android

*Date: 2026-08-11. Affects: §12, §14, C4.*

**Spec.** §12 shows `core/` as the KMP shared module and `android/` as the
reference driver, without specifying targets.

**Did.** `:core` builds for **both** `jvm()` and Android, and `:bench` is a plain
JVM module.

**Why.** §14 asks for a reproducible results table across five ablation configs.
If the harness only ran on a phone, "reproducible" would mean "given the same
handset, app versions and battery temperature" — which is not reproducible, and
is how most student evaluations quietly fail to be checkable. Because `:core`
carries no Android dependency, the planner logic, precondition gate, verifier,
skill compiler and replay path all run headless against recorded UI trees, on CI,
on every commit.

This also makes C4 (portability) demonstrated rather than asserted: there are two
targets in the repository today, and the build graph fails if anything in `:core`
reaches for an Android type.

The split it implies, which the thesis should state plainly: deterministic
components are evaluated in CI; device-dependent numbers (latency, thermal
ceiling, real app layouts) are measured on the phone and reported separately as
device-bound.

---

## D6 — Safety policy landed in Phase 0, in `:core`

*Date: 2026-08-11. Affects: §16, §13.*

**Spec.** §16 lists the credential-refusal guardrail as mandatory. §13 does not
schedule it; perception arrives in Phase 2.

**Did.** `SensitivePolicy` (`core/perception`) with a test corpus, in Phase 0.

**Why in `:core` rather than the Android driver.** §16 opens by noting that
AccessibilityService is the substrate of a documented ~$145M stalkerware industry
and that a panel will ask about it. "We filter credential fields" carries little
weight; a pure deterministic function with a runnable test corpus, in the
platform-agnostic module, is an artefact — and it transfers unchanged to a Linux
or Windows driver, which a policy buried in Android code would not.

**Why in Phase 0 rather than Phase 2.** The policy has to be consulted *before* a
node's text is read, so a password never enters the process at all. That is a
property of how `observe()` is written, not a filter applied afterwards. Landing
the policy first means Phase 2 is written against it; landing it after would mean
retrofitting the guardrail into a working capture path, which is how the
guardrail ends up being a filter over data that was already read.

The same reasoning puts the foreground-service gateway skeleton in Phase 0:
visible operation (§16, guardrail 1) is a property of how the agent runs at all,
so every intermediate build should already be one that cannot operate the device
invisibly.

**Deliberately over-broad.** The two errors are not symmetric. A false positive
hides a field, and the planner is told one was hidden so it can ask the user
rather than loop. A false negative puts a password in a log. The thesis should
state this trade openly rather than claim a precision the method does not have.

---

## D7 — No `INTERNET` permission

*Date: 2026-08-11. Affects: §16, §2.1.*

**Spec.** §16, guardrail 2: *"No screen exfiltration. UI contents never leave the
device… enforce it architecturally (no network capability granted to
perception/planner paths)."*

**Did.** The manifest requests no `INTERNET` permission at all.

**Why.** It converts the project's headline privacy claim from a promise into a
property. An examiner can verify "this app cannot upload your screen" by reading
the manifest, without auditing a line of Kotlin — the OS enforces it. It is also
the strongest available answer to the §3 "this already exists" challenge, since
the comparable projects are cloud- or bridge-based by construction.

**Consequence to plan for.** Model weights cannot be downloaded in-app. They are
side-loaded, or fetched by a separate, clearly-scoped downloader component that
the agent paths do not link against. Chosen knowingly: an `INTERNET` permission
added "just for the model download" would silently be available to every code
path in the process, and the guarantee would be gone.
