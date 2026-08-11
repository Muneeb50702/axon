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

Two things make this publishable rather than an engineering report:

1. **Trace-to-skill compilation (C1)** is genuinely novel — no existing on-device
   agent compiles a verified trace into a deterministic replayable skill.
2. **It is measured, on hardware the field usually ignores.** Most on-device
   agent work runs on flagships or emulators. Every number here comes from a
   ~$150 Helio G85 handset, which is the population §2.1 is actually about.

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

Then the portability seam (C4) and the skill compiler (C1).

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

*Evidence: **E4** (A vs B valid-action rate), **E5**.*

### 5. Deterministic verification and self-healing (C2)

Post-conditions as a mandatory field on every action; tree-diff verification with
no model in the loop; rollback and replan with structured failure context.

*Evidence: **E9** — not yet measured. Phase 4.*

### 6. Trace-to-skill compilation (C1)

The compiler algorithm; slot inference by diffing repeated runs; the
`llm_fallback` boundary; replay with per-step assertions and re-compilation on
divergence.

**The headline figure.** Cold planning costs ~60 s/step on the target device;
replay costs zero model calls. That gap is widest on exactly the low-end hardware
the work targets — a flagship would make C1 look like an optimisation rather than
a necessity.

*Evidence: **E10** — not yet measured. Phase 5.*

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

- **OEM power managers terminate sustained foreground compute.** SIGKILL after
  ~7 minutes, `adj:0`, ~3.9 GB free. Long-horizon on-device autonomy must be
  checkpointed to survive the platform, not merely the workload. **E6**
- **Throughput degrades within a run as the device heats.** Cases completed per
  window fell 4 → 1 at 44 °C. **E6**
- **Unoptimised native builds are silently plausible.** An `-O0` ggml build
  produced results indistinguishable in kind from "this phone is slow". **E1**
- **Effective vs file parameter count.** "E2B" denotes ~2 B *effective*
  parameters; the file is 3.11 GB. Conflating the two mis-sizes the memory
  budget — an easy error, and one the surrounding literature invites.

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
| Second device | One handset is a validity problem. A second SoC, even borrowed, materially strengthens the paper. |
| Variance | Single-shot measurements must be repeated with n ≥ 5. |
| Artifact | Repo, corpus and raw logs should be release-ready — the reproducibility split in §7 is a selling point only if the artifact exists. |

## Venue note

The evaluation-half-runs-in-CI property is worth foregrounding in the artifact
statement. A reviewer can reproduce the deterministic results without owning the
hardware, which is rare in this area and directly addresses the usual complaint
that on-device work is unverifiable.
