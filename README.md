# AXON

**An offline, self-healing, on-device agentic runtime for Android with learned
skill compilation.**

AXON lets a small quantized language model (~2B) reliably operate an Android
phone — messages, calls, alarms, navigation, settings — fully offline, with no
cloud API and no per-token cost.

The engineering problem is that small models hallucinate and derail on
multi-step tasks. AXON's thesis is that **reliability is an architecture
problem, not a model-size problem**: rather than making the model smarter, make
its freedom smaller.

| defence | catches | mechanism |
|---|---|---|
| grammar-constrained decoding | malformed output — *wrong shape* | GBNF discriminated union; illegal actions have no token path |
| precondition gate | actions referencing things that aren't there — *wrong world* | executor checks the live UI tree before touching the device |
| deterministic verifier | actions that were plausible and still failed — *wrong outcome* | UI-tree diff against a stated post-condition, no LLM |

Verified traces are then **compiled into deterministic replayable skills**, so
repeated tasks drop from *N* model calls to zero and the system gets faster the
more it is used.

### Documentation

| file | what it is |
|---|---|
| [`PROJECT_AXON_FYP_SPEC.md`](PROJECT_AXON_FYP_SPEC.md) | the full specification |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | every deviation from the spec, with evidence and how to reverse it |
| [`docs/EXPERIMENTS.md`](docs/EXPERIMENTS.md) | every measurement, with the exact configuration that produced it |
| [`docs/PHASE1.md`](docs/PHASE1.md) | Phase 1 walkthrough and defence preparation |
| [`docs/paper/OUTLINE.md`](docs/paper/OUTLINE.md) | paper structure, claims mapped to the experiments that support them |

A number that cannot be traced to an entry in `EXPERIMENTS.md` does not go in the
paper. That rule exists because of decision D9: an unoptimised native build made
every latency figure wrong by more than an order of magnitude, silently, while
looking entirely plausible.

---

## Status

**Phase 1 in progress** — on-device inference works. Gemma 3 1B Q4_K_M emits
schema-valid grammar-constrained actions on a TECNO Camon 20 (Helio G85) in
60.7 s per planning step. See [`docs/PHASE1.md`](docs/PHASE1.md).

| phase | scope | state |
|---|---|---|
| 0 | KMP module graph, §9 interfaces, §10 schemas, §10.6 grammar, CI | ✅ done |
| 1 | llama.cpp JNI, mmap load, GBNF sampler, thermal telemetry | ✅ working |
| 2 | AccessibilityDriver, perception, executor + precondition gate | |
| 3 | Planner in the loop, end-to-end PLAN path | mid-year demo |
| 4 | Verifier + self-healing — **C2** | |
| 5 | Skill compiler + replay — **C1** | |
| 6 | Gateway, capability sandbox, second client | |
| 7 | AXON-Bench + ablation matrix — **C3/C5** | |
| 8 | Hardening, thesis, defence | |

## Contributions

- **C1 — Trace-to-skill compilation.** Verified execution trace → parameterised
  deterministic skill with embedded assertions and an LLM-fallback boundary.
- **C2 — Deterministic post-condition verification.** UI-tree diff replaces LLM
  self-assessment; rollback + replan recovery.
- **C3 — Grammar-constrained agentic action space.** Formal GBNF action grammar
  making action emission structurally valid by construction, with an ablation.
- **C4 — Portable agentic core.** One reliability core, swappable OS drivers.
- **C5 — AXON-Bench.** Multi-step on-device task benchmark with baselines.

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
git clone --recurse-submodules <repo>      # or: git submodule update --init --recursive

./tools/check-grammar.sh           # §10.6 grammar parses in llama.cpp itself
./gradlew :core:jvmTest            # contracts, schemas, §16 safety policy
./gradlew :bench:test              # shipped skill manifests validate
./gradlew :android:app:assembleDebug
```

CI runs all four on every push. None need a device or an emulator — the same
property that makes the evaluation reproducible by someone who does not own the
handset.

### Running the model

Weights are side-loaded: AXON holds no `INTERNET` permission at all, so it cannot
download them (see [D7](docs/DECISIONS.md)). That is the cost of making "screen
contents cannot leave the device" an OS-enforced property.

```bash
./tools/fetch-model.sh --push      # Gemma 3 1B Q4_K_M → /data/local/tmp/axon
./gradlew :android:inference:connectedDebugAndroidTest
```

## Safety

§16 of the spec is non-negotiable, and these are enforced in code rather than
promised in prose:

- **No `INTERNET` permission.** Screen contents cannot leave the device, and the
  OS enforces it — verifiable from the manifest.
- **Credential fields are refused at capture.** Password, OTP, authenticator and
  payment fields are never read into memory. `SensitivePolicy`, with a test
  corpus, covering English and Urdu/Roman-Urdu surface forms.
- **Visible operation only.** Foreground service with a persistent notification;
  `START_NOT_STICKY`, so a killed agent never silently restarts.
- **Per-skill revocable capabilities.** Nothing runs a capability it did not
  declare and receive.
- **Irreversible actions need confirmation.** A standing grant means "you may do
  this kind of thing", never "do this particular thing without showing me".

AXON is assistive technology built on the accessibility API for its intended
purpose. The same substrate is the basis of a documented stalkerware industry;
the excluded capabilities in §6.3 are excluded deliberately and permanently.
