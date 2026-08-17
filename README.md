# AXON

**A fully-offline agentic runtime that makes a ~1B language model reliably
operate an Android phone — on the hardware everyone else benchmarks around.**

AXON runs messages, calls, alarms, navigation and settings tasks with no cloud
API, no network permission and no per-token cost, on a $150 handset.

The engineering problem is that small models hallucinate and derail on
multi-step tasks. AXON's thesis is that **reliability is an architecture
problem, not a model-size problem**: rather than making the model smarter, make
its freedom smaller.

| defence | catches | mechanism |
|---|---|---|
| grammar-constrained decoding | malformed output — *wrong shape* | GBNF discriminated union; illegal actions have no token path |
| precondition gate | actions naming something absent — *wrong world* | executor checks the live UI tree before touching the device |
| deterministic verifier | plausible actions that still failed — *wrong outcome* | UI-tree diff against a stated post-condition, no LLM |

Verified traces are then **compiled into deterministic replayable skills**, so
repeated tasks drop from *N* model calls to zero — and persist in SQLite, so that
holds across restarts rather than within a session (E22).

### Documentation

| file | what it is |
|---|---|
| [`PROJECT_AXON_FYP_SPEC.md`](PROJECT_AXON_FYP_SPEC.md) | the full specification |
| [`docs/RELATED_WORK.md`](docs/RELATED_WORK.md) | **honest novelty assessment** — what is and is not claimable |
| [`docs/POSITIONING.md`](docs/POSITIONING.md) | **how AXON avoids being a clone** — the reframe from system to measurement |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | every deviation from the spec, with evidence and how to reverse it |
| [`docs/EXPERIMENTS.md`](docs/EXPERIMENTS.md) | every measurement, with the configuration that produced it |
| [`docs/PHASE1.md`](docs/PHASE1.md) | Phase 1 walkthrough and defence preparation |
| [`docs/PHASE5.md`](docs/PHASE5.md) | Phase 5 (C1′) walkthrough and defence preparation |
| [`docs/paper/OUTLINE.md`](docs/paper/OUTLINE.md) | paper structure, claims mapped to supporting experiments |

Three rules this repository holds itself to, each learned the hard way:

- **A number that cannot be traced to an entry in `EXPERIMENTS.md` does not get
  published.** Decision D9 is why: an unoptimised native build made every latency
  figure wrong by more than an order of magnitude, silently, while looking
  exactly like what a reader would expect from a budget phone.
- **An experiment whose baseline is strawmanned is marked void, not quietly
  improved.** E4 is why: a 100%-vs-0% result turned out to be measuring a prompt
  bug rather than the grammar.
- **A mechanism that has not run on the device has not been shown to work.** E21c
  is why: the strongest constraint in the system passed every unit test and was
  switched off entirely on hardware by Android's package-visibility filtering,
  with no error anywhere. Unit tests establish that code is correct, not that it
  is reachable.

---

## Status

**Phase 1 working.** Gemma 3 1B Q4_K_M emits schema-valid, grammar-constrained
actions fully offline on a TECNO Camon 20 (Helio G85) at ~60 s per planning step.
See [`docs/PHASE1.md`](docs/PHASE1.md).

| phase | scope | state |
|---|---|---|
| 0 | KMP module graph, §9 interfaces, §10 schemas, §10.6 grammar, CI | ✅ done |
| 1 | llama.cpp JNI, mmap load, GBNF sampler, thermal telemetry | ✅ working |
| 2 | AccessibilityDriver, perception, executor + precondition gate | ✅ done |
| 3 | Planner in the loop, end-to-end PLAN path | ✅ running on device |
| 4 | Verifier + self-healing — **C2** | |
| 5 | Skill compiler + replay — **C1′** | ✅ done, E17 measured (8,407×), replay confirmed on device |
| 6 | Gateway, capability sandbox, SQLite persistence, second client | ◐ gateway + adb client + §11 storage done |
| 7 | AXON-Bench + ablation matrix — **C3/C5** | |
| 8 | Hardening, thesis, defence | |

## Contributions

Restated after the literature check in
[`docs/RELATED_WORK.md`](docs/RELATED_WORK.md). Trace-to-skill compilation is an
*active* area in 2026 — notably [SkillDroid](https://arxiv.org/abs/2604.14872),
which compiles GUI trajectories into parameterised skill templates and replays
them without LLM calls. AXON therefore does **not** claim to have invented skill
compilation. What it claims is narrower and, on current evidence, defensible:

- **C3 — Grammar-constrained action emission for on-device agents.** A formal
  GBNF discriminated-union grammar making action emission structurally valid by
  construction. Existing skill-compiling agents call cloud-scale models, which do
  not have the malformed-output problem a 1B model has; constrained decoding is
  what makes the small-model regime workable at all.
- **C1′ — Skill compilation as a viability mechanism, not an optimisation.** For
  a cloud agent, replay saves a network round-trip. Fully offline on a Helio G85,
  planning costs ~60 s per step, so compilation is what lets multi-step tasks
  finish at all. Different claim, and measurable.
- **C2 — Deterministic post-condition verification.** UI-tree diff replaces LLM
  self-assessment; rollback and replan on mismatch.
- **C4 — Portable agentic core.** One reliability core, swappable OS drivers —
  which also buys reproducibility (see Build).
- **C5 — AXON-Bench**, plus findings about the deployment substrate itself: OEM
  power managers terminate sustained foreground compute; throughput decays within
  a run as the device heats; unoptimised native builds are silently plausible.
  Work evaluated on flagships or emulators meets none of these.

## Architecture

```
CLIENTS            in-app Compose UI │ CLI / intent │ notification
                            │ local IPC
GATEWAY            foreground service · auth · sessions · capability checks
                            │
AGENT CORE         PLANNER ─▶ EXECUTOR ─▶ VERIFIER ─▶ SKILL COMPILER
(portable)              ▲         │           │             │
                        └─────────┴───────────┴─────────────┘
                            MEMORY (episodic traces + semantic index)
                            INFERENCE (llama.cpp + GBNF sampler)
                            │
                  ── DeviceDriver seam: observe / act / assert ──
                            │
ANDROID DRIVER     AccessibilityService · Shizuku
                   [ future: Linux AT-SPI2 · Windows UIA · macOS AX ]
```

The seam is enforced by the build graph, not by convention: `:core` declares no
Android dependency, so a change that made it need one would fail to compile.

### Modules

| module | contents |
|---|---|
| `:core` | KMP. §9 contracts, §10 schemas, §10.6 grammar, §16 safety policy. No Android. |
| `:android:driver` | `AccessibilityDriver` — tree capture, gesture dispatch, Shizuku. |
| `:android:inference` | llama.cpp JNI, GBNF sampler wiring. |
| `:android:app` | Compose UI, gateway foreground service. |
| `:bench` | AXON-Bench harness. Plain JVM, so the evaluation runs without a phone. |
| `skills/` | Skill folders. A new capability is a directory, not a code change. |

## Build

Requires JDK 17 and the Android SDK (compileSdk 36, NDK 28+ from Phase 1 on).
llama.cpp is a submodule, so clone recursively:

```bash
git clone --recurse-submodules <repo>   # or: git submodule update --init --recursive

./tools/check-grammar.sh                # §10.6 grammar parses in llama.cpp itself
./gradlew :core:jvmTest                 # contracts, schemas, precondition gate, §16 policy
./gradlew :bench:test                   # shipped skill manifests validate
./gradlew :android:app:assembleDebug
```

CI runs all four on every push, and **none need a device or an emulator**. That
is C4 paying a second dividend: because the reliability core is platform-agnostic,
half the evaluation reproduces on any machine — unusual in on-device agent work,
where results typically cannot be checked without the exact handset.

### Running the model

Weights are side-loaded: AXON holds no `INTERNET` permission at all, so it cannot
download them (decision D7). That is the price of making "screen contents cannot
leave the device" an OS-enforced property rather than a promise.

```bash
./tools/fetch-model.sh --push           # Gemma 3 1B Q4_K_M → /data/local/tmp/axon
./tools/run-acceptance.sh 24 3          # chunked ablation run + aggregation
```

The run is chunked because the OEM power manager SIGKILLs sustained foreground
compute after ~7 minutes (E6). Each observation is written out as it is made, so
a kill costs the cases not yet reached rather than the whole run.

## Safety

§16 of the spec is non-negotiable, and these are enforced in code rather than
promised in prose:

- **No `INTERNET` permission.** Screen contents cannot leave the device, and the
  OS enforces it — verifiable from the manifest in ten seconds.
- **Credential fields are refused at capture.** Password, OTP, authenticator and
  payment fields are never read into memory. `SensitivePolicy`, with a published
  test corpus covering English and Urdu/Roman-Urdu surface forms.
- **Visible operation only.** Foreground service with a persistent notification;
  `START_NOT_STICKY`, so a killed agent never silently restarts.
- **Per-skill revocable capabilities.** Nothing runs a capability it did not
  declare and receive.
- **An audit log that outlives the session.** Every action — including every
  refusal — is a row the user can read and delete, in a database only this app
  can open. A log erased on each process death could not answer "what did AXON do
  yesterday", which is when someone would think to ask.
- **Irreversible actions need confirmation.** A standing grant means "you may do
  this kind of thing", never "do this particular thing without showing me".

AXON is assistive technology built on the accessibility API for its intended
purpose. The same substrate is the basis of a documented stalkerware industry;
the excluded capabilities in §6.3 are excluded deliberately and permanently.
