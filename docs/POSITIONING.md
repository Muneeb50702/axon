# Positioning — how AXON avoids being a clone

*Written 2026-08-11, after the literature check in `RELATED_WORK.md`.*

The uncomfortable question, asked early: **what does AXON have that a reviewer
cannot already find on arXiv?**

Answering it honestly means first admitting what is *not* differentiating.

---

## 1. What is already taken

| if the claim is… | it is taken by | verdict |
|---|---|---|
| "we built an LLM agent that operates Android" | AutoDroid, Mythara, Operit, AutoGLM, Roubao | crowded since 2023 |
| "we compile successful traces into replayable skills" | **SkillDroid** (Android, Apr 2026), TraceCompiler, Skill-DisCo, Trace2Skill | taken, on the same platform |
| "we use constrained decoding to get valid JSON" | GBNF, XGrammar, Outlines, and a large applied literature | a technique, not a contribution |
| "we run a small model on-device" | MobileExplorer, and every Gemma/Phi deployment paper | table stakes |

Any FYP whose contribution is one row of that table is a clone with better
documentation. **AXON as originally specified was one row of that table.**

## 2. The reframe: the system is the instrument, not the contribution

Every project listed above presents **a system** and demonstrates that it works.
That is a well-populated genre and the marginal paper in it is hard to place.

There is an adjacent question nobody in this space has answered, and AXON is
already built to answer it:

> **How much architectural scaffolding does it take to substitute for model
> capacity, and where does that substitution stop working?**

Every one of those systems assumes a capable model and adds structure around it.
None of them characterise the *trade*. Yet that trade is the entire practical
question for on-device deployment: a phone has a fixed memory and energy budget,
so the real design decision is how much of your budget goes to parameters versus
how much goes to scaffolding.

That reframing changes what AXON *is*:

| | old framing | new framing |
|---|---|---|
| the contribution | a reliable on-device agent | a measurement of when architecture substitutes for scale |
| the system | the result | the instrument that produces the measurement |
| the ablation (§14.3) | proof our system works | **the result itself** |
| a competitor's system | a rival | a point on the curve |

The experiments barely change. §14.3 already specifies the ablation matrix. What
changes is that the matrix stops being a validation table and becomes the paper's
central figure.

This also makes SkillDroid an *asset*: it is a strong, published, cloud-model
point on the same axis. A curve is more interesting when it has someone else's
system on it.

## 3. Five things that would be genuinely AXON's

Each is achievable within the existing phase plan, and none is claimed by the
work surveyed.

### 3.1 The substitution surface — model size × scaffolding

Not "1B with grammar beats E2B without". A **surface**: model capacity on one
axis, architectural defences on the other, task success as the value.

|  | no grammar | +grammar | +grammar+verifier | +grammar+verifier+replay |
|---|---|---|---|---|
| ~0.5B | | | | |
| ~1B | | | | |
| ~2B | | | | |
| ~4B | | | | |

The interesting results are not the cells but the shape:

- **Where does the scaffolding stop paying?** If a 4B with no scaffolding matches
  a 1B with full scaffolding, the exchange rate is roughly 4×, and that is a
  quotable number nobody currently has.
- **Where does it stop working at all?** There is presumably a floor below which
  no amount of structure rescues the model. Finding it is a genuine result — and
  a *negative* result, which is rare and credible.

Cost: the corpus and harness already exist. It is model downloads and machine
time, not new engineering.

### 3.2 Energy per task — the metric this field omits

On-device agent papers report latency and success rate. **On a battery-powered
device the binding constraint is joules, and essentially nobody reports it.**

AXON is unusually well placed to: it already instruments thermal state per
generation, and Android exposes `batterystats` at package granularity.

The claims this unlocks are ones no cited paper can make:

- joules per completed task, by configuration
- **the C1′ headline in energy terms**: a compiled skill replay costs ~zero
  inference energy, so the saving is not merely time but battery
- the energy cost of constrained decoding (currently 59% of decode time — what
  fraction of decode *energy*?)

This is a small amount of work for a metric that differentiates immediately.

### 3.3 How badly do small models judge their own work?

AXON's C2 asserts that small models are unreliable self-judges, which is why
verification is deterministic. §7.6 states it as motivation. **It is not measured
anywhere in the literature for GUI agents**, and it is trivially measurable with
what already exists:

For each step in a trace, ask the same model *"did that action achieve X?"* and
compare against the deterministic tree assertion, which is ground truth.

Two numbers fall out: the self-judge false-positive rate (believing a failed step
succeeded — the unrecoverable error, since the agent then plans from a state it
does not occupy) and the false-negative rate.

If the false-positive rate is high, C2 stops being a design preference and
becomes a *finding*: LLM self-assessment is unsafe for multi-step device control
at this scale, with a number attached.

### 3.4 Skill decay under UI drift

SkillDroid demonstrates replay works. Nobody has characterised **how skills die**.

Apps update; layouts change; A/B variants ship. A compiled skill is a bet on UI
stability, and the interesting questions are empirical:

- Which selector types survive? (`content_desc` vs `text` vs `id` vs `coord` —
  AXON already ranks these by assumption; measuring it would replace assumption
  with data)
- What fraction of a skill needs repair after a real app update?
- Is repairing cheaper than recompiling, and at what divergence threshold?

§14.1 already specifies `LAYOUT_VARIANT` as a robustness perturbation, and the
corpus now ships it, so the benchmark hooks exist. This turns §17's
"fragmentation is a risk" into a study.

Two mechanisms are now implemented and **deliberately unclaimed** until that
study runs: the compiler promotes a selector to the sturdiest handle the gate
actually matched (E26), and the store retires a skill repaired more often than
not (E27). Both are reasoned, neither is calibrated, and the honest position is
that they are what the study would *evaluate* rather than results it has
produced.

### 3.5 Determinism as a design method, applied repeatedly

The strongest thing to come out of building this is not any single mechanism. It
is that **one rule was applied five times, to five different decisions**, and
each time it removed a failure the model was demonstrably making:

| decision | determined by | the failure it removed |
|---|---|---|
| what shape an action may take | GBNF grammar (C3) | malformed JSON at 1B |
| which elements may be named | the live UI tree | naming something not on screen |
| which app "open X" means | package lookup (E21) | **opening the dialer instead of WhatsApp** (E18b) |
| when a launch goal is finished | foreground package (E21b) | launching WhatsApp six times and exhausting the budget |
| where one task ends and the next begins | sequencing words (E24) | cold-planning a compound goal whose halves were already known |

The rule: **where the correct answer is determinable without the model, do not
ask the model.** Each application is individually small. Together they are a
method, and the method — not the grammar, not the compiler — is what a reader
can carry to a different agent on different hardware.

It also predicts where the approach stops. Every row above is a decision with an
exact answer available outside the model. Semantic selection among several
plausible on-screen elements has no such structure, which is why E18b's failure
class remains open and why matching genuine paraphrase still needs an embedding
model (E23). **A method that says where it does not apply is worth more than one
that claims to apply everywhere.**

### 3.6 The deployment substrate is systematically more forgiving in development

Three findings, arrived at independently, that are the same finding:

| | the developer sees | the device does |
|---|---|---|
| SQL dialect (D11) | the newest SQLite | API 26 ships 3.18; the statement fails only in the field |
| package visibility (E21c) | tests inject the app list | Android hides it; the resolver saw **zero** apps and E21 was **inert** |
| schema creation (E26) | every test opens a fresh database | the phone's already exists; `create()` is skipped |

Each passed every unit test. Each was invisible in CI. The claim is narrower and
sharper than "test on real devices": **unit tests establish that code is correct,
not that it is reachable** — and for an agent whose defences are structural, a
defence switched off by the platform is indistinguishable, in every log the
system produces, from a defence that is working and had nothing to do.

Published on-device agent work is evaluated on flagships and emulators, which is
exactly the environment in which all three of these are invisible.

## 4. What to stop claiming

| drop | keep |
|---|---|
| "no existing agent compiles traces into skills" | "compilation is a *viability* mechanism offline, not a latency optimisation" |
| "we built an on-device agent" | "we characterise the scaffolding-vs-scale trade on hardware that actually constrains you" |
| "constrained decoding gives valid JSON" | "constrained decoding is what makes the sub-2B regime usable at all — measured" |
| "our system achieves X%" | "here is the surface; here is where it breaks" |
| "we handle natural-language variation" | "we collapse launch-verb paraphrase exactly, and decline to guess at the rest" (E23) |
| "skills are robust to UI drift" | "we rank selectors by expected stability and retire skills that stop working — neither yet measured" (E26, E27) |

## 5. Why the cheap phone is the strategic asset

It is tempting to see the Helio G85 as a limitation. It is the opposite, for a
reason that is structural rather than sentimental:

**A flagship cannot produce these results.** On a fast device, scaffolding looks
like an optimisation, energy looks free, thermal never binds, and the OEM never
kills your process. The interesting phenomena only appear when the hardware
actually constrains you — which is exactly why the existing literature, evaluated
on flagships and emulators, has not reported them.

Already observed on this device and absent from the surveyed work:

- OEM power managers SIGKILL sustained foreground compute (~7 min) — long-horizon
  on-device autonomy must survive the *platform*, not just the workload
- throughput decays within a run as the device heats
- effective vs file parameter counts diverge sharply for PLE-architecture models,
  and conflating them mis-sizes the memory budget

None of these are anyone's headline. Together they are a section, and it is the
section a practitioner would actually cite.

## 6. Practical next steps

Ordered by ratio of distinctiveness to effort.

| # | action | effort | phase |
|---|---|---|---|
| 1 | Add energy-per-task instrumentation (`batterystats`) | low | now |
| 2 | Self-judge vs deterministic-verifier comparison (§3.3) | low | Phase 4 |
| 3 | Extend the ablation to ≥3 model sizes (§3.1) | medium — downloads + machine time | Phase 7 |
| 4 | Selector-survival study under UI drift (§3.4) | medium | Phase 5–7 |
| 5 | Cloud baseline on the same tasks, for the energy/latency contrast | medium | Phase 7 |
| 6 | Second device — the single biggest validity fix | depends on access | any |

## 7. The honest summary

AXON is **not** novel as a system. It is a well-built instance of a known genre,
and pretending otherwise will not survive review.

AXON *can* be novel as a **measurement**: the substitution frontier between model
capacity and architectural scaffolding, on hardware where the trade actually
binds, with energy and thermal as first-class axes, and with a rare negative
result about where scaffolding stops rescuing a model.

That contribution is available with the experiments already specified. What it
requires is a change of framing — and the discipline to report the shape of the
curve rather than only the cell where the system looks best.
