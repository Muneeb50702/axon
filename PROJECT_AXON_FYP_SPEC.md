# Project AXON — Master Specification & Build Document
An Offline, Self-Healing, On-Device Agentic Runtime for Android with Learned Skill Compilation

## 0. Document Control
Field
Value
Codename
AXON (Agentic eXecution & Orchestration Nucleus) — rename freely
Version
1.0 (Master Spec)
Status
Pre-implementation. Authoritative build reference.
Author
Musa
Primary purpose
(1) Final Year Project document. (2) Machine-readable build spec that an AI coding agent (e.g. Claude Code) can execute section by section.
Target platform (deliverable)
Android (arm64-v8a), API 26+
Target platform (architected for)
Cross-platform via pluggable driver layer (Linux, Windows, macOS as post-FYP)
Reading time
~40 min full

### 0.1 How to use this document with an AI coding agent
This spec is written so a coding agent can build AXON without re-deriving decisions. When feeding to Claude Code (or similar):
- Do not paste the whole document at once. Point the agent at this file in the repo and instruct it to build one Phase at a time (see §13), in order. Each phase has explicit deliverables and acceptance criteria.
- Start every build session with: "Read PROJECT_AXON_FYP_SPEC.md. We are implementing Phase N only. Confirm the module contracts in §8 and §9 before writing code."
- The interface contracts in §9, the data schemas in §10, and the GBNF grammar in §10.6 are normative — the agent must implement them exactly, not approximate them.
- After each phase, run the acceptance test in §13 before moving on.
- §16 (safety) constraints are non-negotiable guardrails — the agent must not ship the excluded capabilities in §6.3.

## 1. Executive Summary
AXON is an on-device agentic runtime that lets a small quantized language model (2B–4B params) reliably operate an Android phone — sending messages, setting alarms, navigating apps, changing settings — fully offline, with no cloud API and no per-token cost. It targets the two billion people on mid-range Android devices with poor or intermittent connectivity, for whom cloud agents are structurally unavailable.
The core engineering problem is that small models hallucinate and cannot reliably drive multi-step tasks. AXON solves this not by making the model smarter, but by making the model's freedom smaller: grammar-constrained action generation, a deterministic planner–executor split, and a verifier loop that checks the phone's actual UI state after every action and self-heals on mismatch. Successful task traces are then compiled into deterministic replayable skills, so the system gets faster and more reliable the more it is used — invoking the LLM only when a known skill's assumptions break.
The novel research contribution is this trace-to-skill compilation + deterministic-verifier self-healing layer, evaluated against raw-LLM baselines on a purpose-built multi-step task benchmark.

## 2. Problem Statement & Motivation
### 2.1 The gap
Cloud agents (the current default) require constant connectivity, send private screen contents to third-party servers, and cost money per action. This is fine for a developer in a city with fibre. It fails for:
- Users on intermittent or expensive mobile data (much of South Asia, Africa, rural everywhere).
- Privacy-sensitive tasks — an agent reading your banking app, health app, or private chats should not upload that screen anywhere.
- Low-literacy users who could operate a phone by voice but cannot navigate complex UIs (this is the bridge to a future voice-first extension).
### 2.2 Why now (verified trend context)
- Agent research is the fastest-moving area in AI: long-horizon planning papers grew +510% in H1 2026, the single fastest-growing research topic, and the field has shifted from "can we build agents?" to "how do we make agents plan, reason, use tools, and judge their own outputs?"
- On-device models crossed the usefulness threshold in 2025–2026. Purpose-built mobile models (Google's Gemma 3n, using Per-Layer Embeddings and selective parameter activation to run an 8B-total model in a ~2B–4B memory footprint, trained across 140+ languages) and sub-4B reasoners (Phi-4 Mini 3.8B, Qwen 3.5 small, SmolLM3-3B) now run at usable speeds on flagship and upper-mid phones.
- Constrained decoding matured: llama.cpp's GBNF grammars and XGrammar make syntactically invalid model output mechanically impossible by masking non-conforming tokens at each sampling step — the key to making a small model a reliable action-emitter.
- The Android control substrate exists: AccessibilityService exposes a structured UI tree, and Shizuku grants ADB-level privileges without root or a PC.
### 2.3 The core technical problem this project attacks
A 3B model, prompted freely to "operate the phone," will hallucinate app names, invent buttons that don't exist, misjudge whether an action succeeded, and derail on step 4 of a 6-step task.
AXON's thesis: reliability is an architecture problem, not a model-size problem. The intelligence is scaffolding, not the model.

## 3. Related Work & Honest Competitive Positioning
Read this section before your defense. A panel will Google "on-device Android AI agent" and find these. You need a crisp answer for why AXON is a contribution and not a clone. It is here.
### 3.1 What already exists (as of 2026)
Project
What it is
License
Mythara
Open-source local-first agentic "AI OS layer" for Android; 65+ on-device tools (calls, SMS, calendar, Termux); Shizuku for cosmetic system tweaks
MIT
Operit AI
On-device AI agent/chat that runs shell commands via Shizuku
LGPL-3.0
Open-AutoGLM-Android / Ruto-GLM
Automates device actions using the AutoGLM vision-language model (screenshot → action)
GPL-3.0 / Apache-2.0
Roubao
Open-source VLM screenshot-analyze-operate agent (Qwen-VL / GPT-4V) running natively on Android
—
android-shizuku MCP / rish-mcp
Expose an Android device's Shizuku shell to external LLMs as MCP tools
MIT

Two dominant existing paradigms:
- Tool-runner agents (Mythara, Operit): a big/cloud model calls a fixed library of hand-written device tools. Reliable per-tool, but the tool set is static and hand-maintained, and reasoning quality depends on model size.
- VLM screenshot loops (AutoGLM, Roubao): a vision model looks at a screenshot and emits taps. Flexible across any UI, but slow, expensive, non-deterministic, and no memory of past success — every run re-solves from scratch.
### 3.2 What AXON does that these do not
AXON is explicitly the reliability-and-learning layer that both paradigms lack:
- Learned skill compilation (primary novel contribution). No existing on-device agent compiles a successful task trace into a deterministic, replayable skill. AXON does. The first successful "send location to Ammi on WhatsApp" is slow and LLM-driven; it is then frozen into a selector+action+assertion script that replays with the LLM out of the loop, self-repairing only where the UI has diverged. The system gets faster and more reliable with use. This is the paper.
- Deterministic verifier-based self-healing. Instead of asking the model "did that work?" (which small models get wrong), AXON diffs the actual accessibility tree against an expected post-condition — deterministic, no LLM — and on mismatch performs rollback + re-plan with the failure injected into context.
- Formal-grammar-constrained action layer + an ablation proving it. AXON emits actions under a GBNF grammar so the model cannot produce a malformed or out-of-vocabulary action. The evaluation includes an ablation showing constrained + verified 3B beating naive 7B on task success — turning "small models are unreliable" into a measured, defeated claim.
- Cross-platform driver abstraction. The reliability core is platform-agnostic; Android is the reference driver. None of the above are architected for portability.
- Rigorous evaluation. Student/hobby projects ship demos. AXON ships a benchmark + baselines + ablations, which is what elevates it from "app" to "research."
### 3.3 One-line positioning
"Existing on-device agents either hand-write every tool or re-solve every task from a screenshot. AXON makes a small offline model reliable through constrained decoding and deterministic verification, then compiles what works into skills that get faster with use — evaluated, not just demoed."

## 4. Novel Contributions (state these explicitly in the thesis)
- C1. Trace-to-Skill Compilation. An algorithm that converts a verified successful execution trace into a parameterised, deterministic skill with embedded assertions and an LLM-fallback boundary. (§7.7)
- C2. Deterministic Post-Condition Verification for Self-Healing. UI-tree-diff verification replacing LLM self-assessment, with a rollback+replan recovery policy. (§7.6)
- C3. Grammar-Constrained Agentic Action Space. A formal GBNF action grammar making small-model action emission structurally valid by construction, with an empirical reliability ablation. (§7.4, §10.6, §14)
- C4. Portable Agentic Core with a Driver Abstraction. A single reliability/planning core with swappable OS drivers. (§9.1)
- C5. AXON-Bench. An open benchmark of multi-step on-device tasks with success/efficiency/robustness metrics and reference baselines. (§14)
Any two of these, executed and evaluated well, is a strong FYP. All five is publishable.

## 5. (Reserved)

## 6. Project Objectives & Scope
### 6.1 SMART objectives
ID
Objective
Measure of success
O1
Run a Q4-quantized 2B–4B model fully on-device with grammar-constrained JSON action output
100% syntactically valid actions across ≥500 generations; median plan-step latency ≤ target (§17)
O2
Execute multi-step device tasks via planner–executor split
≥80% task success on the AXON-Bench "core" set (Phase 3)
O3
Deterministic verification + self-healing recovers from single-step failures
≥60% of injected single-step failures auto-recovered without user intervention
O4
Compile ≥1 verified trace into a deterministic replayable skill
Replay of a compiled skill runs with 0 LLM calls on unchanged UI; ≥5× faster than cold LLM run
O5
Ship an extensible skill format + local gateway
A new skill added as a folder with no core code change; gateway reachable by ≥2 client types
O6
Evaluate against baselines with ablations
Reproducible results table: constrained+verified small model vs naive small vs naive larger

### 6.2 In scope (FYP deliverable)
- Android reference app (Kotlin + Jetpack Compose).
- On-device inference (llama.cpp via KMP/JNI), GBNF-constrained action generation.
- Perception (AccessibilityService UI-tree capture), Executor (state machine), Verifier (tree-diff), Planner.
- Skill system + trace-to-skill compiler + 5 reference skills (WhatsApp send, phone call, alarm, maps navigate, settings toggle).
- On-device episodic + semantic memory.
- Local gateway (socket IPC) + at least two clients (in-app UI, CLI/intent).
- Capability-permission sandbox.
- AXON-Bench + evaluation harness + results.
### 6.3 Out of scope / explicitly excluded (safety + scope)
- No stalkerware-class capability. No covert operation, no exfiltration of screen contents off-device, no reading of one-time-passcode / authenticator fields, no operation without an explicit, visible user-granted foreground service. (See §16.)
- No credential harvesting, no automated login to third-party accounts on the user's behalf without explicit per-session consent.
- No iOS/Linux/Windows implementation (architected for, not delivered — Linux driver is a stretch goal only).
- No custom model training from scratch (fine-tuning is a stretch goal, not core).
- No always-on background autonomy beyond user-defined, permission-gated triggers.

## 7. System Architecture
### 7.1 Layered view
┌──────────────────────────────────────────────────────────────┐│  CLIENTS                                                       ││  In-app Compose UI │ CLI / Intent │ Notification channel │ …   │└───────────────┬──────────────────────────────────────────────┘                │ (local socket / Binder IPC)┌───────────────▼──────────────────────────────────────────────┐│  GATEWAY  (local daemon / foreground service)                 ││  auth · session mgmt · request routing · capability checks    │└───────────────┬──────────────────────────────────────────────┘┌───────────────▼──────────────────────────────────────────────┐│  AGENT CORE  (portable — KMP shared module)                   ││                                                               ││   ┌─────────┐   ┌──────────┐   ┌──────────┐   ┌───────────┐   ││   │ PLANNER │──▶│ EXECUTOR │──▶│ VERIFIER │──▶│ SKILL      │   ││   │ (LLM,   │   │ (state   │   │ (tree    │   │ COMPILER   │   ││   │ constr.)│◀──│ machine) │◀──│ diff)    │   │            │   ││   └────┬────┘   └────┬─────┘   └────┬─────┘   └─────┬─────┘   ││        │             │              │               │         ││   ┌────▼─────────────▼──────────────▼───────────────▼─────┐   ││   │ MEMORY  (episodic traces + semantic skill/embedding)  │   ││   └───────────────────────────────────────────────────────┘   ││   ┌───────────────────────────────────────────────────────┐   ││   │ INFERENCE ENGINE  (llama.cpp, GBNF grammar sampler)    │   ││   └───────────────────────────────────────────────────────┘   │└───────────────┬──────────────────────────────────────────────┘                │  DRIVER INTERFACE  (trait/interface: observe/act/assert)┌───────────────▼──────────────────────────────────────────────┐│  ANDROID DRIVER  (reference impl)                             ││  AccessibilityService (UI tree, gestures) · Shizuku/ADB       ││  (privileged ops) · screenshot fallback (optional VLM)        │└──────────────────────────────────────────────────────────────┘     [ future drivers: Linux AT-SPI2 · Windows UIA · macOS AX ]
### 7.2 The control loop (single task)
receive goal  ├─ check skill store: is there a compiled skill matching this goal + params?  │     ├─ YES → REPLAY path (deterministic; LLM only on assertion failure)  │     └─ NO  → PLAN path (below)  │  └─ PLAN path:       loop until goal satisfied or budget exhausted:         1. PERCEIVE   → capture accessibility tree → compact state repr.         2. PLAN       → LLM selects ONE next action from valid action set                          (GBNF-constrained JSON; preconditions listed)         3. VALIDATE   → executor checks action preconditions vs current state                          (reject impossible actions BEFORE touching device)         4. ACT        → executor performs the action via driver         5. VERIFY     → capture new tree → diff vs expected post-condition                          ├─ MATCH    → append step to trace, continue                          └─ MISMATCH → SELF-HEAL (rollback + replan w/ failure ctx)       on success:         6. record verified trace → offer/auto compile to skill (C1)
### 7.3 Inference Engine
Responsibility: load the quantized model once (mmap), expose constrained + unconstrained generation, manage thermal/latency budget.
Key decisions (verified):
- Engine: llama.cpp (GGUF, GBNF grammar support, Vulkan GPU acceleration on Android, arm64-v8a, min API 26). Access from Kotlin via Llamatik (KMP, Maven Central, supports concurrent sessions and grammar sampling) or the official llama.android JNI bindings. Alternative: ExecuTorch (1.0 GA Oct 2025, 12+ hardware backends) if NPU delegation becomes a priority.
- Loading: always mmap the weights — never heap-allocate the model, so the OS pages under memory pressure instead of killing the app.
- Quantization: Q4_K_M default (best quality/RAM tradeoff on mobile); step to Q5_K_S only with confirmed headroom.
- Thermal instrumentation from day one — demos run 10s, real tasks run minutes; add adaptive throttling based on real thermal state.
### 7.4 Planner (grammar-constrained decision-maker)
Responsibility: given the current compact UI state + goal + valid action menu, emit exactly one next action as schema-valid JSON.
Mechanism:
- The action is generated under the GBNF action grammar (§10.6). At each token, the sampler masks every token that would violate the grammar → malformed or out-of-enum actions are impossible by construction.
- Two important truths about constrained decoding, handled explicitly:
- The grammar is not injected into the prompt — the prompt must describe the available actions and current elements in natural language, while the grammar enforces shape. (For tool-calling templates specifically, schemas are injected — we use the raw-grammar path, so we describe in-prompt.)
- Grammar does not guarantee completion if the model runs out of tokens mid-object → set a generous max_tokens for the action object and validate on parse; retry once on truncation.
- Heavy constraint can slightly dent reasoning. Mitigation — two-call pattern for hard steps: (a) a short unconstrained "reason about next step" call, then (b) a constrained extract-to-action call. Use single-call constrained for easy steps to save latency.
- Planner never executes. It only selects. Execution, retries, transitions belong to the Executor.
### 7.5 Executor (deterministic state machine)
Responsibility: own the actual doing. Validate action preconditions against live state, perform via driver, manage retries and state transitions, maintain the running trace.
- Precondition gate: before any action touches the device, the executor confirms the target element/state exists in the current tree. A hallucinated "tap Send button" with no Send node present is rejected here, before harm, and returned to the planner as an invalid-action signal.
- Action budget: hard cap on steps per task (configurable, default 15) to prevent runaway loops.
- Deterministic; contains no LLM calls.
### 7.6 Verifier (self-healing) — Contribution C2
Responsibility: after each action, decide deterministically whether the world changed as intended, and drive recovery.
- Each action carries an expected post-condition (e.g., "a node with text matching sent appears" / "screen title == 'Alarm'"). After ACT, capture the new accessibility tree and diff against the post-condition.
- Match → commit the step to the trace.
- Mismatch → self-heal: roll back to the last known-good checkpoint, inject a structured description of the failure (what was expected, what the tree actually shows) into planner context, and re-plan. Escalate to the user only after N failed heal attempts (default 2).
- Crucially the verifier uses no LLM — small models are unreliable self-judges, so success/failure is decided by deterministic tree assertions.
### 7.7 Skill System + Trace-to-Skill Compiler — Contribution C1
Skill format (a folder — the OpenClaw-style extensibility insight):
skills/whatsapp_send/  manifest.json         # id, name, goal-pattern, parameters, required capabilities  prompts/              # optional planner hints specific to this skill  traces/               # verified compiled traces (the deterministic scripts)  assertions.json       # post-conditions per step
A new capability = drop in a folder. No core code change (satisfies O5).
Compiler algorithm (verified trace → deterministic skill):
- Take a trace that reached the goal with all post-conditions satisfied.
- Parameterise variable parts (contact name, message text, alarm time) into typed slots, inferred by diffing repeated runs / from the original goal parse.
- Convert each step into {selector, action, expected_assertion, llm_fallback: bool}.
- Store as a replayable skill keyed by a goal-pattern (semantic embedding + intent tag).
Replay path (why it gets faster with use):
- On a new goal, match against skill goal-patterns (semantic). On hit, replay the deterministic steps with the LLM out of the loop. Each step still runs its assertion; only if an assertion fails (UI diverged — app update, A/B layout) does AXON fall back to the LLM for that step only, then optionally re-compile.
- Result: repeated tasks drop from "N LLM calls" to "0 LLM calls, milliseconds," and reliability rises as the skill hardens. This is the measurable headline (O4).
### 7.8 Memory
- Episodic: append-only store of full execution traces (goal, steps, outcomes, timings) — the raw material the compiler consumes and the eval harness measures.
- Semantic: on-device vector store of skill goal-patterns and reusable UI-element embeddings for skill matching and cross-task recall. Use a lightweight on-device embedding model + a local ANN index (e.g. a compact HNSW/sqlite-vec-style store). (pgvector/Postgres is a server tech — for on-device use a local vector index; note this in the thesis as an explicit port.)
### 7.9 Gateway & IPC
- A foreground service exposing the agent over local Binder/AIDL (and optionally a localhost socket) so the agent is a service other things talk to, not an app.
- Clients: in-app Compose UI; CLI via adb/intent; notification/quick-tile trigger. This is the "OS-level platform, not an app" story and makes the future watch/TV/car clients "just another client."
- Localhost-bound, authenticated (bearer token), origin-checked — mirrors the security posture of existing Shizuku-MCP bridges.
### 7.10 Capability / Permission Sandbox
- Each skill declares required capabilities in its manifest (contacts:read, whatsapp:send, settings:write, shizuku:shell).
- User grants per-skill, revocable, surfaced in a permissions screen. Nothing runs a capability it didn't declare and get granted. This is both the safety story and what makes AXON feel OS-like rather than a script runner.

## 8. Component Responsibility Matrix (for the coding agent)
Component
Owns
Never does
LLM?
Inference Engine
model load, generation, thermal budget
app logic
n/a
Planner
choose next action (constrained)
execute, verify
yes
Executor
precondition-gate, act, retry, trace
decide what to do
no
Verifier
post-condition diff, trigger heal
execute
no
Skill Compiler
trace → deterministic skill
run tasks
no
Skill Store
match goal→skill, hold folders
plan
no
Memory
persist traces + embeddings
act
(embed only)
Gateway
auth, sessions, routing, cap-checks
plan/execute
no
Driver
observe(tree), act(gesture), assert(query)
plan
no
Perception
tree capture → compact state
choose actions
no

## 9. Interface Contracts (normative)
The coding agent must implement these signatures. Language shown is Kotlin (KMP commonMain). Types are illustrative but the shape is required.
### 9.1 Driver interface (the portability seam)
interface DeviceDriver {    /** Capture current UI as a structured, hashable tree. */    suspend fun observe(): UiTree    /** Perform a validated action; returns raw outcome (not success judgment). */    suspend fun act(action: DeviceAction): ActResult    /** Deterministically evaluate a post-condition against current UI. */    suspend fun assert(condition: PostCondition): Boolean    /** Capabilities this driver can satisfy (drives the sandbox). */    fun capabilities(): Set<Capability>}
Android impl: AccessibilityDriver (AccessibilityService tree + gesture dispatch; Shizuku for privileged ops; optional screenshot+VLM fallback for canvas-drawn UIs where the a11y tree is empty).
### 9.2 Core loop services
interface Planner {          // constrained; the only LLM caller in the loop    suspend fun nextAction(state: CompactState, goal: Goal, menu: ActionMenu): DeviceAction}interface Executor {    suspend fun run(action: DeviceAction, state: UiTree): StepOutcome  // precondition-gates internally}interface Verifier {    suspend fun verify(expected: PostCondition, actual: UiTree): VerifyResult  // MATCH | MISMATCH(reason)}interface SkillStore {    suspend fun match(goal: Goal): CompiledSkill?    suspend fun install(folder: SkillFolder)}interface SkillCompiler {    suspend fun compile(trace: VerifiedTrace): CompiledSkill}interface AgentRuntime {      // orchestrates §7.2    suspend fun execute(goal: Goal): TaskResult}
### 9.3 Inference
interface InferenceEngine {    suspend fun generate(prompt: String, grammar: Gbnf? = null, maxTokens: Int): String    fun thermalState(): ThermalState}

## 10. Data Models & Schemas (normative)
### 10.1 DeviceAction (what the model emits, grammar-constrained)
{  "action": "tap | long_press | swipe | input_text | scroll | launch_app | press_key | wait",  "target": { "by": "text | id | content_desc | class | coord", "value": "string" },  "text": "string (only for input_text)",  "direction": "up | down | left | right (only for swipe/scroll)",  "app": "package name (only for launch_app)",  "key": "home | back | enter (only for press_key)",  "expect": {    "type": "node_present | node_absent | text_matches | screen_title | app_foreground",    "value": "string"  }}
action and enum fields are closed enums in the grammar → the model cannot invent an action type or a target-by kind.
### 10.2 CompactState (perception output fed to planner)
A token-efficient serialization of the current screen: foreground package, screen title, and a pruned list of interactable nodes {index, role, text/desc, bounds, enabled}. Prune decoration; cap node count; stable indices so the planner can reference by index.
### 10.3 Skill manifest
{  "id": "whatsapp_send",  "name": "Send a WhatsApp message",  "goal_pattern": "send {message} to {contact} on whatsapp",  "parameters": [    {"name": "contact", "type": "contact_name", "required": true},    {"name": "message", "type": "string", "required": true}  ],  "required_capabilities": ["contacts:read", "whatsapp:send"],  "version": 1}
### 10.4 CompiledSkill step
{  "step": 3,  "selector": {"by": "content_desc", "value": "Send"},  "action": "tap",  "expect": {"type": "text_matches", "value": "\\bdelivered\\b|\\bsent\\b"},  "llm_fallback": true}
### 10.5 VerifiedTrace (episodic memory record + compiler input)
{  "trace_id": "uuid",  "goal": "…",  "params": {"contact": "Ammi", "message": "on my way"},  "steps": [ {"action": {…}, "pre_ok": true, "post_ok": true, "latency_ms": 812} ],  "outcome": "success",  "llm_calls": 6,  "total_ms": 5400,  "device": "…", "model": "gemma-3n-e2b-q4_k_m"}
### 10.6 GBNF Action Grammar (reference — implement in llama.cpp)
This is a starting grammar; refine to match §10.1 exactly. It forbids malformed actions by construction.
root        ::= "{" ws "\"action\":" ws action ws "," ws "\"target\":" ws target                ( ws "," ws "\"text\":" ws string )?                ws "," ws "\"expect\":" ws expect ws "}"action      ::= "\"tap\"" | "\"long_press\"" | "\"swipe\"" | "\"input_text\""              | "\"scroll\"" | "\"launch_app\"" | "\"press_key\"" | "\"wait\""target      ::= "{" ws "\"by\":" ws by ws "," ws "\"value\":" ws string ws "}"by          ::= "\"text\"" | "\"id\"" | "\"content_desc\"" | "\"class\"" | "\"coord\""expect      ::= "{" ws "\"type\":" ws etype ws "," ws "\"value\":" ws string ws "}"etype       ::= "\"node_present\"" | "\"node_absent\"" | "\"text_matches\""              | "\"screen_title\"" | "\"app_foreground\""string      ::= "\"" ( [^"\\] | "\\" . )* "\""ws          ::= [ \t\n]*
(Note: GBNF converts a subset of JSON-Schema Draft 7; keep patterns simple — PCRE shorthands like \d\w\s\b are known to break the JSON-Schema→GBNF converter, so express character classes explicitly.)

## 11. Technology Stack (verified, with rationale)
Layer
Choice
Rationale
Core language
Kotlin Multiplatform (commonMain core; androidMain driver)
Android is primary → Kotlin native; KMP shares the reliability core to desktop/iOS later. Lower friction than Rust for the Android a11y layer, which must be Kotlin regardless.
Alt core
Rust (portable core + JNI)
Only if maximum portability outweighs velocity. Not recommended for FYP timeline.
Inference
llama.cpp (GGUF) via Llamatik (KMP, Maven Central) or llama.android JNI
GBNF grammar support, Vulkan accel, mmap, concurrent sessions. Verified 2026-current.
Model (primary)
Gemma 3n E2B (Q4_K_M, ~2 GB footprint)
Multimodal (screenshot fallback) + 140+ languages (bridges to future Urdu voice extension); mobile-purpose-built (Per-Layer Embeddings).
Model (reasoning alt)
Phi-4 Mini 3.8B or Qwen 3.5 (small) (Q4_K_M ~2.7 GB)
Smartest sub-4B / strong multilingual, if planning quality > multimodal need.
Model (fast router)
FunctionGemma 270M or Gemma 3 1B (~720 MB)
Instant intent classification / skill routing before invoking the heavier planner.
Constrained decoding
GBNF (llama.cpp native); consider XGrammar for speed
XGrammar is current SOTA for constrained decoding throughput.
UI
Jetpack Compose
Standard modern Android UI.
Device control
AccessibilityService (tree + gestures) + Shizuku (ADB-priv, no root)
Verified standard substrate; structured tree ideal for deterministic diffing.
Serialization
kotlinx.serialization
Parse constrained JSON in the shared layer.
On-device vector store
local ANN index (sqlite-vec-class / HNSW) + small embedder
Semantic skill matching without a server.
Async
Kotlin Coroutines / Flow
Stream tokens, structure the loop.
Persistence
SQLite (traces, skills, permissions)
Local, robust.
Build
Gradle (KMP), Android NDK + CMake (for llama.cpp .so)
arm64-v8a, -DLLAMA_VULKAN=ON, android-26.

Version policy for the coding agent: pin the latest stable of each at build time. Verified anchors: ExecuTorch 1.0 GA (Oct 2025), FunctionGemma 270M (Dec 2025). Do not hard-code versions from this doc — resolve current at implementation.

## 12. Repository Structure
axon/├── PROJECT_AXON_FYP_SPEC.md        # this file — the agent's source of truth├── settings.gradle.kts├── core/                           # KMP shared module (commonMain)│   ├── runtime/                    # AgentRuntime, control loop (§7.2)│   ├── planner/                    # constrained planner│   ├── executor/                   # state machine, precondition gate│   ├── verifier/                   # tree-diff self-healing│   ├── skills/                     # store, compiler, manifest models│   ├── memory/                     # episodic + semantic│   ├── inference/                  # engine interface + GBNF│   ├── model/                      # DeviceAction, CompactState, schemas (§10)│   └── driver/                     # DeviceDriver interface (§9.1)├── android/                        # androidMain + app│   ├── app/                        # Compose UI, gateway foreground service│   ├── driver/                     # AccessibilityDriver, Shizuku bridge│   └── inference/                  # Llamatik/llama.android JNI wiring├── skills/                         # shippable skill folders (§7.7)│   ├── whatsapp_send/│   ├── phone_call/│   ├── set_alarm/│   ├── maps_navigate/│   └── settings_toggle/├── bench/                          # AXON-Bench tasks + harness (§14)│   ├── tasks/                      # task specs + gold post-conditions│   ├── runner/                     # executes tasks across configs│   └── results/                    # generated tables/plots└── docs/                           # architecture diagrams, defense deck

## 13. Build Roadmap & FYP Timeline
Structured as 8 phases mapped to a typical two-semester FYP. Each phase = agent build unit with an acceptance test.
### Semester 1 — Foundation & Core Loop
Phase 0 — Scaffolding (Week 1–2)
- KMP project, module skeleton (§12), CI, empty interfaces (§9).
- Accept: project builds; interfaces compile; empty app launches.
Phase 1 — On-device inference + constrained output (Week 3–5)
- Wire llama.cpp via Llamatik/llama.android; mmap load Gemma 3n E2B Q4_K_M; implement InferenceEngine; implement the GBNF action grammar (§10.6).
- Accept: 500 generations → 100% schema-valid actions; thermal telemetry logs; median gen latency recorded.
Phase 2 — Perception + Executor (Week 6–8)
- AccessibilityDriver.observe() → CompactState; Executor with precondition gate + gesture dispatch; action budget.
- Accept: agent can perform a single correct action on a real app from a hand-written plan; impossible actions are rejected pre-execution.
Phase 3 — Planner + full control loop (Week 9–12)
- Planner.nextAction in the loop; end-to-end PLAN path (no verifier yet); 5 target tasks runnable.
- Accept: ≥3 of 5 core tasks complete end-to-end at least once. (Mid-year demo.)
### Semester 2 — Reliability, Learning, Evaluation
Phase 4 — Verifier + self-healing (Week 1–3) — C2
- Post-condition diffing; rollback+replan; escalation policy.
- Accept: ≥60% of injected single-step failures auto-recover (O3).
Phase 5 — Skill compiler + replay (Week 4–6) — C1
- Trace → CompiledSkill; skill store matching; replay path with per-step assertion + LLM fallback.
- Accept: a compiled skill replays with 0 LLM calls on unchanged UI, ≥5× faster than cold run (O4).
Phase 6 — Gateway, sandbox, extensibility (Week 7–8) — C4/O5
- Foreground-service gateway (auth, sessions); capability sandbox; second client (CLI/intent); prove a new skill installs as a folder with no core change.
- Accept: new skill added without touching core; two client types drive the same agent.
Phase 7 — AXON-Bench + evaluation (Week 9–11) — C3/C5
- Benchmark tasks + harness; run the ablation matrix (§14); generate results.
- Accept: reproducible results table; constrained+verified small model beats naive baseline on success rate (O6).
Phase 8 — Hardening, docs, defense (Week 12+)
- Bug-fix, thermal/latency tuning, thesis write-up, demo video, defense deck.
De-risking rule: if time slips, ship Phases 0–5 well (that alone = C1 + C2, a strong FYP) and present Linux driver + fine-tuning as future work.

## 14. Evaluation Methodology (this is what makes it research)
### 14.1 AXON-Bench
A set of multi-step device tasks with machine-checkable gold post-conditions, in tiers:
- Core (10 tasks): send a message, call a contact, set an alarm for a time, navigate to a place, toggle a setting, add a calendar event, etc.
- Robustness (variants): same tasks under perturbation — different starting screen, an intervening notification, a slightly different app version/layout.
- Long-horizon (5 tasks): 6+ step compositions (e.g. "find X in messages, then set a reminder about it").
Each task ships a spec: initial condition, goal, and a deterministic success oracle (final + intermediate post-conditions).
### 14.2 Metrics
Metric
Definition
Task Success Rate (TSR)
% tasks whose success oracle passes
Step Efficiency
actual steps ÷ optimal steps
LLM Calls / task
mean model invocations (drops to ~0 on replay — the C1 headline)
Recovery Rate
% injected single-step failures auto-healed (C2)
Latency
wall-clock per task (cold vs replay)
Valid-Action Rate
% syntactically/enumeratively valid actions (should be ~100% with grammar)
Thermal ceiling
tasks complet* before throttling on a mid-range device

### 14.3 Ablation matrix (the money table)
Run each config across the benchmark:
Config
Model
Grammar?
Verifier?
Skill replay?
Expectation
A (naive small)
3B
✗
✗
✗
low TSR, low valid-action
B (+grammar)
3B
✓
✗
✗
valid-action ≈100%, TSR up
C (+verifier)
3B
✓
✓
✗
TSR up, recovery > 0
D (full AXON)
3B
✓
✓
✓
best TSR, LLM-calls collapse on repeat
E (naive larger)
7B
✗
✗
✗
baseline to beat with D

The headline claim to prove: Config D (constrained + verified + skill-compiled 3B) meets or beats Config E (naive 7B) on Task Success while using a fraction of the compute — and on repeated tasks approaches zero LLM calls. That single chart wins the room.
### 14.4 Baselines to cite/compare
Where feasible, qualitatively compare against a VLM-screenshot agent (AutoGLM-class) and a tool-runner (Mythara-class) on the same tasks to situate AXON's reliability/efficiency claims.

## 15. (Reserved)

## 16. Security, Safety & Ethics (non-negotiable)
Context you must acknowledge in the thesis: the exact substrate AXON uses — AccessibilityService — is the same one abused by a documented ~$145M stalkerware industry ("a single API granting god-mode over an Android device"). Building responsibly is part of the contribution, and a panel will ask.
Mandatory guardrails (the coding agent must enforce these):
- Visible, user-initiated, foreground operation only. The agent runs as a foreground service with a persistent notification. No covert/background operation. No hiding the service.
- No screen exfiltration. UI contents never leave the device. This is a core selling point and an ethical requirement — enforce it architecturally (no network capability granted to perception/planner paths).
- Never read sensitive credential fields. Explicitly skip/ignore OTP and authenticator nodes and password fields in perception. (Note: isAccessibilityDataSensitive exists since Android 14 but is inconsistently applied by apps — so AXON must itself refuse to capture these, not rely on the app flag.)
- Per-skill, revocable consent via the capability sandbox (§7.10). No capability runs unless declared and granted.
- No autonomous account login / credential harvesting on the user's behalf.
- Destructive/irreversible actions require explicit confirmation (sending money, deleting data, posting publicly).
- Audit log of every action the agent takes, viewable by the user.
Ethics framing for the report: assistive-technology positioning (this is literally the accessibility API's intended purpose — helping people operate their device), privacy-by-design (offline), and explicit misuse analysis with the guardrails above as mitigations. If your university has an ethics/IRB process for any user testing, complete it before Phase 3 user trials.

## 17. Risk Register
Risk
Likelihood
Impact
Mitigation
Small model too weak for multi-step planning
Med
High
Grammar + precondition-gate + verifier carry reliability, not the model; two-call reason/extract for hard steps; skill replay removes model from repeated tasks
a11y tree empty (canvas/game/DRM UIs)
Med
Med
Screenshot + VLM fallback path (Gemma 3n is multimodal); scope target apps to a11y-friendly ones for the benchmark
Thermal throttling / battery drain in long tasks
High
Med
mmap, Q4_K_M, adaptive thermal throttle from day one, router model for cheap steps
Device/app fragmentation breaks skills
High
Med
Skills are per-device-family; assertion-driven fallback + re-compile on divergence (this is a feature, framed as robustness)
Shizuku setup friction for users
Med
Low
Core tasks work via AccessibilityService alone; Shizuku only for privileged extras; clear setup flow
Scope creep (trying to ship all 5 contributions + Linux)
High
High
Phase gating (§13); de-risk rule — Phases 0–5 = complete FYP
"This already exists" (Mythara etc.) challenge
High
Med
§3 positioning + the ablation table: AXON is the reliability+learning layer none of them have, and it's measured
Latency makes demo feel sluggish
Med
Med
Lead the live demo with a replayed compiled skill (near-instant) to show the "gets faster with use" payoff

## 18. Hardware & Dev Environment
- Primary test device: an upper-mid/flagship Android with ≥8 GB RAM (needed for 3B–4B Q4). Snapdragon 8-Elite-class with 12 GB comfortably holds a Q4 4B; 8 GB is the practical floor for 3B–4B; Gemma 3 1B runs on 4 GB.
- Secondary device: a genuine mid-range 6 GB phone — run 1.7B-class + Gemma 3n to prove the low-resource thesis. Don't only demo on a flagship; the whole point is cheap phones.
- Dev machine: Android Studio + NDK + CMake; ≥16 GB RAM to build llama.cpp .so and iterate.
- Build flags (verified): arm64-v8a ABI, -DANDROID_PLATFORM=android-26, -DLLAMA_VULKAN=ON, -DBUILD_SHARED_LIBS=ON → libllama.so into jniLibs. No special Android permission is required for inference itself.

## 19. How to Build AXON with an AI Coding Agent (operational)
Session template (repeat per phase):
1. "Read PROJECT_AXON_FYP_SPEC.md in full. We are building PHASE <N> ONLY."2. "Restate the interface contracts (§9) and schemas (§10) you will implement.    Do not deviate from the enums or the driver seam."3. "Propose the file list you will create/modify under §12's structure. Wait for my ok."4. "Implement. Then write the Phase <N> acceptance test from §13 and run it."5. "Report results against the acceptance criteria. Do not start Phase <N+1>."
Guardrails to give the agent every session:
- Planner is the only LLM caller in the loop; Executor and Verifier contain no LLM calls.
- Actions must be grammar-constrained (§10.6); never emit free-text actions.
- Enforce the §16 safety guardrails; never implement the §6.3 excluded capabilities.
- Use mmap model loading and Q4_K_M; pin latest stable dependency versions at build time (don't copy versions from this doc).
Recommended build order: Phase 0 → 1 → 2 → 3 (Semester 1 demo) → 4 → 5 (core contributions locked) → 6 → 7 (evaluation) → 8. If behind, stop at 5 and move the rest to future work.

## 20. Appendix
### 20.1 Glossary
- GBNF — GGML Backus-Naur Form; llama.cpp's grammar format for constraining output token-by-token.
- Constrained decoding — masking non-conforming tokens at each sampling step so invalid output is impossible.
- AccessibilityService — Android API exposing a structured UI tree and gesture dispatch; AXON's perception+action substrate.
- Shizuku — grants ADB-level privileges to apps without root or a PC.
- Trace — the recorded sequence of (action, pre-check, post-check, timing) for one task run.
- Skill — a compiled, parameterised, deterministic replay of a verified trace with per-step assertions.
- Self-healing — deterministic detection of a failed step (tree diff) + rollback + re-plan.
- Q4_K_M — a 4-bit quantization format balancing quality and memory for mobile.
### 20.2 Key facts this spec relies on (for your own re-verification before defense)
- On-device model landscape 2026: Gemma 3n (Per-Layer Embeddings, selective activation, 140+ languages, multimodal), Phi-4 Mini 3.8B, Qwen 3.5 small family, SmolLM3-3B, Gemma 3 1B/4B; Q4_K_M footprints and RAM floors as in §11/§18.
- Constrained decoding: llama.cpp GBNF (subset of JSON-Schema Draft 7; PCRE shorthands break the converter); grammar not injected into prompt (except tool-calling templates); XGrammar = SOTA throughput; grammar ≠ guaranteed completion on token exhaustion.
- Android control: AccessibilityService UI tree + gestures; Shizuku ADB-priv no-root; screenshot-analyze-operate VLM loop as the alternative paradigm; isAccessibilityDataSensitive since Android 14 (inconsistently applied).
- Inference wiring: llama.cpp on Android via JNI (llama.android) or KMP (Llamatik, Maven Central); mmap loading, Vulkan accel, arm64-v8a; ExecuTorch 1.0 GA (Oct 2025) as NPU-delegation alternative.
- Existing overlapping projects to differentiate from: Mythara, Operit AI, Open-AutoGLM/Ruto-GLM, Roubao, android-shizuku/rish MCP bridges (§3).
Re-run these checks close to submission — the on-device model field moves quarterly.

End of Master Specification. Build in phases. Evaluate rigorously. Lead the demo with a replayed skill.
