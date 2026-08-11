# Related Work — and an honest reassessment of novelty

*Literature check, 2026-08-11. Read this before writing the introduction, and
before the defence.*

Spec §3 opens: *"A panel will Google 'on-device Android AI agent' and find these.
You need a crisp answer for why AXON is a contribution and not a clone."* This is
that check, run early. It found something the spec did not anticipate.

---

## The finding that changes the framing

### SkillDroid: Compile Once, Reuse Forever

[arXiv:2604.14872](https://arxiv.org/abs/2604.14872) · Chen et al., April 2026

> *"A three-layer skill agent that compiles successful LLM-guided GUI
> trajectories into parameterized skill templates (sequences of UI actions with
> weighted element locators and typed parameter slots) and replays them on future
> invocations without any LLM calls."*

Compare with the spec's statement of **C1** (§3.2):

> *"No existing on-device agent compiles a successful task trace into a
> deterministic, replayable skill. AXON does… frozen into a
> selector+action+assertion script that replays with the LLM out of the loop."*

These describe the same mechanism. Point by point:

| | SkillDroid | AXON's C1 as specified |
|---|---|---|
| trace → parameterised template | ✅ | ✅ |
| typed parameter slots | ✅ | ✅ |
| element locators, robustness-weighted | ✅ | ✅ (selector preference) |
| replay with **zero** LLM calls | ✅ | ✅ |
| semantic + lexical skill matching | ✅ regex → embedding → app filter | ✅ goal-pattern + embedding |
| motivation | agents are stateless; yesterday's success is re-derived today | identical |

They also report the headline AXON planned to report: **85.3% success (+23 pp
over a stateless baseline)**, and replay at **2.4× the speed** of full LLM
execution across 79 replay rounds.

**Conclusion, stated plainly: C1 as written in the spec is no longer novel.** The
sentence *"No existing on-device agent compiles a successful task trace into a
deterministic, replayable skill"* is false as of April 2026 and must not appear
in the thesis or the paper.

Discovering this in week one is the good outcome. Discovering it in the viva is
not.

---

## What survives, and why it is still a contribution

Three differences are real, and one of them is the whole premise of the project.

### 1. SkillDroid calls a cloud API. AXON does not.

SkillDroid's LLM guidance runs against hosted GPT models. That makes skill
compilation a **latency and cost** optimisation: replace a network round-trip per
step with a local replay.

For AXON the same mechanism answers a different question. §2.1's users have
intermittent or expensive connectivity and privacy-sensitive screens; a cloud
agent is not slow for them, it is *unavailable*. On-device, planning costs ~60 s
per step on the target hardware (E2), so compilation is not an optimisation —
**it is what makes multi-step on-device tasks finish at all**.

The claim to make is therefore not "we compile skills" but:

> Skill compilation is what makes a ~1B fully-offline model a viable agent on
> low-end hardware, where per-step planning latency is two orders of magnitude
> worse than cloud inference.

That is a different claim, it is measurable, and the numbers so far support it.

### 2. Grammar-constrained action emission (C3) appears unclaimed here

Nothing in SkillDroid's description indicates constrained decoding or a formal
action grammar. It relies on a large cloud model to emit well-formed actions —
affordable when the model is GPT-class, and precisely what fails with a 1B model,
which is the regime AXON operates in.

Preliminary evidence (E4): **12/12 valid under grammar, 0/12 without**, same
model, same prompts, greedy decoding. C3 looks intact and is arguably the
stronger contribution now.

### 3. Deterministic verification (C2) is at least differently framed

SkillDroid's keywords mention "speculative replay"; the verification mechanism is
not clearly an LLM-free deterministic tree diff. AXON's position — that small
models are unreliable self-judges, so success is decided by tree assertions with
no model in the loop — needs a careful read of their §4 before any claim of
difference is made.

**Action required: obtain and read the full SkillDroid paper.** The summary above
is from the abstract and metadata; the PDF body did not extract cleanly. Sections
3–5 must be read before the introduction is written.

---

## Adjacent work

| work | relation |
|---|---|
| [AutoDroid](https://arxiv.org/abs/2308.15272) | LLM task automation on Android; app-knowledge memory, not compiled skills |
| [TraceCompiler](https://arxiv.org/abs/2608.02680) | mines noisy agent traces into mostly-deterministic workflows; general agents, not GUI/on-device |
| [Skill-DisCo](https://arxiv.org/html/2606.26669) | distils PFSM subgraphs from traces into verifiable procedural skills; general agents |
| [Trace2Skill](https://arxiv.org/pdf/2603.25158) | trajectory-local lessons → transferable skills |
| [LearnAct](https://arxiv.org/pdf/2504.13805) | few-shot mobile GUI agent from demonstrations |
| [MobileExplorer](https://arxiv.org/html/2605.26546v1) | accelerating on-device inference for mobile GUI agents via online exploration |
| [LLM-Powered Phone GUI Agents survey](https://github.com/PhoneLLM/Awesome-LLM-Powered-Phone-GUI-Agents) | TMLR survey; the map of the field |

Trace→skill compilation is clearly an **active cluster** in 2026, not an
untouched idea. That cuts both ways: the idea is validated as important, and it
is crowded.

One more datum from the search worth internalising: on AndroidWorld, leading
agents are predominantly **vision-based**, and accessibility-tree approaches
"rarely appear among the leading entries." AXON is a11y-tree-only by design
(§7.1, with a VLM fallback listed as a §17 mitigation). That is defensible on
latency and privacy grounds for on-device use, but it is a known weakness of the
modality and a reviewer will raise it.

---

## Is this publishable?

Honestly assessed.

**As an FYP: yes, comfortably.** The engineering is real, it runs on hardware,
the evaluation is designed rather than improvised, and the decision trail is
documented. The examiners' bar is not novelty against arXiv.

**As a paper: not in the spec's current framing.** "We compile traces into
skills" is now a crowded claim with a direct 2026 precedent on the same platform.

**As a paper with the framing above: plausibly, at a workshop or a systems venue
with a mobile/edge track.** The defensible package is:

1. **Fully-offline on low-end hardware.** Not a flagship, not an emulator, no
   network. A ~$150 Helio G85 handset — the device class §2.1 is about and the
   one the literature largely ignores.
2. **Constrained decoding as the enabler.** The mechanism that makes a 1B model
   usable where the cloud-model papers simply do not have the problem. E4 is
   already strong evidence.
3. **Deployment-substrate findings.** OEM power managers SIGKILL sustained
   foreground compute after ~7 minutes; throughput decays within a run as the
   device heats; unoptimised native builds are silently plausible (E1, E6).
   Work evaluated on flagships or emulators does not meet any of these. This may
   be the most transferable material in the project.
4. **Reproducibility.** Half the evaluation runs in CI with no handset, because
   `:core` carries no Android dependency. Rare in this area.

**What would raise it further:** a second device (one handset is a real validity
threat), variance across repeated runs, and a direct comparison against a
SkillDroid-style cloud baseline on the same tasks — which would let the paper say
something quantitative about what offline operation costs.

---

## Required edits to the thesis

1. **Delete** the §3.2 claim that no existing on-device agent compiles traces into
   replayable skills. Cite SkillDroid instead, and state the difference.
2. **Reframe C1** from "we do skill compilation" to "skill compilation is what
   makes fully-offline small-model agents viable on low-end hardware".
3. **Promote C3.** On current evidence it is the cleaner novelty for the on-device
   regime.
4. **Add the deployment-substrate findings** as a contribution in their own right.
5. **Read SkillDroid in full** before writing the introduction.
