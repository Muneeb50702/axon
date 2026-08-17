# AXON — Paper Outline

Target: a systems/HCI venue with an on-device or mobile-AI track. The FYP report
and the paper share evidence but differ in emphasis — the report documents a
build, the paper defends a claim.

This file maps each claim to the evidence that must exist for it, and names the
experiment (`docs/EXPERIMENTS.md`) that supplies it. **A claim with no experiment
id is not yet publishable**, and saying so here is cheaper than discovering it
during writing.

---

## The claim

> Reliability in on-device agents is an architecture problem, not a model-size
> problem. A ~1B model, constrained by a formal action grammar, gated against
> live UI state, and verified by deterministic post-conditions, matches or beats
> a substantially larger unconstrained model on task success — and on repeated
> tasks approaches zero model invocations through trace-to-skill compilation.

**Revised after the literature check** (`docs/RELATED_WORK.md`). The original
framing rested on trace-to-skill compilation being novel; it is not — see
[SkillDroid](https://arxiv.org/abs/2604.14872) and the 2026 cluster around it.
What remains, and is defensible:

1. **Constrained decoding is what makes the small-model regime workable.**
   Existing skill-compiling GUI agents call cloud-scale models and therefore
   never face the malformed-output problem. A 1B model does, and a formal action
   grammar removes it by construction.
2. **Skill compilation is a viability mechanism here, not an optimisation.**
   Cloud replay saves a round-trip; offline replay is the difference between a
   six-step task taking six minutes and taking milliseconds.
3. **It is measured, on hardware the field ignores.** Every number comes from a
   ~$150 Helio G85 handset — the population §2.1 is about, and one whose OEM
   power management actively fights sustained agent workloads.
4. **Half the evaluation reproduces without the handset**, because the
   reliability core carries no Android dependency.

---

## Structure

### 1. Introduction

Cloud agents are structurally unavailable to users on intermittent or expensive
connectivity, and unacceptable for privacy-sensitive screens. On-device agents
exist but split into two paradigms that both lack a reliability layer.

*Evidence: none needed — positioning.*

### 2. Background and related work

Two existing paradigms, and what each lacks (spec §3.1):

| paradigm | examples | limitation |
|---|---|---|
| tool-runner | Mythara, Operit AI | tool set is static and hand-maintained |
| VLM screenshot loop | AutoGLM, Roubao | slow, non-deterministic, no memory of success |

Plus: constrained decoding (GBNF, XGrammar), Android accessibility as a control
substrate, small-model landscape.

**Honest positioning is essential here.** A reviewer will find these projects.
The contribution is the reliability-and-learning layer neither paradigm has, and
the fact that it is evaluated rather than demonstrated.

*Evidence: literature only. Needs a proper citation pass — currently the spec's
§3 table has names but no references.*

### 3. System design

The three-defence architecture, and specifically that each catches a class the
others cannot:

| defence | rejects | when | contribution |
|---|---|---|---|
| grammar | malformed output — *wrong shape* | during sampling | C3 |
| precondition gate | names something absent — *wrong world* | before acting | — |
| verifier | plausible but ineffective — *wrong outcome* | after acting | C2 |

Then the portability seam (C4) and the skill compiler (C1′).

*Evidence: architecture + the CI-enforced module graph. The claim "`:core` has no
Android dependency" is checkable by a reviewer from the build files.*

### 4. Grammar-constrained action emission (C3)

The discriminated-union grammar; why it is stronger than a flat schema; the
`package-name` constraint as the clean illustration; the three-layer
correspondence (Kotlin sealed types ⟷ wire format ⟷ GBNF) and the CI check that
keeps them aligned.

**Findings worth a paragraph each, because they generalise beyond this system:**

- Optional whitespace inherited from parsing grammars costs real tokens and
  causes truncation in generation contexts. **E5**
- A rejected grammar fails *silently* — llama.cpp does not install the sampler
  and generation proceeds unconstrained with no error. Any system relying on
  constrained decoding needs a positive check that the constraint is active.
  **E4 methodology**
- **Ablating a grammar requires a prompt that already specifies the format.**
  Omit it and the unconstrained arm collapses to a degenerate one-token answer,
  producing a 0%-vs-100% result that measures the prompt rather than the
  grammar. AXON's first attempt did exactly this; it is recorded as void.
  Generalises to any constrained-decoding ablation. **E4 (void) → E4b**

*Evidence: **E4b** (A vs B valid-action rate over a fair prompt), **E5**.
E4 is void and must not be cited.*

### 5. Deterministic verification and self-healing (C2)

Post-conditions as a mandatory field on every action; tree-diff verification with
no model in the loop; rollback and replan with structured failure context.

*Evidence: **E9** — not yet measured. Phase 4.*

### 6. Skill compilation as a viability mechanism (C1′)

**Position against SkillDroid explicitly and early in this section**, before a
reviewer does it. The mechanism is not new; the regime is. Their compilation
removes a network round-trip from a cloud-model agent. Here it removes ~60 s of
local inference per step from a model that is the only kind that fits.

The compiler algorithm; slot inference by diffing repeated runs; the
`llm_fallback` boundary; replay with per-step assertions and re-compilation on
divergence.

**The headline figure.** Cold planning costs ~60 s/step on the target device;
replay costs zero model calls. That gap is widest on exactly the low-end hardware
the work targets — on a flagship, compilation is a nicety; here it is the
difference between a six-step task taking six minutes and taking milliseconds.

**Lead with the compound-goal result, not the ratio.** A speedup invites the
reader to discount it — 2.3 s versus 66 s is impressive and still just *faster*.
The compound goal is categorical: composed from two learned skills it completes
in **2.3 s with zero model calls**; on the cold path, given **277 s and three
model calls, it never attempted the task at all** — it tapped an unrelated
element, tapped Back, and escalated (**E24c**). One arm of that comparison has no
latency to report, because it does not finish.

This is also the section's best defence against "so it is a cache". A cache makes
a slow thing fast. Here the uncached path does not produce the answer, because a
compound goal loses both the app-identity oracle and the completion test (§3.5's
contrapositive), so the small model is handed exactly the decision it is worst at.

*Evidence: **E17**, **E22d**, **E24b/c/d**. **E10** — the full cold-vs-replay
matrix across the corpus — remains unmeasured. A cloud-baseline comparison on the
same tasks would let this section quantify what offline operation costs, and is
the single most valuable addition available.*

### 7. Evaluation

AXON-Bench (C5): tiers, oracles, metrics. The ablation matrix A–E.

**Methodological point worth making explicitly**, because it is unusual and
defensible: the evaluation is split by what is device-bound.

| half | runs where | why |
|---|---|---|
| deterministic core — gate, verifier, compiler, replay | JVM, in CI, any machine | reproducible by a reviewer with no handset |
| latency, thermal, real app layouts | the phone | genuinely device-bound |

This is only possible because `:core` carries no Android dependency, so C4 buys
reproducibility as well as portability.

*Evidence: **E4**, **E7**, **E8**, **E9**, **E10**, **E12**.*

### 8. Findings about the deployment substrate

A short section that is not in the FYP spec but is, on the evidence so far, one
of the paper's more transferable contributions. Published on-device agent work
is generally evaluated on flagships or emulators and therefore does not
encounter:

- **OEM power managers terminate sustained foreground compute** — and not on a
  timer. The signal is `SIGKILL` from `system_server`, delivered in *batches*
  (seven processes in five seconds in one observed sweep), against a process that
  is by a wide margin the device's largest tenant: **1.08 GB PSS while planning,
  51 MB idle**, on a phone already 1.6 GB into swap before the agent starts.
  Survival is therefore a matter of whether a sweep lands during the run, not of
  how long the run is — two runs of the same task died at 75 s and finished at
  277 s. E6's "~7 minutes" is the interval sweeps happened to fall at in one
  session, not a ceiling. Long-horizon on-device autonomy must be checkpointed to
  survive the *platform*, not merely the workload. **E6, E6b**
- **Throughput degrades within a run as the device heats.** Cases completed per
  window fell 4 → 1 at 44 °C. **E6**
- **Unoptimised native builds are silently plausible.** An `-O0` ggml build
  produced results indistinguishable in kind from "this phone is slow". **E1**
- **Effective vs file parameter count.** "E2B" denotes ~2 B *effective*
  parameters; the file is 3.11 GB. Conflating the two mis-sizes the memory
  budget — an easy error, and one the surrounding literature invites.

#### 8.1 One lesson, three independent instances

The findings above are individually useful. The following three are the same
finding arriving by three unrelated routes, which is what makes it worth a
section rather than a footnote:

> **The developer's environment is not the deployment environment**, and on
> Android the gap is systematic rather than incidental: the toolchain, the
> emulator and the test fixture each present a *more capable and more forgiving*
> world than the device does.

| instance | what the developer sees | what the device does | id |
|---|---|---|---|
| SQL dialect | the newest SQLite, so upsert syntax compiles | API 26 ships SQLite 3.18; upsert arrived in 3.24, and the statement fails only in the field | **D11** |
| package visibility | tests inject an app list, so name resolution works | Android 11+ hides installed packages; the resolver saw **zero** apps and the strongest constraint in the system was inert | **E21c** |
| schema creation | every test opens a fresh database, so `create()` always runs | the phone has a database that already exists; `create()` is skipped and the first write names columns that are not there | **E26** |

Each passed every unit test. Each was invisible in CI. Two of the three were
found only because a *different* app or a *real* upgrade was tried, and the
third was caught by a build that happened to target the minimum API rather than
the developer's.

The transferable claim is narrower and sharper than "test on real devices":
**unit tests establish that code is correct, not that it is reachable.** For an
agent whose defences are structural — a grammar, a gate, a verifier — a defence
that is switched off by the platform is indistinguishable, in every log the
system produces, from a defence that is working and simply had nothing to do.

*Evidence: D11, E21c, E25, E26.*

- **Timing constants are sized for the developer's app.** Post-condition
  verification used a fixed 500 ms settle, correct for an Android transition and
  far too short for a cold app start on a Helio G85. Every experiment to that
  point had used WhatsApp, which starts fast enough to hide it; the first heavy
  app tried (LinkedIn) turned a successful launch into a 203-second escalation.
  **E25**

### 9. Threats to validity

Written honestly and early, since the weaknesses are known:

- **Synthetic screen corpus.** 24 hand-written approximations. Measures
  structural validity and screen-grounding, not task completion on live apps.
- **Single device.** Every number is from one Helio G85 handset. Generalisation
  to other SoCs and OEM skins is untested, and E6 in particular is likely
  OEM-specific.
- **Small n.** E2 and E3 are single-shot. **E14** must close this.
- **Structural validity ≠ semantic correctness.** A 100% valid-action rate is not
  a task success rate, and the paper must not permit that reading.
- **Skills are per-device-family.** Compiled traces are not claimed to transfer
  across devices or app versions; assertion-driven repair is the mitigation, and
  its cost is itself a measurement.

### 10. Ethics and responsible disclosure

AccessibilityService is the substrate of a documented stalkerware industry. AXON's
guardrails are architectural rather than promissory and a reviewer can verify the
strongest one from the manifest: **the app requests no `INTERNET` permission**,
so screen contents cannot leave the device by construction.

Also: credential/OTP refusal at capture time with a published test corpus;
foreground-only operation; per-skill revocable capabilities; explicit misuse
analysis.

---

## What is missing before submission

| gap | action |
|---|---|
| Citations | §2 has project names, no references. Needs a real literature pass. |
| E7–E14 | Most evaluation is unmeasured; Phases 3–7. |
| E24c | Composition is measured working (E24b) but never compared against the cold path it claims to replace. The interesting outcome is a failure — a compound goal the PLAN path cannot finish — and until it is run, C1′-as-viability rests on arithmetic. |
| E26b / E27b | Selector promotion and skill retirement are implemented and unit-tested but **not shown to reduce breakage**. Skill drift has never been quantified here; both need the robustness tier's `LAYOUT_VARIANT` runs. |
| Valid-action rate in the harness | Cannot be derived from traces — it needs the planner's generation counts. Measured correctly in E4b's instrumentation; the JVM harness reports "not measured" rather than a plausible wrong number. |
| Second device | One handset is a validity problem. A second SoC, even borrowed, materially strengthens the paper. |
| Variance | Single-shot measurements must be repeated with n ≥ 5. |
| Artifact | Repo, corpus and raw logs should be release-ready — the reproducibility split in §7 is a selling point only if the artifact exists. |

## Venue note

The evaluation-half-runs-in-CI property is worth foregrounding in the artifact
statement. A reviewer can reproduce the deterministic results without owning the
hardware, which is rare in this area and directly addresses the usual complaint
that on-device work is unverifiable.
