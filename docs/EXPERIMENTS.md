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
| E14 | Variance across repeated runs for E2, E3 | rerun with n ≥ 5 |
