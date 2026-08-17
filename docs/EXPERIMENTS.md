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
| cases | 15 | 15 |
| valid actions | 7 | 15 |
| **valid-action rate** | **46.7%** | **100.0%** |
| median latency | 54.9 s | 68.3 s |
| p90 latency | 63.5 s | 73.9 s |
| median prefill | 47.5 s | 48.6 s |
| median decode | 6.2 s | 17.8 s |
| grammar sampling | — | 12.2 s |
| mean prompt tokens | 784 | 784 |
| mean output tokens | **38.9** | **32.5** |
| truncated | 0 | 0 |

**§13 acceptance: PASS** — 15/15 schema-valid under grammar.
**§14.3 A-vs-B expectation: PASS.**

### The number is stable, which matters more than the number

*Re-run 2026-08-17 to extend coverage. Superseded figures: n=13, A = 46.2%.*

| | n | A (naive) |
|---|---|---|
| first run | 13 | 46.2% |
| extended | **15** | **46.7%** |

Half a point across two independent runs. A single-shot 46% invites the reading
that the arm got unlucky; two runs landing on the same value make it a property
of the configuration. It also sits squarely inside the 40–80% band the literature
reports for unconstrained small models, which is the main reason to believe this
figure and to disbelieve E4's voided 0%.

B remains **100%, exactly**, and by construction rather than by luck: an action
the grammar could not have produced has no token path. A value below 100% here
would not mean the model did poorly — it would mean the grammar was not installed
(D9's failure mode).

### 15 of 24, and the way the other 9 were lost is itself data

The run was chunked at 3 cases per invocation (E6). Cases recorded per chunk:

```
offset:   0    3    6    9   12   15   18   21
gained:  +3   +1   +3   +1   +3   +1   +3   +0
```

**Every other chunk was cut short after one case**, reproducibly, across the
whole run. That is a sharper version of E6's finding than E6 itself recorded:
termination is not merely "after about seven minutes of sustained load" but
follows a pattern stable enough to predict.

The plausible mechanism is memory rather than time — each chunk loads an 806 MB
model into a fresh process, and if the previous process has not fully released
before the next allocates, every second chunk meets a device already under
pressure. **That is a hypothesis, not a measurement**; distinguishing it from
thermal throttling needs `meminfo` sampled per chunk, which this harness does not
do. Recorded because the pattern is too regular to omit, and labelled because it
is not yet explained.

Coverage is stated rather than rounded away: **the corpus has 24 screens and this
result covers 15.** The nine absent cases are not a sample — they are the tail of
each killed chunk, so they are the *later* screens in corpus order, and any claim
that the rate generalises to the whole corpus is unsupported by this run.

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

---

## E22 — Learning survives the process

*2026-08-17 · SQLDelight 2.3.2 on real SQLite files · `core:jvmTest`, 15 tests ·
no device required*

E17 measured replay at **17 ms and 0 model calls** against a 142,917 ms cold
plan. That number was real, and until this entry it was reachable only on a
second attempt inside one run of the app, because both stores were `mutableMap`s.
The process ending — which on this hardware happens without the user asking, as
the OEM power manager SIGKILLs sustained foreground compute after ~7 minutes
(E6) — took every learned skill with it.

**What is asserted.** Every test opens a real SQLite file, writes, then *closes
the driver entirely* and opens a new one before reading. An in-memory database
would pass while testing nothing: the property is precisely that the data
outlives the connection that wrote it.

| property | assertion |
|---|---|
| skill survives restart | a saved skill still matches its goal after reopen |
| body round-trips exactly | `assertEquals(original, loaded)` — selectors, assertions, bindings, provenance |
| trace stays compilable | screen hashes survive shredding, so a reloaded trace can still be compiled |
| counters accumulate | replay/repair counts persist and keep adding across three restarts |
| recompile keeps history | 41 replays are not reset when a skill is recompiled after drift |
| §16 erase cascades | deleting history leaves no orphaned actions |
| corruption is contained | one unparseable skill body does not fail the load of the others |
| SQL == Kotlin | the SQL clean-run gate agrees with `isCompilable` |

The last row is the one that earned its place. See D11: written first as
`outcome = 'success'` against an enum that persists as `SUCCESS`, the compile
gate matched nothing and returned 0 for every goal forever — so **AXON would
never have learned anything, silently**, because "not enough clean runs yet" is a
legitimate answer that raises no error. Restating a Kotlin predicate in SQL is a
real cost, and the mitigation is asserting the two agree rather than testing
either alone.

### What changes for the claim

C1′ can now be stated without a qualifier. Before: *repeated tasks cost zero
model calls, within a session.* After: **repeated tasks cost zero model calls.**

It also makes the property visible rather than merely true — the app reads its
skill count at launch, so opening AXON after a reboot shows what it already
knows, before the user does anything. That is the only part of C1′ a person can
see without a stopwatch.

### Not yet measured

- **On-device (E22b).** These are JVM tests against the same generated queries
  the phone runs, which is C4's dividend and not a substitute for the device.
  What is unmeasured is the *cost*: hydrate time at launch with a realistic skill
  count, and whether writing a trace at task end is perceptible.
- **Growth.** `trace_step` is unbounded by design. No retention policy exists and
  none should be invented before the growth rate is known.
- **Concurrency.** One database instance per process is enforced, but the
  gateway service and the UI touching the store during a task is untested under
  contention.

---

## E21b/c — E21 on device: inert, then correct, then terminating

*2026-08-17 · TECNO Camon 20 (Helio G85) · Gemma 3 1B Q4_K_M · battery 38→37% ·
CPU 34→42 °C · live WhatsApp, gateway foreground service*

E21 collapsed the planner's grammar to a single `launch_app` when a goal names an
installed app, and was **implemented, unit-tested and never measured on device**.
Measuring it took three runs to reach a success, and each failure was a different
kind of instructive.

| run | outcome | model calls | wall | steps | cause |
|---|---|---|---|---|---|
| 1 | ESCALATED | 3 | 179 s | 1 | resolver blind — E21 never fired (E21c) |
| 2 | BUDGET_EXHAUSTED | 6 | 426 s | 6 | E21 fired perfectly; nothing could tell it had finished |
| 3 | **SUCCESS** | **1** | **66 s** | **1** | success oracle added |

### E21c — a §16 decision silently disabled the mechanism

Run 1 emitted `tap content_desc="Send"` — a hallucinated element on AXON's own
screen. The precondition gate refused it before dispatch (`pre_ok=0`, *"no
element matching content_desc=\"Send\" exists on this screen"*), so nothing wrong
reached the device. But the grammar had **not** collapsed, and the reason was not
in the planner at all.

`PackageAppResolver` resolves "whatsapp" → `com.whatsapp` by reading the
launcher's list. Android 11+ hides installed packages unless an app declares
`<queries>`, and AXON declared none — because §6.3 excludes `QUERY_ALL_PACKAGES`
as stalkerware-adjacent, and nothing narrower had been added in its place.
Confirmed from the platform: `dumpsys package queries` showed **zero** entries
for `dev.axon.android` under both *queries via package name* and *queryable via
interaction*.

So `resolve()` returned null for every app, E21's grammar collapse never fired,
and **the strongest constraint in the system was inert on hardware while passing
every unit test.** Every downstream symptom was misleading: a null resolution is
a legitimate answer meaning "ambiguous", so the planner fell back to ordinary
screen-grounded planning and behaved plausibly badly.

The fix is *not* `QUERY_ALL_PACKAGES`. A `<queries>` element scoped to
`MAIN`/`LAUNCHER` grants visibility of apps that have a launcher icon and nothing
else — exactly the set the user already sees on their home screen and can already
tap. Visible apps went **0 → 91**. AXON learns nothing about the device its owner
could not learn by looking at it, and scoping visibility explicitly is a better
§16 story than the accidental version it replaced, where the restriction held by
omission and the cost was a feature failing silently.

`PackageAppResolver` now logs loudly when the list is empty, because the
distinguishing symptom of this bug was that there was none.

### E21b — the grammar collapse worked, and the task still could not stop

Run 2 is the interesting failure. With the resolver fixed, **all six generations
emitted `launch_app com.whatsapp`**, the gate approved every one and the verifier
confirmed every one (`pre_ok=1, post_ok=1` throughout). Against E18b — where a
well-formed, gate-approved action opened the phone dialer — the mechanism did
exactly what C3 claims.

And the run ended in `BUDGET_EXHAUSTED` at 426 seconds, having launched WhatsApp
six times. Nothing was wrong with any individual decision. The runtime had no way
to know it was finished: a caller with no oracle passes one that never fires, and
`RepetitionGuard` blocks actions that *failed*, so it correctly stayed silent
while every step succeeded.

The oracle is determined by the same reasoning that collapses the grammar. If
"open X" has exactly one correct action without consulting the model, it has
exactly one success condition too — **X is in the foreground** — read off the
observed state, asking the model nothing. Same argument as C2 replacing LLM
self-assessment with a deterministic verifier.

Run 3: **1 model call, 66 s, 1 step, SUCCESS.** Two clean runs then crossed §7.7's
threshold and compiled `open_whatsapp`.

### What this says about the method

E21's own framing — *"when a goal's correct action is determinable without the
model's judgement, remove the judgement"* — turned out to be half a rule. The
completion test is determinable by the same lookup, and omitting it produced a
system that made six perfect decisions and could not stop. Constraining what an
agent may *do* is not sufficient; something must also define when it is *done*.

---

## E22b/c/d — Persistence on device, and two bugs it exposed

*2026-08-17 · TECNO Camon 20 · same session as E21b/c · live WhatsApp*

### E22b — learning survives process death, measured

| measurement | result |
|---|---|
| hydrate, empty database, cold open | **46 ms** (0 skills) |
| hydrate, after process death | **39 ms** (1 skill) |
| trace write, end of task | **10–31 ms** (1–6 steps) |
| skill survives `am force-stop` | **yes** |

The restart is a real `am force-stop` — the same thing the OEM power manager does
to sustained foreground compute (E6, O1) — verified by confirming the process was
gone before relaunching. The fresh process logged `E22b hydrate: 1 skill(s) in
39 ms` before the user touched anything.

Both costs are negligible against a ~60 s planning step, which was the prediction;
it is now a number. Note the empty database opened *slower* (46 ms) than the
populated one (39 ms) — first-open schema work dominates, and deserialising one
skill does not register. That does not extrapolate: hydrate is O(skills), and the
figure to watch is a store with hundreds.

### E22c — the compiler was dropping every payload that is not a selector

The first compiled skill **could not be replayed**, and the way it failed was
worse than failing.

`open_whatsapp` compiled to a step with no package at all. `SkillReplayer` reads
`launch_app`'s package from the step, found nothing, and could not build the
action — so the replay failed and fell through to a 66-second cold plan, **while
`replay_count` incremented**. The store recorded a replay that had not happened.

Cause: `compileStep` derived a step's entire payload from `action.target`, which
is `null` by definition for `launch_app`, `press_key` and `wait`, and never
carried the direction for `swipe` or `scroll`. **Only `tap` and `long_press`
compiled intact.**

Two of the losses were more dangerous than a failed replay. With no payload the
replayer *defaulted*: `press_key` → BACK, `swipe` → UP, `scroll` → DOWN. A skill
that recorded "press HOME" would have replayed "press BACK" — a wrong action
dispatched confidently at a live device. A compiled skill is checked by no
grammar, no gate and no planner; all three defences sit on the PLAN path. Replay
answers to none of them, so its correctness has to come from the compiler, and
this is the first evidence that the replay path needed a defence of its own.

**Why it survived to here.** E17 — the 8,407× headline — used a hand-written
`tap` skill, chosen because obtaining two clean runs on a real app required the
planner to choose correctly twice, which E18b showed it did not. `tap` is one of
the two types that happened to work. The measurement was sound; its fixture
avoided the bug.

Fixed by giving `CompiledStep` an `args` map, populated from an exhaustive `when`
so a new action variant cannot compile without being handled, and read back with
a fail-closed replayer that refuses rather than guessing. `ActionRoundTripTest`
now round-trips **every** action in `DeviceAction.ACTION_TYPES`, plus a test
asserting the corpus covers the wire format so a new action cannot go untested.

One existing test had been green *because* of this bug: its trace began with
`launch_app`, so the replay died at step one and never reached the missing-
parameter tap the test claimed to be about. It now asserts on taps specifically.

### E22d — zero model calls is not zero cost until the app stops loading the model

With replay working, "open whatsapp" replayed correctly with **0 model calls** —
in **42.8 seconds**.

The gateway loaded the 806 MB GGUF before every task, unconditionally, and only
then discovered the task needed no model. C1′ was exactly right inside the loop
and entirely invisible to the person holding the phone.

Asking the skill store *before* paying for the model:

| | wall clock, cold process |
|---|---|
| replay, model loaded first | 42,801 ms |
| **replay, model skipped** | **2,276 ms** |

**18.8× faster**, and the remaining 2.3 s includes the adb round-trip, service
start, database open, skill match and WhatsApp's own cold start — none of which
is AXON deciding anything.

This is a claim about where a contribution has to be true. "Replay costs zero
model calls" was a property of `AxonRuntime` and not of AXON, and no measurement
taken inside the runtime could have revealed the difference. `AxonRuntime.planner`
is now nullable, which makes "replay only" a configuration the type system
expresses rather than a state the app has to remember to avoid.

### Threats to validity

- **One skill, one device, n = 1** on every timing here.
- **The 2,276 ms figure is end-to-end wall clock**, not replay in isolation, and
  is therefore an upper bound on AXON's own cost rather than a measurement of it.
- **Hydrate is O(skills)** and was measured with one. The number that matters for
  a real user is a store with hundreds, and that is E22e.
- Battery fell only 38→37% across the whole session, so thermal throttling
  (34 → 42 °C CPU) is the more likely confound in the wall-clock figures.

---

## E23 — One phrasing per intent: paraphrase matching for launch goals

*2026-08-17 · TECNO Camon 20 · skill compiled from "open whatsapp" · battery 42% ·
CPU 34 °C · 8 unit tests + on-device confirmation*

### The gap

Skill matching aligns an utterance against the stored `goal_pattern` literally.
A skill learned from **"open whatsapp"** was therefore missed by **"launch
whatsapp"** — same request, same package, different verb — and the user paid a
full ~66 s cold plan for a task the system had already learned.

That is the correct failure direction (a false match replays the wrong skill at a
live device; a miss only costs time) but it is a bad experience for the reason
that matters to C1′: *the system was right and the person could not tell why it
was slow.* "It gets faster the more you use it" is not a property a user can rely
on if it depends on them repeating themselves word for word.

### The fix, and why it is not a similarity threshold

`AppIntent` already collapsed nine launch verbs — including Roman-Urdu `kholo`
and `khol` (§2.1) — onto one app name, for E21's grammar collapse. The
normalisation existed; it was simply not wired into *matching*.

Both sides of the comparison are now also tried in canonical form. Normalising
the stored pattern as well as the incoming request means a skill compiled under
any verb is reachable by any other, and nothing has to be recompiled.

This is a **lookup against a verb list, not a similarity score**, which is what
makes it safe on the replay path. A verb the list does not contain falls through
to the planner. Adding a synonym is a one-line change with a test, not a
threshold to tune — and there is no value of any parameter at which "open camera"
starts matching a WhatsApp skill.

### Measured on device

The store held one skill, `open_whatsapp`, with `goal_pattern = "open whatsapp"`.
Each request below used a **different verb** and was served by that skill:

| utterance | WhatsApp foreground | model calls |
|---|---|---|
| `launch whatsapp` | (confirmed by counter) | **0** |
| `kholo whatsapp` | **5,075 ms** | **0** |
| `start whatsapp` | **1,379 ms** | **0** |

`replay_count` went 2 → 5 and the trace table stayed at 6 rows — a clean replay
returns early and records no trace, so an unchanged trace count *is* the evidence
that no cold plan ran. The 5,075 ms figure includes a cold gateway-service start;
1,379 ms is the warm case, and both include WhatsApp's own launch.

### What is still not handled, and stays that way

"Text ammi that I'm coming" against `send {message} to {contact} on whatsapp`
still misses. That is genuine semantic matching and needs the embedding model and
ANN index §7.8 specifies — a second model resident on a device that already
struggles with one. It remains a stated limitation, not a gap this experiment
quietly narrows.

The scope claim is therefore precise: **verb paraphrase for app-launch goals,
handled exactly.** One narrow class, chosen because an exact normalisation for it
already existed.

### Threats to validity

- **One app, one skill.** Whether canonicalisation ever produces a *false* match
  in a store with many launch skills is untested; the unit tests cover "open
  camera" not matching a WhatsApp skill, which is the obvious case, not a
  systematic one.
- **Wall clock includes the gateway service start and WhatsApp's own launch**, so
  these are upper bounds on AXON's cost, not measurements of it.
- n = 1 per phrasing.

---

## E24 — Composing known skills for compound goals

*2026-08-17 · implemented and unit-tested (11 tests) · **NOT YET MEASURED ON
DEVICE***

That status line is the point. E21c is why this file now says so explicitly: a
mechanism that passed every unit test was switched off entirely on hardware by
Android's package-visibility filtering, and nothing anywhere reported it. Until
E24 runs on the phone, the claim below is a design, not a result.

### The gap

"Turn on wifi then open whatsapp" matches no single skill, so it cold-plans every
step — even when both halves are already compiled.

On this hardware that is not inefficiency, it is a wall. At ~60 s per planning
step (E2), the corpus's long-horizon tier (8–11 optimal steps, §14.1) costs 8–11
minutes, and the OEM power manager SIGKILLs sustained foreground compute after
about **seven** (E6). **Several long-horizon tasks are not completable by the
PLAN path on this device at all.** Composed from known skills they cost
milliseconds.

That makes composition the sharpest available statement of C1′: skill reuse is
not making a slow task faster, it is making an impossible task possible.

### The fifth application of one idea

| decision | determined by | instead of |
|---|---|---|
| what shape an action may take | GBNF grammar (C3) | the model getting JSON right |
| which elements may be named | the live UI tree | the model recalling the screen |
| which app "open X" means | package lookup (E21) | the model picking an icon |
| when a launch goal is finished | foreground package (E21b) | the model self-assessing |
| **where one task ends and the next begins** | **sequencing words (E24)** | **the model planning across both** |

"Then", "and", "phir", "aur" are syntax. Seeing them needs no model, and asking
one costs ~60 s and may be wrong.

### Splitting is aggressive; composing refuses

`GoalDecomposer` over-splits on purpose. "Send a message to Ali and Ahmed" is one
task and splits into `["send a message to ali", "ahmed"]`.

Teaching a heuristic to recognise that would be semantics again, and wrong at 1B.
Instead the safety sits one level up: **a compound goal composes only if every
fragment matches a compiled skill.** "ahmed" matches nothing, so the composition
is abandoned *before anything is dispatched* and the planner receives the
original sentence intact.

Partial composition was considered and rejected. It would have messaged Ali and
then planned something for "ahmed" — performing an irreversible act the user
never asked for as a separate step. A wrong split now costs a missed opportunity;
it can never cost an action. Same asymmetry as every threshold in `GoalMatcher`.

A composed run also **stops at the first failed part** and falls back to planning
the whole original goal. Continuing would substitute AXON's judgement about which
parts of a request matter.

### What composition is not

Not a new skill. It is a routing decision made per request and never stored — so
it cannot rot, cannot be replayed wrongly later, and needs no compilation gate.
A compound goal asked often enough gets compiled from its own traces by the
ordinary two-clean-runs path.

### E24b - measured on device

*2026-08-17 - TECNO Camon 20 - two compiled skills - battery 24%*

The store held `open_whatsapp` and `go_to_linkedin`, each compiled from its own
two clean runs. One compound request naming both:

> **"open whatsapp then go to linkedin"**

| | |
|---|---|
| `open_whatsapp` replay_count | 8 → **9** |
| `go_to_linkedin` replay_count | 1 → **2** |
| second app in foreground | **4,417 ms** |
| traces recorded | 9 → **9 (unchanged)** |
| **model calls** | **0** |

Both counters advancing is the evidence that both skills ran; the unchanged trace
count is the evidence that nothing was planned, because a clean replay returns
early and records no trace. 4.4 s is end-to-end and includes two app launches.

**What this is not a measurement of.** The same goal's *cold* cost was not run,
so no speedup ratio is claimed here. For scale: each half measured 66 s and 69 s
cold on its own (E23, E25b), and a compound goal has no success oracle - E21b's
finding - so the PLAN path would more likely exhaust its step budget than
complete. That is the E24 claim, and confirming it needs the cold arm actually
run (E24c).

### Still to measure

- **E24c** - the compound goal on the PLAN path, to establish whether it
  completes at all on this hardware. The interesting outcome is a failure.
- The long-horizon tier with and without composition - the number that turns this
  from a feature into evidence for C1′.
- Whether the decomposer's over-splitting produces false composition in a store
  with many skills. The unit tests cover the obvious cases, not a population.

---

## E25 — The verifier checked before the app had started

*2026-08-17 - TECNO Camon 20 - found in a user's own run, not a designed
experiment - trace `...15-0`*

### What happened

Asked to **"go to LinkedIn"**, AXON escalated after **203 seconds** across three
steps. The trace:

| step | action | pre | post | observed |
|---|---|---|---|---|
| 0 | `launch_app` | yes | **no** | "a screen with no readable elements (unknown)" |
| 1 | `tap` LinkedIn | no | no | no such element on this screen |
| 2 | `tap` | yes | no | launcher showing CamScanner, Chrome... |

**Step 0 had already succeeded.** E21's grammar collapse fired, the resolver
returned `com.linkedin.android`, the gate approved, the launch was dispatched -
and the verifier looked 500 ms later, saw LinkedIn's splash screen with an empty
accessibility tree, and called it a failure. The remaining 200 seconds were spent
hunting for a "LinkedIn" element on the launcher.

### Why every previous experiment passed

Verification was a single observation after a fixed `settleMs = 500`, chosen
because an Android transition runs 200-400 ms. That is correct for a transition
and wrong for a **cold app start on a Helio G85**: a process fork, a splash
screen and a first layout pass.

WhatsApp starts fast enough to beat 500 ms. Every experiment to date - E17, E21b,
E22b, E23 - used WhatsApp. The constant was never wrong for anything that had
been tried, which is the general shape of the C5 substrate finding: **on low-end
hardware the timings a developer assumes are the ones that break**, and they
break on the heavier app nobody benchmarked.

### The fix, and the fix to the fix

Verification now polls until the post-condition holds or a per-action deadline
expires - 8 s for `launch_app`, 3 s for an in-app transition, and a `wait`
action's own stated timeout. Polling rather than a longer fixed settle, because a
5-second settle would fix LinkedIn and make every tap five seconds slower on a
device where six-step tasks are already minutes.

The first implementation retried on **any** unmet condition, and that is worth
recording because it was worse than the bug:

> Every genuine mismatch - a tap on the wrong button, an assertion that can never
> hold - waited out the full deadline while re-observing the device. The test
> suite went from **9 seconds to 7 minutes**, and seven tests failed outright
> because repeated `observe()` calls consumed the scripted screens their
> assertions depended on.

The retry predicate is now narrow, and only fires on positive evidence that the
UI has not finished rendering:

- **an empty tree** - no real screen has zero readable elements; this is an app
  mid-launch, and exactly what LinkedIn showed;
- **a launch whose target is not yet foreground** - the splash may have nodes
  while the package still is not in front.

A screen that is populated and simply fails the condition is a genuine mismatch.
Waiting cannot change it, and the verdict is more useful delivered promptly.

### Why this matters beyond one app

The precondition gate and the verifier both read the *live* UI tree, and this is
the first measured case of that tree being **transiently empty rather than
wrong**. "Not ready yet" and "not there" had been the same observation, and the
system treated both as failure. They are now distinguishable, which is a
correctness property the three structural defences depend on: a gate that cannot
tell an unloaded screen from an absent element will refuse correct actions on
slow hardware, which is precisely the hardware this project targets.

### E25b - confirmed on the device that found it

Same task, same phone, after the fix:

| | before | after |
|---|---|---|
| outcome | ESCALATED | **SUCCESS** |
| steps | 3 | **1** |
| model calls | 3 | **1** |
| wall clock | 203 s | **69 s** |

`launch_app` with `pre_ok=1, post_ok=1`. The task that could not complete now
completes in one step, and the two wasted steps are gone with it.

### Threats to validity

- **Found, not designed.** n = 1, from ordinary use. LinkedIn's cold start is not
  characterised; the 8 s deadline is chosen with margin, not measured.
- **One slow app.** Whether 8 s covers the slowest app on this device is unknown,
  and the honest statement is that it covered the one that exposed the bug.

---

## E26 — Selector robustness: freezing the sturdier handle

*2026-08-17 - implemented, 7 unit tests - **not yet measured on device***

### The no-op that was not neglect

`DefaultSkillCompiler.preferStableSelector` returned its argument unchanged, with
a comment describing an ordering it did not apply:

| selector | survives |
|---|---|
| `id` | most updates; the developer's own stable handle |
| `content_desc` | most updates; changes with localisation |
| `text` | visible-copy changes break it |
| `coord` | almost nothing - rotation, font size, density |

The reason it did nothing is more interesting than the omission: **the compiler
had nothing better to choose from.** A `TraceStep` carried only the action, so
the only selector in evidence was the one the model wrote - and the model writes
what it can see in the prompt, which is the visible text.

Meanwhile the precondition gate had *already resolved* that text to a concrete
node, and returned it as `GateResult.Allowed(node)`. That node frequently carries
`com.whatsapp:id/send`: a handle the app's own developer controls, immune to
translation and copy changes. The executor received it, used it for the §16
confirmation check, and then dropped it.

### What changed

The resolved node now flows `StepOutcome` -> `TraceStep.resolvedHandles` ->
SQLite -> compiler. Three fields only - view id, content-description, text -
because episodic memory grows without bound on a phone and the compiler never
reads the rest of a node.

Persisted rather than kept in memory, because the compiler reads traces back
after a restart; dropping them would make promotion work only within the session
that recorded the trace, which is the exact qualifier §11 exists to remove.

### Two refusals matter more than the promotions

**Never invent a handle.** Only handles observed on the matched node are used, so
a rewrite can only ever name the same element by a better-attested route. With
nothing better observed, the model's selector stands - not as a fallback but
because it is the only handle known to address that element.

**Never promote a parameterised selector.** This one would be a real bug. "Ammi"
is a *parameter*, substituted from the caller's params at replay. Promoting it to
the view id observed while messaging Ammi would freeze her contact row into every
future replay, and the skill would silently only ever message her - precisely the
macro-versus-skill error §7.7 warns about. A slot-bound selector is left exactly
as written.

### The install that would have failed

Adding those columns to `Trace.sq` left the build green and every test passing,
because `Schema.create()` runs only against a **fresh** database and every test
opens a temp file. The phone — holding a database with real learned skills in it
— would have kept the old table, and the first task after upgrading would have
failed on an INSERT naming columns that did not exist.

Third instance of one pattern, after D11's SQLite-dialect trap and E21c's package
visibility: **the developer's environment is not the deployment environment.**
Here the difference is a table that already exists.

`AxonStorage.migrate()` applies additive columns idempotently, by asking SQLite
what the table currently has rather than tracking a version number, so a
half-applied upgrade converges rather than wedging. It is honest about its limit:
**added nullable columns only.** A rename, drop or retype needs real versioned
migrations, and that is the point to adopt SQLDelight `.sqm` files rather than
extending this.

Verified on the device that held real data:

| | before | after |
|---|---|---|
| skills | 2 | **2** |
| traces | 9 | **9** |
| `trace_step` columns | 12 | **15** |
| hydrate at launch | — | **35 ms** |

### Status and what it is worth

Implemented and unit-tested; **not yet demonstrated to reduce breakage**, because
skill drift has never been measured here. §17 frames drift as something to
recover from and the honest position remains the one in `PHASE5.md`: *we have the
mechanism, not the numbers.*

The measurement that would settle it (E26b) is a robustness-tier run using the
`LAYOUT_VARIANT` perturbation the benchmark corpus already contains - the same
skill replayed against a moved or restyled target, with and without promotion.
Until then this is a plausible improvement, not a demonstrated one.

---

## E27 — Retiring skills that have rotted

*2026-08-17 - implemented, 9 unit tests - **not yet measured on device***

### A signal computed and read by nothing

`CompiledSkill.cleanReplayRate` existed from the day the skill store did. Every
replay updated it. **Nothing consumed it.**

So a skill whose target UI had drifted was replayed forever: each attempt failed
its assertions, fell back to a cold plan, and cost the replay attempt *on top of*
the planning it was supposed to avoid. §17 frames drift as something to recover
from, and the mechanism to notice it was sitting in the row, unread.

A skill judged unhealthy is now skipped by the matcher, so the goal takes the
PLAN path - which records fresh traces and re-compiles the skill against the UI
as it now is. The drift-recovery loop closes.

### Thresholds, and why they lean the opposite way to the matcher's

| | value | reasoning |
|---|---|---|
| replays before judging | **3** | one repaired replay out of one is a 0% clean rate and means nothing; a single transient would discard the ~60 s x 2 that compiling cost |
| clean-rate floor | **0.5** | repaired more often than not; a repaired replay still *succeeded* and merely cost one planner call, so retiring early throws away a skill still saving most of its steps |

`GoalMatcher`'s thresholds fail towards doing nothing, because a false match acts
wrongly on a live device. These fail towards *keeping* a skill, because being
wrong in either direction costs only time. Same reasoning, opposite conclusion,
and worth stating because a reviewer will otherwise read the leniency as
carelessness.

### The interaction bug this exposed

D11 had `save()` preserve the replay counters across every write, reasoning that
a skill with 41 replays had earned its clean-replay rate. That was right while
the rate was **reported**. It stopped being right the moment the rate became
**consumed**: a skill retired for a poor rate would inherit that rate onto its
freshly repaired body and stay retired *permanently* - precisely when the system
had just fixed itself.

The rule is now split by what actually changed:

- **same steps** - a refinement, which improves slots without altering the
  script. Keep the history; it still describes the thing being stored.
- **different steps** - a different script. Its predecessor's record says nothing
  about it, so the history starts over.

A test that asserted the old behaviour now asserts the new one, with the reversal
explained rather than silently corrected.

### A divergence found on the way

The two stores disagreed. SQLite preserved counters on every save; the in-memory
store silently reset them by overwriting. That is the class of difference
`GoalMatcher` was extracted to prevent - **the tests use the in-memory store and
the phone uses SQLite**, so a behavioural gap there means the tests are not
testing what ships. Both now apply the same rule.

### Not yet measured

Skill drift has never been quantified here, so the thresholds are reasoned rather
than calibrated. E27b is a robustness-tier run measuring how often skills
actually break and whether retirement fires when it should - the same run that
would settle E26.

---

## E29 — A safety claim the code did not back

*2026-08-17 - found by auditing the README against the source - 7 unit tests*

### The claim

The README has said, since Phase 0:

> **Per-skill revocable capabilities.** Nothing runs a capability it did not
> declare and receive.

It was enforced **at skill-install time and nowhere else**. `SkillStore.install()`
compared a manifest's declared capabilities against the granted set and returned
`NeedsCapabilities`; after that, nothing checked again.

Three holes followed:

1. **The PLAN path has no skill.** A freshly planned action has no manifest to
   have declared anything, so every action AXON took *before* learning a task —
   which is every action on a task's first run — was ungated.
2. **`PreconditionFailure.CapabilityDenied` was never constructed.** The failure
   existed in the type system and was unreachable from any code path. Grepping
   for its construction returned nothing.
3. **The shipped configuration granted nothing.** `SqlSkillStore` takes
   `granted: Set<Capability> = emptySet()` and the app passed no argument, so on
   the only build that runs on a phone the grant set was empty and no behaviour
   differed.

A safety property checked at install and not at use is not enforced. It is a
comment.

### What it took to make true

`CapabilityPolicy` maps every action to the capability it requires, and the
executor checks before dispatch — on both paths, since it sits in the executor
rather than in the skill store. The mapping is an exhaustive `when`, so a new
`DeviceAction` variant will not compile until it declares what it needs; a `when`
with an `else` would silently give every future action the weakest requirement
and ship a new capability ungated.

Two questions are kept apart, because their remedies are unrelated:

| question | source | failure |
|---|---|---|
| *can the device do this?* | `DeviceDriver.capabilities()` | `Unsupported` — no permission screen will help |
| *may AXON do this?* | the grant set | `NotGranted` — the user can grant it |

Collapsing them would mean a capability the OS happens to expose is one AXON may
use, which is exactly the reasoning that makes an accessibility-API agent
indistinguishable from the stalkerware built on the same substrate (§6.3).

The check runs **before** the precondition gate. "You may not do this at all"
outranks "the thing you named is not on screen", and checking the gate first
would report the wrong reason — sending a user to hunt for a missing button when
the real answer is that AXON was never allowed to tap.

### What the fix immediately caught

Five test drivers declared `setOf(Capability.UI_GESTURE)` while simulating app
launches. Every one of them had been exercising `launch_app` against a driver
that, by its own declaration, could not launch apps — and passing, because
nothing checked. They now declare what they simulate.

The real `AccessibilityDriver` declares all three, so no shipped behaviour
changed. That is worth stating plainly: **this experiment fixed a hole, not a
symptom.** Nothing had gone wrong yet.

### Why it is recorded as an experiment rather than a bug fix

Because the finding is not "a check was missing". It is that a **README claim
survived nine phases, a decision log and a test suite without anyone noticing it
was unbacked**, and it was found by reading the prose against the source rather
than by any test failing. The same audit was then run against every other structural §16 claim.

### The rest of the audit

| README claim | status | evidence |
|---|---|---|
| no `INTERNET` permission | **holds** | `dumpsys package` on the device lists only FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE and POST_NOTIFICATIONS — checked as installed, not merely as written |
| credential fields refused at capture | **holds** | `TreeCapture` returns before reading `node.text` *and* without descending, so a password field's children — an inline error echoing the value — are refused with it |
| visible operation only | **holds** | `canBeSeen()` refuses to run when notifications are blocked (O2, itself a shipped violation found the same way) |
| per-skill revocable capabilities | **was unbacked** | this entry |
| irreversible actions need confirmation | **was unbacked** | the gate was `DENY` with no UI, so AXON refused rather than asked (E28) |
| audit log viewable by the user | **was unbacked** | the data existed and nothing displayed it (E28) |

Three of six held; three did not, and all three failures were of the same kind —
**a mechanism present in the code with no path from it to the user.** None would
have been caught by a test, because each component behaved correctly in
isolation. They were found by reading the prose against the source.

That ratio is the finding worth reporting. It says something uncomfortable about
safety sections generally: the claims are written once, early, and nothing
afterwards re-checks them.

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
| E22b | Persistence cost on device: hydrate at launch, trace-write at task end | needs device |
| E22e | Hydrate cost with a realistic skill count (hundreds, not one) | needs a populated store |
| E21d | Does the launch oracle generalise past app-launch goals? | Phase 7 |
| E23b | False-match rate for canonicalisation across many launch skills | needs a populated store |
| E24c | Compound goal on the PLAN path: does it complete at all? | needs device |
| E26b | Does selector promotion reduce replay breakage under LAYOUT_VARIANT? | Phase 7 |
| E27b | Skill-drift rate, and whether retirement thresholds fire correctly | Phase 7 |
