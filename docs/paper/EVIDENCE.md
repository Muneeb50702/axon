# Evidence inventory

*Every claim the project makes, what backs it, and what it does not establish.*

Written 2026-08-19. The purpose is to make writing the paper mechanical and to
make an unbacked claim visible before a reviewer finds it. Three claims were
already found unbacked this way (E29); the discipline earns its keep.

**Status key** — `MEASURED` on the target device · `MEASURED (JVM)` off-device,
shape only · `PARTIAL` one side measured · `UNBACKED` asserted, no evidence.

---

## The headline contributions

### C1′ — Skill compilation makes repeated tasks free

| claim | evidence | number | status |
|---|---|---|---|
| A repeated task costs zero model calls | E17, E22d, E8 | `open_whatsapp`: 75.7 s cold → **5.3 s replayed, 0 model calls** | MEASURED |
| Learning survives process death | E22, E22b | rehydrates in **39 ms**; survives `force-stop` | MEASURED |
| Reuse reaches paraphrases, not just the exact string | E23 | 9 phrasings, 2 languages, 1 skill | MEASURED |
| Compound goals compose from learned parts | E24b, E24d | **2.3 s, 0 model calls** for two skills | MEASURED |
| **Composition makes an impossible task possible** | **E24c, E24e** | cold arm: **277 s / 3 calls and never attempted the task**; killed at 75 s in a second run | MEASURED |
| Persistence stays cheap as the store grows | E22e | hydrate and match both linear; match **40 µs at 500 skills** | MEASURED (JVM) |

**The strongest form of C1′ is E24c, and it is categorical rather than a
speedup.** One arm of that comparison has no latency to report because it does
not finish. That is also the best answer to "so it is a cache": a cache makes a
slow thing fast, and here the uncached path does not produce the answer.

### C2 — Deterministic verification replaces model self-assessment

| claim | evidence | number | status |
|---|---|---|---|
| The verifier catches actions the other defences cannot | E24c | both failing steps passed the grammar **and** the gate; only the verifier objected | MEASURED |
| The loop recovers when the planner offers a correction | E9 | ceiling **100%** | MEASURED (JVM) |
| A non-recovering planner terminates rather than looping | E9 | floor **0%**, escalates after 3 heals having dispatched **1 step** | MEASURED (JVM) |
| **The device recovery rate** | — | — | **UNBACKED (E9b)** |

**Do not quote a recovery rate for AXON as a system.** E9 bounds the *control
loop*; the device number depends on how often a 1B model proposes a workable
alternative, which E18 suggests is seldom, and it has not been measured.

### C3 — Grammar-constrained emission removes a failure class

| claim | evidence | number | status |
|---|---|---|---|
| Constrained decoding eliminates malformed actions | E4, E4b | **100% vs 46.7%** valid actions, n=15, two independent runs | MEASURED |
| A hallucinated *target* is unreachable at the sampler | E18, E31, **E33** | **was FALSE on device until 2026-08-19**; 3 violations/run → 0 after the fix | MEASURED (after fix) |
| Structural validity is **not** task success | E4b | a valid action named a non-interactive label | MEASURED |
| The engine's `constrained` flag is evidence the grammar bound | — | it is `grammar != null` — reports only that one was *passed* | **FALSE, do not cite** |

**The target-grounding claim needs its history stated, not hidden.** E18 and E31
established the mechanism; E33 found it **did not bind on real screens** because
the grammar emitted trimmed labels while the gate matched raw attributes. Every
other signal — `isSpecialised`, the parse check, the engine's own flag — reported
that the constraint was active. Any corpus result collected before
2026-08-19 measured a system whose C3 was not working, which is why
`E8-core-COLD-pre-E33.csv` is kept as the before-half of that comparison rather
than deleted.

The generalisable lesson, and the one worth a paragraph in the paper: **a
constraint mechanism needs a check that its output actually obeyed it.** Verifying
that the grammar was built, that it parses, and that it was passed to the sampler
established all three and still missed a constraint that did not bind. Only
comparing the *emitted action* against the *evidence the grammar was built from*
caught it.

The 100% is *by construction* — a lower value would mean the grammar was not
installed, not that the model did badly. The paper must not let it read as a
success rate.

### C5 — The deployment substrate is the binding constraint

| claim | evidence | number | status |
|---|---|---|---|
| OEM power management kills sustained compute | E6, **E6b** | `SIGKILL` from `system_server` **in batches** — 7 processes in 5 s | MEASURED |
| The agent is the device's largest tenant | E6b | **1.08 GB PSS planning vs 51 MB idle**; phone already 1.6 GB into swap | MEASURED |
| Survival is sweep overlap, not duration | E6b, E24c | same task died at **75 s** and finished at **277 s** | MEASURED |
| The dev environment is systematically more forgiving | D11, E21c, E26, E31 | four independent instances | MEASURED |
| **Task success rate over the corpus** | **E8** | **in progress** | **PARTIAL** |
| **Arm E (larger model) baseline** | D10 | model present; loadability untested | **UNBACKED** |

---

## The method claim

**Where the correct answer is determinable without the model, do not ask the
model** — applied six times (§3.5 of `POSITIONING.md`).

| decision | determined by | evidence |
|---|---|---|
| action shape | GBNF grammar | E4b |
| nameable elements | the live UI tree | E18 |
| which app "open X" means | package lookup | E21, E21c |
| when a launch goal is done | foreground package | E21b |
| where one task ends | sequencing words | E24 |
| which attribute selects an element | the tree | **E31** |

**The contrapositive is measured.** E24c removes two of these — a compound goal
gets neither app identity nor a completion test — and the 1B model produced
exactly the predicted failure: a structurally valid action naming a real
on-screen element irrelevant to the goal. A method that only ever helps when
applied is weakly evidenced; one that produces the predicted failure when
withheld is not.

---

## Safety (§16)

| claim | evidence | status |
|---|---|---|
| Irreversible actions require confirmation | E28; E8 records 2 corpus tasks as `GATED_CONFIRMATION` | MEASURED |
| Nothing runs a capability it was not granted | E29 | MEASURED |
| The user can see and revoke what was learned | E29 (audit + forget UI) | MEASURED |
| Operation is visible or refused | gateway `canBeSeen()` | MEASURED |
| No screen contents leave the device | no `INTERNET` permission (D7) | MEASURED (structural) |
| Package visibility is launcher-scoped, not `QUERY_ALL_PACKAGES` | D12 | MEASURED (structural) |

Three of these were **false when first written** and were found by reading the
prose against the source, not by any test (E29). Every one failed the same way:
a mechanism present in the code with no path from it to the user.

---

## Mechanisms characterised but *not* endorsed

Reported because a reviewer will ask, and because the honest answer is
interesting.

| mechanism | what was assumed | what was measured |
|---|---|---|
| Selector promotion (E26) | "the most drift-resistant form" | **E26b**: a trade, not an improvement. `content_desc` and `id` each survive 6/8 drift classes, on *different* classes |
| Skill retirement (E27) | "deliberately lenient" | **E27b**: a skill replaying cleanly **80% of the time is permanently retired 12.9%** of the time |

Both converge on the same missing number: **the frequency of each drift class in
real app updates** (E26c). Without it neither policy can be ranked and neither
constant chosen. That is a measurement of the world, not more engineering, and
naming it precisely is stronger than guessing.

---

## What is still missing before submission

| gap | why it matters | id |
|---|---|---|
| **Second device** | every device number is n=1 hardware | — |
| **Corpus TSR across arms** | §14.3's money table is the evaluation | E8 |
| Arm E baseline | the claim is "D beats E"; E has never run | D10 |
| Device recovery rate | C2's headline | E9b |
| Variance, n≥5 | every latency is n=1–2 | E14 |
| Energy per task | the metric the field omits (§3.2) | E15/E16 |
| Drift-class frequency | ranks E26b, calibrates E27b | E26c |

### Confounds carried by the numbers already collected

- **Battery 14–17%** on every run before 2026-08-19; the phone *lost* charge
  while plugged in. E6b shows the kill policy tightens under pressure, so those
  latencies are an upper bound taken in a degraded state.
- **n=1 or 2 per condition** almost everywhere.
- **One starting screen** per task, sometimes inherited from the previous run
  rather than chosen (E24c's LinkedIn start, corrected by E24e).

---

## Publication read

**Workshop-ready now** on the substrate findings (C5) plus the method and its
contrapositive: those are complete, measured, and not claimed elsewhere in the
on-device-agent literature, which evaluates on flagships and emulators.

**Conference-ready needs** the corpus run across arms (E8) and a second device.
The first is underway; the second is the single biggest validity fix available
and costs one handset.
