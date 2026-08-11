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
edge variant continues the same Per-Layer-Embeddings lineage.

**Correction, 2026-08-11.** An earlier draft of this entry recorded the Q4_K_M
download as ~1.3 GB, taken from a secondary blog source. That figure is wrong.
Measured from the Hugging Face API against `unsloth/gemma-4-E2B-it-GGUF`:

| file | size |
|---|---|
| `gemma-4-E2B-it-Q4_K_M.gguf` | **3.11 GB** |
| `gemma-4-E2B-it-Q4_K_S.gguf` | 3.04 GB |
| `gemma-4-E2B-it-Q3_K_M.gguf` | 2.54 GB |
| `gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf` | 2.19 GB |

The discrepancy is inherent to the architecture and worth understanding rather
than papering over: "E2B" denotes ~2 B *effective* parameters — the compute
active per token — while the file holds the full parameter set, which
Per-Layer Embeddings and selective activation draw from. Effective size and file
size are different quantities for this model family, and quoting one for the
other is an easy mistake to make. A panel may well probe it, so:

| | Gemma 3n E2B (spec) | Gemma 4 E2B (now) |
|---|---|---|
| Q4_K_M file size | ~2 GB (per §11) | **3.11 GB** (measured) |
| Effective params | ~2 B | ~2.3 B |
| Agentic training | general instruct | native function-calling |

**Did.** Gemma 4 E2B **Q4_K_M** remains the primary model — it is what §7.3
specifies, and it is the honest first measurement. Model ids resolve at run time
via `axon.model.small` / `axon.model.large` (`bench/BenchTask.kt`), so §20.2's
"re-run these checks close to submission" is a config change, not a code change.

**What the corrected number costs, stated plainly.** The device has 7.9 GB total
and ~4.3 GB available, so a 3.11 GB mmapped model fits but is not comfortable.
The claim "the primary model runs on a 6 GB phone" is **not** supported at
Q4_K_M and should not be made. Two honest positions remain, and Phase 1 measures
which one to take:

1. Q4_K_M on an 8 GB device — the spec-compliant configuration, and what the
   benchmark headline numbers are produced on.
2. Q3_K_M (2.54 GB) or the QAT Q2_K_XL (2.19 GB) for the 6 GB claim, **with the
   quality cost measured** on AXON-Bench rather than assumed to be negligible.

Quantisation-vs-task-success on a fixed benchmark is itself a result worth
reporting (§14.2), and it turns a broken claim into a finding. What matters is
that the low-resource story rests on a measurement, not on a number taken from a
blog post.

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

---

## D8 — Vulkan is a measurement, not a default, on the target device

*Date: 2026-08-11. Affects: §18, §7.3, §14.2.*

**Spec.** §18 gives the build flags as *"verified"*: `arm64-v8a`,
`-DANDROID_PLATFORM=android-26`, `-DLLAMA_VULKAN=ON`, `-DBUILD_SHARED_LIBS=ON`.
§11 likewise lists Vulkan acceleration as a reason for choosing llama.cpp.

**Found.** The primary test device is a **TECNO Camon 20 (CK6n)**, profiled
2026-08-11 over ADB:

| | |
|---|---|
| SoC | MediaTek Helio G85 (`MT6769`) |
| CPU | 2× Cortex-A75 @ 2.0 GHz + 6× Cortex-A55 @ 1.8 GHz |
| ISA | ARMv8.2-A with `asimddp` (dot product). **No** `i8mm`, no SVE |
| GPU | Mali-G52 MC2, OpenGL ES 3.2, driver `r32p1` |
| RAM | 7.9 GB total, ~4.3 GB available |
| OS | Android 14, API 34, arm64-v8a |

Two things follow, pulling in opposite directions:

- **The CPU path is in good shape.** `asimddp` is exactly what llama.cpp's
  Q4_K kernels use, so the quantisation choice in §7.3 is well matched to this
  silicon.
- **The GPU path is doubtful.** Mali-G52 MC2 is a two-core mid-range GPU, and
  `r32p1` is a driver from around 2021. llama.cpp's Vulkan backend on mid-range
  Mali is commonly *slower* than the CPU backend and has a history of
  correctness problems on drivers of that vintage. Turning it on because §18
  says so would risk shipping a configuration that is both slower and wrong.

**Did.** Treat the backend as an empirical question rather than a setting.
Phase 1 builds llama.cpp **twice** — CPU-only and Vulkan — and runs the same
prompt corpus through both, comparing prefill throughput, decode throughput,
correctness of grammar-constrained output, and thermal behaviour. The engine
selects its backend from that measurement, with the CPU build as the default if
Vulkan does not clearly win.

**Why this is worth the extra work.** §14.2 already reports latency and thermal
ceiling as metrics, so the comparison is not a detour — it is one of the results
tables, and it is a more interesting one than a number taken on faith. It also
directly addresses §18's own warning: *"Don't only demo on a flagship; the whole
point is cheap phones."* A backend chosen by measurement on a ~$150 handset is a
finding; a build flag copied from a spec is not.

**Undo.** A single CMake flag, plus the engine's backend selection. Nothing above
`InferenceEngine` (§9.3) is aware of which build is loaded.

### The consequence that shapes the project

On 2× A75, prefill dominates. A rendered `CompactState` for a busy screen is
several hundred tokens, and every planning step re-processes a prompt of that
size. Phase 1 will produce the real numbers, but the cold PLAN path on this
device is expected to be **tens of seconds per step**, not the sub-second figures
a flagship would give.

That is not a problem to hide; it is the argument. §17's demo advice — *"Lead the
live demo with a replayed compiled skill (near-instant) to show the 'gets faster
with use' payoff"* — stops being presentation tactics and becomes the thesis:

> On a $150 phone, a cold LLM-planned task costs tens of seconds per step. The
> same task, once compiled to a skill, replays deterministically with **zero**
> model calls in milliseconds. The gap between those two numbers is exactly what
> contribution C1 buys, and it is widest on precisely the low-end hardware the
> project exists to serve.

A flagship would have made C1 look like an optimisation. This device makes it
look necessary. Three mitigations follow directly and are Phase 1/3 work:

1. **KV-cache prefix reuse.** Structure the planner prompt as a stable prefix
   (system instructions + goal) followed by a volatile suffix (the screen), so
   only the changed tail is prefilled each step.
2. **Re-tune `CompactState.MAX_NODES`** (currently 40) against measured prefill
   cost rather than against a token-budget guess.
3. **Router model for cheap steps**, per §11 — a sub-1B model to classify intent
   and match skills before the planner is ever invoked.

---

## D9 — The native build must be optimised even in a debug APK

*Date: 2026-08-11. Affects: §18, §14.2, and the credibility of every latency number.*

**Found.** AGP compiles the debug variant with `CMAKE_BUILD_TYPE=Debug`, i.e.
`-O0`. That is harmless for JNI glue and ruinous for ggml, whose quantised matmul
kernels depend entirely on vectorisation and inlining.

The symptom was not a compiler warning. It was a **ten-minute hang** on a single
32-token generation from a 0.8 GB model, ending in a SIGKILL from the OEM memory
manager. Nothing in any log said "you are running unoptimised code".

**Why this is worth its own entry.** The failure is invisible *and* plausible. A
budget phone being slow at LLM inference is exactly what one expects, so an
unoptimised build produces numbers that look like a finding. Every latency figure
in §14.2 would have been wrong by more than an order of magnitude and no reviewer
could have spotted it from the results table.

**Did.** Force `-O3` for all four build-type flag sets in
`android/inference/src/main/cpp/CMakeLists.txt`, before `add_subdirectory` so
llama.cpp inherits them. Debug symbols are kept — the goal is optimised code that
is still debuggable.

---

## D10 — Gemma 3 1B is the planner; Gemma 4 E2B becomes ablation arm E

*Date: 2026-08-11. Affects: §11, §14.3, D2. Supersedes D2's model choice.*

**Measured** on the TECNO Camon 20 (Helio G85), one planning step from the
§14 screen corpus, 547-token prompt, grammar-constrained, optimised build:

| model | size | prefill | decode | grammar | **total/step** |
|---|---|---|---|---|---|
| Gemma 3 1B Q4_K_M | 0.81 GB | 38.3 s (14.3 t/s) | 22.4 s (1.7 t/s) | 13.2 s | **60.7 s** |
| Gemma 4 E2B Q4_K_M | 3.11 GB | 123.0 s (4.6 t/s) | 34.3 s (1.4 t/s) | 17.0 s | **157.4 s** ✂ |

✂ = hit the token cap mid-object.

**Did.** Gemma 3 1B Q4_K_M is the planner for configs A–D. Gemma 4 E2B becomes
**config E**, §14.3's "naive larger model" baseline.

**Why this is a better outcome than the spec's plan, not a retreat.** §14.3 asks
for a 3B-vs-7B comparison and predicts that a constrained, verified,
skill-compiled small model will match or beat a naive larger one. That experiment
needs two models separated by a real capability gap, both runnable on the target
device. On this hardware that pairing is 1B vs E2B, and the gap is now measured
rather than assumed: **2.6× in wall-clock per step**. The headline claim gets
sharper — the small model is not merely adequate, it is the only one that makes a
multi-step task finish in a usable time, and the architecture is what makes it
reliable enough to use.

### Where the time actually goes — and what to do about it

Two numbers determine the next optimisations, and both were guesses until now:

1. **Prefill is 63% of a step.** The prompt is 547 tokens, of which ~350 are the
   system prompt — identical on every step of every task. KV-cache prefix reuse
   (D8, mitigation 1) therefore addresses about half the total cost, and it is
   now the highest-value change available. `PlannerPrompt` is already split into
   a stable prefix and a volatile suffix for exactly this.

2. **Grammar sampling is 59% of decode** (13.2 s of 22.4 s). This is the price of
   the deliberate choice in `axon_llama.cpp` to put the grammar at the head of the
   sampler chain, where it is evaluated against Gemma's full ~262k vocabulary on
   every token. llama.cpp's own `common_sampler` avoids this with an optimistic
   scheme: sample first, check the single chosen token, and fall back to full
   masking only on rejection.

   Head position was chosen for unconditional correctness, and that was right for
   Phase 1 — C3 had to be shown working before it was made fast. But **59% of
   decode is too high to keep**, and the optimistic scheme is equally correct, so
   Phase 3 should adopt it and report both numbers. The cost of constrained
   decoding is itself a §14.2 result, and now it is a measurement.

### One more grammar finding

The reference grammar in §10.6 threads an optional-whitespace rule between every
token, as `json.gbnf` does. Correct for a parser, wrong for a generator: Gemma 4
E2B used that freedom to emit pretty-printed JSON with newlines and **ran into
its token cap mid-object**. Removing the whitespace slots forces compact output,
cut a 1B generation from 43 to 38 tokens, and deletes that truncation mode
entirely.
