# AXON — Experiment Log

Every measurement, with the exact configuration that produced it.

## Why this file exists separately from DECISIONS.md

`DECISIONS.md` records *choices* and the evidence for them. This file records
*measurements* — and it is written to a stricter standard, because the project is
intended for publication as well as for the FYP.

The difference that matters: a report tolerates a number recalled from a build
three months ago; a paper does not. A reviewer asking "what exactly produced
Table 3?" must get an answer precise enough to re-run. So every entry below
carries the model file, the build configuration, the device state, the commit,
and the raw artefact the number was computed from. **A number that cannot be
traced to an entry here does not go in the paper.**

The single most likely way this project produces a wrong published result is a
measurement taken under a configuration nobody wrote down — decision D9 is the
proof of that: an unoptimised native build made every latency figure wrong by
more than an order of magnitude, silently, while looking entirely plausible.

## Provenance template

Each entry states:

| field | why it is required |
|---|---|
| **date / commit** | the code that produced it |
| **device** | model, SoC, OS, RAM |
| **model file** | exact GGUF, quantisation, byte size |
| **build config** | `CMAKE_BUILD_TYPE` effective flags, backend, threads, `n_ctx` |
| **device state** | battery %, temperature, charging, other load |
| **artefact** | the raw file the number was computed from |
| **result** | the number, and what it does *not* show |

---

## Standing environment

Unless an entry says otherwise:

**Device.** TECNO Camon 20 (`TECNO CK6n`), MediaTek Helio G85 (`MT6769`),
2× Cortex-A75 @ 2.0 GHz + 6× Cortex-A55 @ 1.8 GHz, Mali-G52 MC2 (driver
`r32p1`), 7.9 GB RAM, Android 14 (API 34), arm64-v8a. CPU features include
`asimddp`; **no** `i8mm`, no SVE.

**Build.** llama.cpp submodule at `third_party/llama.cpp`; NDK 28.2.13676358;
`-O3` forced for all build types (D9); CPU backend only, `AXON_VULKAN=OFF` (D8);
weights loaded with `LLAMA_LOAD_MODE_MMAP` (§7.3).

**Sampling.** Greedy (`temperature = 0`) throughout, so runs are reproducible and
an A/B comparison differs only in the variable under test.

---

## E1 — Native optimisation level

*2026-08-11 · pre-`d5b4c51` · Gemma 3 1B Q4_K_M · 4 threads · `n_ctx` 2048*

| `CMAKE_BUILD_TYPE` | one 32-token generation |
|---|---|
| Debug (`-O0`, AGP default) | **did not complete in >10 min**; SIGKILLed |
| Debug with forced `-O3` | 60.7 s |

**Result.** More than an order of magnitude, and the unoptimised case never
terminated at all.

**What it does not show.** This is not a measurement of `-O0` versus `-O3` in
general; it is a floor, since the slow arm was killed before finishing.

**Why it is logged as an experiment.** It is the clearest available evidence for
this file's premise. Nothing warned that the build was unoptimised, and a budget
phone being slow at inference is exactly what a reader would expect — so every
latency number in the paper would have been wrong and unfalsifiable from the
results alone.

---

## E2 — Planner model selection

*2026-08-11 · `d5b4c51` · one planning step from `ScreenCorpus`
(`whatsapp_conversation`) · grammar-constrained · 4 decode threads · `n_ctx` 2048
· `max_tokens` 48 · battery >40%, thermal `NONE`*

| model | GGUF bytes | prompt tok | out tok | prefill | decode | grammar | **total** |
|---|---|---|---|---|---|---|---|
| `gemma-3-1b-it-Q4_K_M` | 806,058,272 | 547 | 43 | 43.5 s (12.6 t/s) | 21.5 s (2.0 t/s) | 15.1 s | **65.0 s** |
| `gemma-4-E2B-it-Q4_K_M` | 3,106,738,272 | 565 | 48 ✂ | 123.0 s (4.6 t/s) | 34.3 s (1.4 t/s) | 17.0 s | **157.4 s** |

✂ hit the token cap mid-object (see E5).

**Result.** 2.6× in wall-clock per planning step. Drives decision D10: the 1B is
the planner for configs A–D; the E2B becomes §14.3's config E baseline.

**What it does not show.** Nothing about *task success*. These are single
generations on one screen, measuring speed only. Whether the larger model chooses
better actions is a Phase 3 question and must be measured separately before any
claim that the small model is "as good".

**Threat to validity.** n = 1 per model. Adequate for a 2.6× gap and a
configuration decision; **not** adequate for the paper. Repeat across the full
corpus with variance before publishing.

---

## E3 — Thread allocation on big.LITTLE

*2026-08-11 · `d5b4c51` · Gemma 3 1B Q4_K_M · `n_ctx` 2048 · grammar-constrained*

| threads | prefill | decode |
|---|---|---|
| 4 | 12.6 t/s | **2.0 t/s** |
| 8 | **15.6 t/s** | 1.5 t/s |

**Result.** The optimum differs by phase. Prefill is compute-bound and
parallelises across all eight cores; single-token decode is memory-bound and
completes only when the slowest thread does, so the six A55s hold it back. AXON
therefore sets `n_threads_batch = 8` and `n_threads = 4`.

Combined configuration (`4/8`, plus the E5 grammar change): **60.7 s/step**.

**What it does not show.** Only two points were sampled. 2 and 6 threads were not
tested, and the interaction with thermal throttling is unmeasured — these figures
were taken on a cool device.

---

## E4 — Grammar ablation (§14.3 rows A and B)

*2026-08-11 · `0f12b40` · Gemma 3 1B Q4_K_M · `ScreenCorpus`, 24 screens ·
greedy · `max_tokens` 192 · chunked 4 cases per invocation (E6) ·
artefact: `bench/results/acceptance-raw.log`*

Both arms receive **byte-identical prompts**; the grammar is the only variable.
This is the §13 Phase 1 acceptance criterion and simultaneously the A-vs-B rows
of the §14.3 ablation matrix.

| metric | A (naive) | B (+grammar) |
|---|---|---|
| cases | 18 | 18 |
| **valid-action rate** | **0.0%** | **100.0%** |
| median latency | 32.5 s | 52.4 s |
| median prefill | 32.0 s | 32.5 s |
| median decode | 0.4 s | 19.0 s |
| grammar sampling | — | 12.8 s |
| mean output tokens | **2.4** | 36.6 |
| truncated | 0 | 0 |

18 of 24 cases recorded; 6 lost to the E6 process kills.
Artefact: `bench/results/acceptance-raw.log` / `.json`.

### ⚠ This result is NOT publishable as it stands — the baseline is strawmanned

`mean output tokens = 2.4` for arm A is the tell, and following it up settles the
matter. Running the unconstrained arm and printing the text gives:

```
output tokens  : 1
stopped on EOG : true
output         : tap
```

Arm A is not emitting *malformed JSON*. It is emitting the word "tap" and
stopping. The cause is in `PlannerPrompt.SYSTEM`, which never states that the
output should be JSON — it ends with "Answer with the action only", and a 1B
model reasonably reads that as "name the action".

The constrained arm succeeds regardless, because the grammar supplies the
structure the prompt omitted. So what this experiment actually measured is
*"grammar versus a prompt that forgot to ask for JSON"* — which is not the
question, and a reviewer would reject it on sight.

**What C3 should claim, and how to measure it honestly.** The interesting
question is what the grammar adds *over a well-specified prompt*, so both arms
must be told the output format, including a worked JSON example; only arm B
additionally gets the grammar. The expected outcome is a smaller but real gap —
published figures for unconstrained small models typically land in the 40–80%
valid-JSON range, not 0%.

Note the tension this creates with §7.4, which says the grammar is not injected
into the prompt. Describing the *format* in prose is not injecting the grammar,
and both arms get the same description, so the ablation stays clean. It does cost
prompt tokens, and prefill is already 63% of a step (E2) — that trade-off is
itself worth reporting.

**Status: E4 is void. Superseded by E4b below.** Raw data retained at
`bench/results/E4-void-strawmanned.log` as evidence of the methodology fix.

---

## E4b — Grammar ablation, fair prompt (§14.3 rows A and B)

*2026-08-11 · `94d1c75` · Gemma 3 1B Q4_K_M · `ScreenCorpus` · greedy ·
`max_tokens` 192 · chunked 4/invocation (E6) · battery 94%→, thermal `NONE` ·
artefact: `bench/results/acceptance-raw.log` / `.json`*

Both arms receive **byte-identical prompts**, now including the output format
with worked examples. The grammar is the only variable.

| metric | A (naive) | B (+grammar) |
|---|---|---|
| cases | 13 | 13 |
| valid actions | 6 | 13 |
| **valid-action rate** | **46.2%** | **100.0%** |
| median latency | 57.0 s | 67.7 s |
| p90 latency | 73.2 s | 70.4 s |
| median prefill | 46.9 s | 48.9 s |
| median decode | 6.1 s | 16.9 s |
| grammar sampling | — | 11.8 s |
| mean prompt tokens | 788.5 | 788.5 |
| mean output tokens | **51.6** | **31.7** |
| truncated | 0 | 0 |

**§13 acceptance: PASS** — 13/13 schema-valid under grammar.
**§14.3 A-vs-B expectation: PASS.**

### Reading it honestly

**46.2% is the credible number.** It sits squarely in the 40–80% band the
literature reports for unconstrained small models, which is the main reason to
believe this run and disbelieve E4's 0%. A well-prompted 1B model gets the format
right about half the time; the grammar takes that to certainty.

**The grammar makes output *shorter*, not longer** — 31.7 tokens against 51.6.
Unconstrained, the model spends tokens on prose, restatement and occasional code
fences; constrained, it cannot. Worth reporting because the intuition runs the
other way: constraint is usually assumed to cost tokens.

**Constrained decoding costs ~11.8 s of the 16.9 s decode**, ~70%, from grammar
evaluation over Gemma's ~262k vocabulary at sampler-chain head. That is the price
of the correctness-first placement, and it is what the optimistic scheme (E13,
Phase 3) is expected to recover.

**Latency is dominated by prefill in both arms** (~47 s of ~57–68 s), and the
prompt grew from 547 to 788 tokens when the format description was added. That
is the cost of a fair ablation, and it makes KV prefix reuse more valuable rather
than less.

### What it does not show

Structural validity, not task success. A valid action can still name the wrong
element — the precondition gate's job (§7.5), measured separately. **The paper
must not permit 100% to be read as a success rate.**

n = 13 of 24 planned; the remainder were lost to E6 process kills. Adequate to
separate 46% from 100%; **not** adequate for a published confidence interval.
Rerun with the full corpus and repetitions (E14).

**Validity is judged by `AxonJson.strict`** — the same parser the planner uses,
with `ignoreUnknownKeys = false`. A laxer parser here would flatter arm A by
accepting output the real system rejects.

**Arm A is given every advantage.** Its output is passed through a
brace-matching extractor first, so leading prose ("Sure! Here's the action:")
does not count against it. §14.3 exists to show B beating A; an A penalised for
chattiness rather than for malformedness would be a straw man.

**What it does not show — and this is the paper's most important caveat.**
Validity is *structural*, not *semantic*. In one recorded generation the model
targeted `"Ammi"`, a non-interactive label pruned from `CompactState` — a
perfectly well-formed action naming an element that cannot be acted on. C3 claims
only that malformed actions become unreachable; catching well-formed-but-wrong
targets is the executor's precondition gate (§7.5), and the fact that a second
mechanism is needed is precisely why AXON's reliability argument is
architectural. The paper must not let a 100% valid-action rate be read as a task
success rate.

---

## E5 — Optional whitespace in the grammar

*2026-08-11 · `d5b4c51` · `ScreenCorpus` `whatsapp_conversation`*

§10.6's reference grammar threads `ws ::= [ \t\n]*` between tokens, following
`json.gbnf`.

| grammar | output tokens (1B) | E2B behaviour |
|---|---|---|
| with `ws` slots | 43 | pretty-printed; **hit token cap mid-object** |
| without | 38 | compact |

**Result.** −12% output tokens, and one truncation failure mode removed
entirely.

**Interpretation.** Optional whitespace is right for a *parser* and wrong for a
*generator*: every permitted space is a token the model may spend, and on-device
those tokens are the budget. Worth stating in the paper as a general finding —
grammars ported from parsing contexts carry permissiveness that costs real
latency when used for constrained generation.

---

## E6 — Sustained-load process termination

*2026-08-11 · observed across three independent runs*

**Observation.** The instrumentation process is SIGKILLed by TECNO's `Griffin`
memory manager after roughly seven minutes of sustained load, while in the
foreground with the screen on:

```
Griffin/AdjClean: Kill dev.axon.android.inference.test/10288,pid:…,adj:0,mem:909Mb
Process: Sending signal. PID: … SIG: 9
```

Reproduced with a 0.8 GB model and a 3.1 GB model, plugged in, screen unlocked,
`adj:0` (foreground). Not attributable to memory exhaustion alone —
`MemAvailable` was ~3.9 GB at the time of one kill.

**Consequence for methodology.** Long-running on-device measurement on this class
of hardware **must be checkpointed**. AXON emits one `RECORD|` line per
observation at the moment it is made and aggregates on the host
(`tools/run-acceptance.sh`, `tools/aggregate-acceptance.py`), so a kill costs the
observations not yet reached rather than the run.

**Why this belongs in the paper, not just the repo.** It is a genuine finding
about deploying agent workloads on budget Android: OEM power managers terminate
sustained foreground compute, independent of the app's own behaviour. Any
on-device agent intending long-horizon autonomy has to survive that, and
published work that only ever ran on flagships or emulators will not have seen
it.

**Secondary observation.** Throughput degrades within a run as the device heats
(44 °C observed). Chunk 1 completed 4 cases in its window; chunk 2 completed 1.
This is §14.2's **thermal ceiling** metric measuring itself, and it strengthens
the C1 argument: compiled skill replay costs zero model calls and therefore
generates no heat.

---

## E15 — Energy per planning step

*2026-08-11 · `39ac4d0` · Gemma 3 1B Q4_K_M · grammar-constrained · 4/8 threads ·
`n_ctx` 2048 · `max_tokens` 48 · battery 89%, thermal `NONE`, screen on ·
artefact: instrumented `InferenceSweepTest`*

| | |
|---|---|
| prompt / output tokens | 800 / 31 |
| wall clock | 60.4 s (prefill 45.1 s, decode 15.3 s) |
| current during work | 358 mA |
| idle baseline | 185 mA |
| **energy above idle** | **83.3 J** |
| energy total | 126.4 J |
| samples | 239 @ 250 ms |
| **planning steps per 5000 mAh charge** | **~831** |

**Why this metric is worth introducing.** On-device agent papers report latency
and task success. Almost none report joules — yet on a battery device joules are
the binding constraint. A user does not abandon an assistant because it took a
minute; they abandon it because it cost 8% of their battery.

**What it makes concrete.** A six-step cold task costs roughly **500 J**, about
0.7% of a full charge, and the device can serve ~138 such tasks before flat. A
compiled skill replay performs the same task with **zero model invocations** and
therefore essentially zero inference energy. That reframes C1′ in the terms the
person holding the phone actually experiences: not "5× faster" but *"the
difference between 138 tasks per charge and effectively unbounded"*.

It also gives the scaffolding-versus-scale trade (POSITIONING §3.1) a second
axis. A larger model does not merely take longer per step — it costs more charge
per task, and on a 5000 mAh pack that is a hard ceiling rather than an
inconvenience.

**Method, and its limits, stated plainly.** `BATTERY_PROPERTY_CURRENT_NOW` is
whole-device current from the fuel gauge, not per-process. The figure reported is
therefore a **differential**: mean current during the work minus an idle baseline
sampled immediately before, which subtracts screen, radios and background apps to
first order. Pack voltage is assumed at the 3.85 V Li-ion nominal midpoint, since
Android does not expose instantaneous voltage on every device — a few percent
error across the usable range.

This is **not** a power monitor, and the paper must say so. It is enough to
compare configurations on one device, which is the comparison the argument needs.
An external power monitor would be the obvious strengthening, and is the kind of
equipment a lab has and a student does not.

**Threat to validity.** n = 1, screen on, charging over USB. Screen draw is in
the baseline and largely subtracts out, but charging current is not stationary
and could bias the delta. Repeat on battery with the screen off via a foreground
service before publishing (E16).

---

## E18 — Prompt-based self-healing fails at ~1B

*2026-08-12 · `9706b79` · Gemma 3 1B Q4_K_M · live device, full loop ·
goal: "open whatsapp" · `stepBudget` 4, `healBudget` 2*

```
ESCALATED — 3 steps, 3 model calls, 182 s, 3 heals
  1. gated  tap content_desc="Whatsapp"
  2. gated  tap content_desc="Whatsapp"
  3. gated  tap content_desc="Whatsapp"
```

**Every safety mechanism worked.** The grammar produced a well-formed action each
time; the precondition gate refused all three, so the device was never touched;
the heal budget stopped the run after three attempts rather than spending all
fifteen steps; and it escalated to the user instead of failing silently.

**The finding is what the model did in between.** `FailureContext` was populated
and rendered into the prompt on attempts 2 and 3, naming the failed action
explicitly and instructing *"already tried and failed on this screen … choose a
DIFFERENT action."* The model repeated itself anyway, twice.

That is worth reporting as a result rather than patching around quietly:
**prompt-based self-healing does not hold at ~1B.** The instruction is present
and comprehensible; it simply does not outweigh whatever made the action look
best initially, and nothing about the screen has changed to make it look worse.

Two secondary observations from the same run:

- The model chose `tap` over `launch_app` despite an explicit prompt rule
  (*"To open an app, use launch_app with its package name"*). Instruction-following
  degrades in the same way.
- It was perceiving AXON's own UI, since the agent runs inside the Activity. The
  §7.9 gateway foreground service is what fixes that, and this run is the
  argument for prioritising it.

### Response: two structural constraints, not a better prompt

Consistent with §2.3 — *"make the model's freedom smaller"* — the response is to
make the failure unreachable rather than discouraged.

1. **`RepetitionGuard`.** An action that failed on a given screen cannot be
   re-proposed while that screen is unchanged. Keyed on `(screenHash, action)`
   rather than action alone, because tapping "Send" can fail on one screen and be
   correct two screens later; blacklisting outright would break the task the
   agent was blocked from starting.

2. **`ScreenGrammar`.** The grammar is specialised each step so `target.value`
   can only be a label present on the current screen. `"Whatsapp"` is not in the
   alternation, so **the sampler cannot produce it** — a hallucinated target stops
   being caught and becomes unreachable.

The second is the more interesting one. The base grammar constrains the *shape*
of an action; this constrains its *reference*. The precondition gate is demoted
from primary defence to backstop, which is where a runtime check belongs when a
decoding constraint can do the job.

| mechanism | makes impossible | when |
|---|---|---|
| GBNF grammar (C3) | malformed actions | during sampling |
| **screen grammar (C3′)** | **naming an element that is not there** | **during sampling** |
| repetition guard | re-proposing a failed action here | before acting |
| precondition gate (§7.5) | anything the above missed | before acting |

**Costs, stated.** The grammar is rebuilt per step (microseconds against a ~50 s
step). Labels are capped at 40 to match what the planner is shown, so an element
beyond the cap is unnameable and must be scrolled to. `launch_app` stays
unconstrained by screen, since package names are deliberately not on screen. An
empty screen falls back to the base grammar, or the agent would have no legal
move at all (§17).

**Validation.** `tools/check-grammar.sh` now validates the *specialised* grammar
too, built from real corpus labels — the generated grammar is the one that
reaches the sampler, and it is assembled from untrusted app text that can contain
quotes and backslashes. An unescaped label produces a parse failure, and a parse
failure means llama.cpp silently declines to constrain generation at all.

**Not yet measured.** Whether screen grounding raises task success, and what it
costs in tokens. That is E19, and it needs the gateway service first so the agent
perceives the app it is operating rather than its own UI.

---

## O1 — Force-stop revokes the accessibility grant

*Operational finding, 2026-08-12. Not an experiment; recorded because it will
recur and because it has a methodological consequence.*

`adb shell am force-stop dev.axon.android` **permanently disables AXON's
accessibility service.** Android treats force-stop as a signal that the app is
untrusted and drops it from `enabled_accessibility_services`; the setting is not
restored when the app next starts. Every subsequent run failed with
`accessibility service is not enabled` until the user re-enabled it by hand.

**Consequences for the evaluation.** Any benchmark harness that force-stops
between runs — a natural way to get a clean process state — silently disables the
thing under test. The failure presents as the agent being unable to see the
screen, which is easy to misread as a driver bug. Restarting the *process* is
fine; force-stopping the *app* is not.

**Consequence for the guardrail.** The permission can be restored from adb with
`settings put secure enabled_accessibility_services`, and it was deliberately not
done that way here. §16 states that only the user enables the service, by hand,
and the app's own UI says so; re-granting it through a shell backdoor during
development would make the developer an exception to a guarantee the thesis
claims is structural. The cost is ten seconds of manual work per occurrence,
which is the correct price.

Worth a line in the paper's deployment-substrate section: platform security
mechanisms interact with agent research in ways that look like bugs. This one
costs a confused hour if you have not seen it before.

---

## O2 — "Foreground service" does not imply "visible" on Android 13+

*Operational finding, 2026-08-12. A §16 violation that shipped and was caught by
the user noticing an absent notification.*

```
POST_NOTIFICATIONS: granted=false
startForegroundCount: 0
AxonLlama: loaded model …          ← the agent was running
```

The gateway ran a task with **no notification anywhere on the device**.

**Cause.** Since Android 13, `POST_NOTIFICATIONS` is a *runtime* permission. AXON
declared it in the manifest and never requested it. A foreground service whose
notification is suppressed does not fail — it runs, silently. The service had
`startForegroundCount: 0` and kept working regardless.

**Why this is worth recording rather than quietly fixing.** The project's own
documentation asserted that §16's visibility guarantee was *enforced by the
platform*: "Android tears down a foreground service that fails to post its
notification." That is no longer true, and building on it produced precisely the
covert operation §16 forbids. The guardrail existed, was believed, was documented
— and was not there.

It is also the strongest available illustration of why the paper's guardrail
claims must be phrased as *mechanisms*, not intentions. "We run as a foreground
service" sounds like a visibility guarantee and is not one.

**Fix, in the shape of every other guardrail here.** The service now checks
`areNotificationsEnabled()` plus channel importance before starting any task, and
**refuses to run** when operation would be invisible. Not a warning, not
degraded mode — refusal. The app also requests the permission at launch rather
than at first use, so the refusal is not the user's first experience of it.

**For the thesis.** The claim to make is now: *AXON cannot operate the device
while hidden from the user, because it checks and declines* — verifiable by
revoking notification permission and observing that tasks refuse to start. That
is a testable property. The previous claim was an assumption about platform
behaviour that happened to be false.

---

## E18b — Screen grammar holds; semantic selection is the remaining gap

*2026-08-12 · `3f5de5a` · Gemma 3 1B Q4_K_M · live device via gateway service ·
goal: "open whatsapp" · launcher in foreground · screen grammar + repetition
guard active · `stepBudget` 6*

**AXON's first real action on a live app.** The agent perceived the launcher,
planned, passed the gate, dispatched a tap — and opened the **phone dialer**
instead of WhatsApp. Run stopped manually at that point; `mCallState=0`, nothing
was dialled.

### Every structural defence held

| mechanism | verdict |
|---|---|
| GBNF grammar (C3) | ✅ well-formed action |
| screen grammar (C3′) | ✅ selected a label genuinely present on the launcher |
| precondition gate (§7.5) | ✅ target resolved, action allowed |
| repetition guard | ✅ no loop — contrast E18, three identical attempts |
| **model's semantic choice** | ❌ **wrong element** |

Compare directly with E18 on the same goal: three identical hallucinated actions,
nothing dispatched, 182 s wasted. Here the agent acted on the first attempt and
acted on something real. The structural fixes did what they were built for.

### The finding

**This is where scaffolding stops substituting for scale.** Every failure mode
encountered so far had a structural remedy — malformed output, absent targets,
repetition, invisibility. This one does not. Choosing which of several legal,
present, well-formed options corresponds to "whatsapp" is semantic selection, and
no decoding constraint can supply it.

It is also a live demonstration of the caveat this log has been repeating: **a
100% valid-action rate (E4b) is not a task success rate.** Every action in this
run was valid. The task still failed. Anyone reading the C3 numbers as success
numbers now has a concrete counter-example, which is worth more in the paper than
another paragraph of hedging.

For POSITIONING §3.1's substitution surface, this locates a point on the curve:
at 1B, with full scaffolding, structural validity is solved and semantic
selection is not. Whether 2B or 4B closes it is exactly what the surface
measures.

### Candidate remedies, none structural

- **Router model** (§11) — a small classifier mapping the goal to a target app or
  package before the planner sees the screen, so "whatsapp" resolves to
  `com.whatsapp` rather than to whichever icon looks plausible.
- **Prefer `launch_app` when the goal names an app.** The prompt already says so
  and the model ignored it (E18); making it structural would mean restricting the
  grammar to `launch_app` when the goal parses as an app reference.
- **Few-shot examples** of goal→action for app-opening.
- **Label similarity in the prompt**, ranking screen elements by lexical distance
  to the goal so the right one is visibly nearer.

The second is the most AXON-shaped and the cheapest to test.

### Safety note

The run was stopped by hand once the dialer was open, with five steps of budget
remaining. Nothing had gone wrong, and the point of stopping was that the next
action was unconstrained by anything except the budget. §16 requires explicit
confirmation for irreversible actions — placing a call among them — and this run
is the argument for implementing that gate before any longer autonomous run. It
is currently declared in `Capability.ALWAYS_CONFIRM` and **not yet enforced**.

---

## E21 — Collapsing the grammar for app-launch goals

*2026-08-12 · `af9c73d` · implemented and unit-tested; **not yet measured on
device***

E18b's failure was semantic selection: a well-formed, screen-grounded,
gate-approved action opened the phone dialer instead of WhatsApp. The prompt
already said *"to open an app, use launch_app with its package name"* and the
model ignored it, as it ignored the failure context in E18.

**"Open X" is one of the few goals whose correct action is determinable without
the model.** If the device has an app named X, the right move is `launch_app`
with X's package, whatever is on screen. So when a goal parses as *purely* a
launch intent and the name resolves to an installed package, the grammar
collapses to a single production — one legal action, one token path. The dialer
becomes unreachable rather than merely wrong.

This is the strongest constraint anywhere in AXON:

| grammar | admits |
|---|---|
| base (C3) | any well-formed action |
| screen-grounded (C3′) | …naming an element that is present |
| **launch (E21)** | **exactly one action** |

Safe only because the determination happens *outside* the model, by a package
lookup that cannot be wrong about whether an app exists.

**Two limits, both deliberate.** Multi-step goals ("open whatsapp and message
ammi") are not launch intents — narrowing for a whole task would leave the agent
unable to act after the launch and treat the task as done at step one. And the
resolver matches exact → prefix → contains, stopping at the first tier with
exactly one candidate; anything ambiguous returns null and falls back to ordinary
planning, because a wrong resolution would make the wrong app *the only reachable
outcome*.

**Scope, honestly.** One narrow class of goal. Not a general solution to semantic
selection, and the paper must not present it as one — the substitution surface
still has to measure where model capacity binds. What it demonstrates is the
method: *when a goal's correct action is determinable without the model's
judgement, remove the judgement.*

**Pending measurement (E21b).** Re-run "open whatsapp" on device and confirm it
launches WhatsApp rather than the dialer.

---

## E17 — Cold planning vs compiled replay: **the C1′ headline**

*2026-08-12 · `af9c73d` · Gemma 3 1B Q4_K_M · TECNO Camon 20 · 2 steps ·
screen-grounded grammar · synthetic driver · battery ~89%*

Both arms perform the **same two actions** against the **same screens**. The only
difference is where the actions come from — a generation per step, or a frozen
`CompiledSkill`. That isolates exactly the quantity C1′ claims to remove: the
cost of consulting the model.

| | cold PLAN | compiled REPLAY |
|---|---|---|
| model calls | 2 | **0** |
| output tokens | 62 | — |
| **wall clock** | **142,917 ms** | **17 ms** |
| inference energy | 89.05 J above idle | **0 J** (exact — none occurred) |
| outcome | — | SUCCESS, fully deterministic |

### **Speedup: 8,407×**

§13's O4 acceptance criterion asks for *"≥5× faster than a cold LLM run"*. The
measured figure is three orders of magnitude beyond it, because on this hardware
a planning step is not a little slow — it is 71 seconds.

### What is NOT claimable, and why the first run printed it anyway

The harness initially reported an **energy ratio of 56,119×**. That number is an
artefact and has been removed from the output.

Replay completes in ~17 ms; [EnergyProbe] samples every 250 ms. The replay arm
therefore yields **one sample** — its energy is *below the instrument's
resolution*, not measured as zero. Dividing by it produces a spectacular figure
that means nothing. The tooling now prints the sample count and the probe
interval instead, and states the defensible claim:

> Inference energy is **89.05 J cold and exactly 0 J on replay** — exact rather
> than measured, because no inference occurred.

Also note the cold arm's mean current (161 mA) came out *below* the idle baseline
(193 mA), which is why `joulesAboveIdle` (89 J) is well under `joulesTotal`
(196 J). Whole-device current is noisy over a 143-second window and the baseline
was sampled in a different thermal state. **The energy figures are order-of-
magnitude indicators, not precise measurements**, and the paper must say so. An
external power monitor is the fix (E16).

### What this makes concrete

A six-step task: **~7 minutes cold, ~50 ms replayed.** The user-facing claim is
not "5× faster" but *the difference between a task you would never wait for and
one that is instant* — and on a battery device, between ~138 tasks per charge and
a number bounded by something other than energy.

### Threats to validity

- **n = 1.** Repeat with n ≥ 5 before publishing (E14).
- **Synthetic driver.** Deliberate: on a live app the cold arm frequently picks
  the *wrong* element (E18b), and the comparison would then measure task success
  rather than decision cost. It also means the replay figure excludes real
  gesture dispatch and UI settle time, which would add a few hundred ms — still
  leaving three orders of magnitude.
- **The skill was hand-written**, not compiled from a live run, because obtaining
  two clean runs on a real app needs the planner to choose correctly twice.
  `SkillCompilerTest` covers the compiler producing this shape.

---

## Open measurements

Required before publication. Listed here so gaps are visible rather than
discovered late.

| id | measurement | blocked on |
|---|---|---|
| E7 | Full 500-generation valid-action rate (§13) | Phase 3 speedups; ~14 h chunked otherwise |
| E8 | Task success rate, configs A–E (§14.3) | Phase 3 (control loop) |
| E9 | Recovery rate on injected failures (§14.2, C2) | Phase 4 |
| E10 | LLM calls and latency, cold vs replay (O4, C1) | Phase 5 |
| E11 | Vulkan vs CPU backend (D8) | Vulkan build + Mali driver validation |
| E12 | Quantisation vs task success (Q4_K_M / Q3_K_M / Q2_K_XL) | Phase 7 |
| E13 | Prefill cost with KV prefix reuse | Phase 3 |
| E14 | Variance across repeated runs for E2, E3, E15 | rerun with n ≥ 5 |
| E16 | Energy on battery, screen off, foreground service | Phase 6 |

| E18b | Re-run "open whatsapp" with repetition guard + screen grammar | needs gateway service |
| E19 | Task success and token cost, screen-grounded vs base grammar | Phase 7 |
| E20 | Does a router model close the semantic-selection gap (E18b)? | Phase 3 |
| E21b | On-device confirmation that E21 opens the right app | needs device |
| E21 | Grammar restricted to launch_app when the goal names an app | Phase 3 |
