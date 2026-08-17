# Phase 5 — Trace-to-skill compilation and replay

Viva preparation. What was built, why, and the questions a panel will ask.

Spec: §7.7 (skill system and compiler), §7.8 (memory), §9.2, §10.3–10.5, §13.
Contribution: **C1′**.

---

## 1. Start with what is not novel

A panel that has read the literature will open here, so the thesis should too.

**Trace-to-skill compilation is not a new idea.**
[SkillDroid](https://arxiv.org/abs/2604.14872) (April 2026) compiles successful
LLM-guided GUI trajectories into parameterised skill templates and replays them
with zero LLM calls — on Android, with typed parameter slots and weighted element
locators. [TraceCompiler](https://arxiv.org/abs/2608.02680),
[Skill-DisCo](https://arxiv.org/html/2606.26669) and
[Trace2Skill](https://arxiv.org/pdf/2603.25158) do related things for agents
generally. It is an active 2026 cluster.

The spec's §3.2 claim — *"No existing on-device agent compiles a successful task
trace into a deterministic, replayable skill"* — is **false**, and must not appear
in the thesis. See [`RELATED_WORK.md`](RELATED_WORK.md).

**Saying this first is the strongest available move.** A reviewer who finds
SkillDroid after reading an overclaim discounts everything else; a reviewer who
finds it cited in paragraph two reads the rest as careful work.

## 2. What is different, and why it is not a consolation prize

Those systems call **cloud-scale models**. Replay removes a network round-trip —
a latency and cost optimisation.

AXON runs a ~1B model fully offline on a Helio G85. Measured on that device:

| | cold PLAN | compiled replay |
|---|---|---|
| per planning step | **60.4 s** (E2) | — |
| energy per step | **83.3 J** (E15) | ~0 J of inference |
| six-step task | ~6 min, ~500 J | gestures and assertions |
| tasks per 5000 mAh charge | ~138 | effectively unbounded |

At those numbers compilation is not an optimisation. **It is what lets a
multi-step task finish at all**, and the claim is correspondingly different:

> Skill compilation is a *viability* mechanism for fully-offline small-model
> agents on low-end hardware, not a latency optimisation for cloud agents.

That is measurable, it is ours, and a flagship cannot demonstrate it — on fast
hardware the gap looks like a nicety.

## 3. How it works

```
PLAN run ──▶ trace ──▶ (2nd clean run) ──▶ compile ──▶ skill
                                                        │
next request ──▶ store.match() ──┬── hit ──▶ REPLAY (0 model calls)
                                 └── miss ─▶ PLAN
```

### 3.1 The hard part is deciding what was a parameter

§7.7's algorithm has four steps. Three are transcription. Step 2 is the work:

> Tapping **"Ammi"** is a *parameter*. Tapping **"Send"** is *structure*.

Get it wrong one way and the skill only ever messages Ammi. Wrong the other way
and it types the contact's name into the message box.

AXON does this two ways, and is explicit about which is which:

| method | basis | strength |
|---|---|---|
| single trace | match selector values against the goal's recorded params | **heuristic** — can only find parameters the caller already named |
| `refine()` on a second trace | positions where two runs of the same goal *differ* | **evidence** — §7.7's intended mechanism |

A trace with no declared params compiles to a fully literal skill: correct,
replayable, and useful only for the exact task it recorded. Refinement is what
generalises it.

### 3.2 Replay is not playback

Every frozen step carries the post-condition that held when it was recorded, and
that assertion is evaluated on replay. **A skill that has stopped working says so
at the step that broke**, rather than driving blindly through a changed UI.

That is what makes a compiled skill safe to run unattended, and it is precisely
what a macro recorder does not have.

### 3.3 The fallback boundary is per-step

| step kind | `llm_fallback` | why |
|---|---|---|
| inside an app's own screens | `true` | this is what changes between app versions |
| `launch_app`, `press_key` | `false` | a failure means something the model cannot fix by guessing; calling it spends ~60 s re-deriving an action that was never in doubt |

A repaired step is narrowed to *that step's* intent, not the whole task —
otherwise the planner re-plans the entire goal from a screen halfway through one,
and the repair competes with the skill rather than serving it.

### 3.4 Compilation waits for repetition

§7.7 permits auto-compiling every verified trace. AXON requires **two clean
runs**, for two reasons:

- compiling one-offs fills the store with single-use skills that dilute matching
  and make a false match more likely — and a false match replays the wrong thing
  against a live device;
- a second run gives the compiler two traces to diff, which is how §3.1's
  evidence-based inference becomes possible at all.

### 3.5 Only clean traces compile

A run that limped to the goal through failed steps and heals encodes the mistakes
alongside the solution. Freezing it replays them forever. Such traces are still
kept — they are evaluation data and audit-log material (§16) — just not scripts.

---

## 4. Questions a panel will ask

> **"SkillDroid already did this. What is your contribution?"**

The mechanism is theirs; the regime is not. They remove a network round-trip from
a cloud-model agent. We remove ~60 s and ~83 J of *local* inference per step from
a model small enough to fit on a $150 phone. The claim is viability, not speed,
and the numbers in §2 are what make it a claim rather than an assertion.

> **"Is a compiled skill just a recorded macro?"**

No, and the difference is testable. A macro replays gestures. A compiled skill
carries a post-condition per step, checks each one, stops at the step that broke,
and can repair that step with the planner while the rest still replays
deterministically. `SkillCompilerTest` asserts each of those.

> **"How do you know replay actually costs zero model calls?"**

It is asserted with a planner that fails the test loudly if it is ever consulted.
The end-to-end test runs the same goal three times — plan, plan-and-compile,
replay — and checks that the third costs zero. On the JVM, so a reviewer can run
it without the handset.

> **"What happens when the app updates and the skill breaks?"**

The step's assertion fails, the planner repairs that step if `llm_fallback`
allows it, and the skill can be re-compiled. §17 frames drift as something to
recover from. What is **not** yet measured is how *often* skills break and which
selector types survive — that is E-series work and the honest answer is "we have
the mechanism, not the numbers".

> **"Your skill matching is lexical, not semantic. §7.8 says semantic."**

Correct, and it is a stated limitation rather than an oversight. Semantic
matching needs an embedding model and an ANN index — a second model resident on a
device that already struggles with one. Lexical slot-alignment handles
paraphrase-free repetition, which is the case C1′ needs since the claim is about
*repeated* tasks, and it fails cleanly on paraphrase instead of guessing. A false
match replays the wrong skill against a live device, so failing closed is right.

One class of paraphrase **is** handled, and the distinction is worth drawing
because it shows what the limitation actually costs. Verb paraphrase for
app-launch goals — "launch whatsapp", "kholo whatsapp", "open whatsapp" — is
collapsed exactly, by canonicalising both sides against `AppIntent`'s verb list
(E23). Measured on device: three different verbs all replayed a skill compiled
from a fourth, with zero model calls.

That is a *lookup*, not a similarity score, which is why it is safe here. There
is no threshold at which "open camera" begins matching a WhatsApp skill. The
general case has no such exact structure to exploit, which is precisely why it
needs an embedding model — the honest framing is not "we solved paraphrase" but
"where an exact normalisation exists we use it, and where it does not we decline
to guess".

---

## 5. Honest weaknesses

- ~~**Skills are in-memory.**~~ **Fixed** — SQLDelight-backed SQLite per §11
  (D11, E22). Skills, traces and the §16 audit log now outlive the process, so
  "gets faster the more it is used" no longer carries the silent qualifier
  *within a session*. What remains unmeasured is the *cost* on device: hydrate
  time at launch, and whether the trace write at task end is perceptible (E22b).
- **Matching is lexical** (§4 above) — with one exception: verb paraphrase for
  app-launch goals is handled exactly, by canonicalising both sides against
  `AppIntent`'s verb list (E23). "launch whatsapp", "kholo whatsapp" and "open
  whatsapp" are one request. Genuine semantic paraphrase still misses.
- ~~**`preferStableSelector` is a no-op.**~~ **Implemented** (E26). The compiler
  now sees the node the gate actually matched, so it can freeze a view id where
  the model wrote visible text. It still cannot invent a handle that was never
  observed, and it deliberately never promotes a parameterised selector — doing
  so would freeze one recorded run's contact into every future replay. What is
  still unmeasured is whether this reduces breakage in practice (E26b).
- **No on-device measurement yet.** Every number in §2 for the *replay* column is
  arithmetic from cold-path measurements, not a measured replay. **E17** closes
  this and is the single most important remaining experiment — it is the headline
  figure of the paper.
- **Slot inference from a single trace is weak** by construction (§3.1).

---

## 6. Reproducing

```bash
./gradlew :core:jvmTest --tests "*SkillCompilerTest*"   # compile + replay
./gradlew :core:jvmTest --tests "*AxonRuntimeTest*"     # the full learning loop
./gradlew :core:jvmTest --tests "*SkillStoreTest*"      # matching + O5 install
```

None need a device. That is C4 paying a second dividend: the C1′ claim is
checkable by a reviewer who does not own the handset, which is unusual in this
area.
