# Phase 1 — On-device inference and constrained generation

Viva preparation. What was built, why each decision was made, and the questions a
panel is likely to ask.

Spec reference: §7.3 (inference engine), §7.4 (planner/constrained decoding),
§9.3 (`InferenceEngine`), §10.6 (action grammar), §13 (Phase 1 acceptance),
§18 (build flags). Contribution: **C3**.

---

## 1. What Phase 1 had to prove

One thing: **that a small model running entirely on a cheap phone can be made
incapable of emitting a malformed action.** Not unlikely to — incapable.

That is the whole of C3, and it is the foundation the rest of AXON stands on. The
executor's precondition gate and the verifier both assume they are handed a
well-formed action; if the planner could emit `{"action": "click the send
button"}`, every downstream guarantee would be conditional on the model behaving.

§13's acceptance criterion states it as a number: *500 generations → 100%
schema-valid actions.*

---

## 2. How it works, end to end

```
CompactState (§10.2)                     ActionGrammar.SOURCE (§10.6)
        │                                            │
        ▼                                            │
PlannerPrompt.SYSTEM  +  PlannerPrompt.user(...)     │
        │  (natural language: screen, goal, menu)    │
        ▼                                            ▼
   chat template  ──────▶  llama.cpp  ──────▶  llama_sampler_init_grammar
                              │                      │
                              │        sampler chain:│
                              │   [grammar] → top_k → top_p → temp → dist
                              ▼
                    compact JSON  ──▶  AxonJson.strict  ──▶  DeviceAction
```

The division of labour in §7.4 is the thing to be able to state cleanly:

- **The prompt** carries all the *judgement* — which element, which verb, what
  should be true afterwards. It is natural language and it is where the model's
  intelligence is used.
- **The grammar** carries all the *structure*. It never appears in the prompt. It
  acts at the sampler, masking every token that would leave the grammar.

A grammar cannot make the model choose the right button. It can make every button
it chooses be expressed in a form the executor can act on. Those are different
guarantees and the thesis should never blur them.

---

## 3. Decisions, and the reasoning behind each

### 3.1 Our own JNI bridge instead of Llamatik (D1)

§11 suggests Llamatik. It exists and is maintained, but its only constrained path
is `generateJson(prompt, jsonSchema)` — **no way to pass raw GBNF**.

Three reasons that is disqualifying:

1. §7.4 and §20.2 require the *raw grammar* path specifically.
2. AXON's grammar is a discriminated union, which JSON Schema expresses as
   `oneOf` — the construct the schema→GBNF converter handles worst.
3. llama.cpp issue #22396: `--json-schema` fails to initialise samplers on
   `gemma3n`-architecture models, while `--grammar` works.

Routing C3 through a third party's schema translator would mean the mechanism the
thesis defends is not in the thesis's repository.

> **Likely question — "Isn't writing your own JNI just reinventing a wheel?"**
> The wheel does not turn the way the contribution needs. Llamatik's constrained
> path is a JSON-Schema converter; C3 is a claim about a hand-written grammar
> that the converter cannot express and that crashes on our model. The bridge is
> ~400 lines and it puts the mechanism under test inside the project.

### 3.2 Grammar at the head of the sampler chain

```
[grammar] → top_k → top_p → temp → dist
```

The grammar sets non-conforming logits to `-INF` **before** the stochastic
samplers see the distribution. Placed after top-k, it would be masking an
already-truncated candidate list, and if all *k* survivors were invalid there
would be nothing left to sample — the constraint would fail exactly when it
mattered most.

The cost is real and is reported rather than hidden: at head position the grammar
is evaluated against Gemma's full ~262k vocabulary on every token, and that is
**59% of decode time** on the target device. llama.cpp's own `common_sampler`
uses an optimistic scheme instead — sample first, check the one chosen token,
fall back to full masking only on rejection — which is equally correct and much
faster. Phase 3 adopts it.

> **Likely question — "Why did you choose the slow option?"**
> Correctness before speed, deliberately. C3 had to be demonstrated working
> before it was made fast, and the naive placement is the one that is obviously
> correct. Having measured the cost, the optimisation is now justified by a
> number rather than by intuition — and "constrained decoding costs X on this
> hardware" is itself a §14.2 result.

### 3.3 A discriminated-union grammar (D3)

§10.6's reference grammar is one object with optional fields, which permits:

```json
{"action":"tap","direction":"up", ...}       ← grammar-valid, meaningless
{"action":"launch_app","app":"WhatsApp"}     ← not a package name
```

AXON's grammar has one production per action type, each carrying exactly the
fields §10.1 allows, plus a `package-name` production for `launch_app`.

**The single best demo of what constrained decoding buys:** the sampler cannot
produce a capital letter in the `app` field. `"WhatsApp"` is not merely rejected
— there is no token path to it.

The same constraint exists at three layers, kept in 1:1 correspondence:

| layer | mechanism |
|---|---|
| Kotlin | sealed `DeviceAction` — illegal combinations do not compile |
| wire | `action` discriminator — unchanged from §10.1 |
| sampler | GBNF discriminated union — unreachable token paths |

`ActionGrammarTest` fails CI if they drift; `DeviceActionWireFormatTest` proves
the wire format still matches §10.1.

### 3.4 No optional whitespace in the grammar

§10.6 threads `ws ::= [ \t\n]*` between tokens, as `json.gbnf` does. That is
right for a **parser** and wrong for a **generator**: every permitted space is a
token the model may spend, and on-device those tokens are the budget.

Measured: Gemma 4 E2B used the freedom to pretty-print and hit its token cap
*mid-object*. Removing the slots cut a 1B generation from 43 to 38 tokens and
deleted that truncation mode.

---

## 4. Four bugs worth being able to talk about

These are good viva material — they show the work was actually run, not just
written.

### 4.1 The native build was `-O0` (D9)

AGP compiles debug variants with `CMAKE_BUILD_TYPE=Debug`. Harmless for JNI glue;
ruinous for ggml's quantised matmul kernels.

**Symptom:** one 32-token generation ran over ten minutes and was SIGKILLed.
Nothing logged "unoptimised".

**Why it matters beyond the fix:** the failure is invisible *and* plausible. A
budget phone being slow at inference is exactly what one expects, so every
latency figure in §14.2 would have been wrong by an order of magnitude and no
reviewer could have caught it from the results table.

### 4.2 A GBNF rule body ends at the newline

llama.cpp's parser terminates a rule at the line break unless the continuation is
inside an open `( )`. `json.gbnf` only *looks* multi-line — its continuations all
sit inside parens.

**Symptom:** the grammar was rejected on-device with the five-word message
"failed to parse grammar". The sampler was then simply never installed, so
generation ran **unconstrained with nothing failing** — a silent loss of C3.

**Fixes, both permanent:**
- `tools/gbnf-check` validates the exported grammar with llama.cpp's own parser,
  on the host, in CI. The grammar is *generated* from `ActionGrammar.SOURCE`, so
  what CI validates is what the phone runs.
- llama.cpp's `stderr` is pumped into logcat. The parser reports the real reason
  there (`expecting ::= at …`) and Android discards it by default.

### 4.3 The GGUF ships no chat template

`llama_model_chat_template` returned NULL, so prompts reached the model with no
turn markers. This does not error — it silently degrades quality, and every
measurement taken afterwards would have been of a misformatted prompt. Fixed with
a named built-in fallback (`gemma`).

### 4.4 Two llama.cpp APIs had moved

Written against `include/llama.h` as it is today rather than from memory:

- `llama_model_params::use_mmap` is **gone**, replaced by `enum llama_load_mode`
  whose `AUTO` default *disables* mmap on integrated GPUs — i.e. every phone.
  §7.3 requires mmap, so AXON sets `LLAMA_LOAD_MODE_MMAP` explicitly.
- `llama_sampler_sample()` already calls `llama_sampler_accept()` internally.
  An extra accept would advance the grammar automaton twice per token and
  corrupt every constrained generation after the first.

---

## 5. Results on the target device

TECNO Camon 20 · Helio G85 (2× Cortex-A75 @ 2.0 GHz + 6× A55) · Mali-G52 MC2 ·
7.9 GB RAM · Android 14 · CPU backend · 547-token prompt · grammar-constrained.

| model | file | prefill | decode | grammar | **total/step** |
|---|---|---|---|---|---|
| Gemma 3 1B Q4_K_M | 0.81 GB | 38.3 s (14.3 t/s) | 22.4 s (1.7 t/s) | 13.2 s | **60.7 s** |
| Gemma 4 E2B Q4_K_M | 3.11 GB | 123.0 s (4.6 t/s) | 34.3 s (1.4 t/s) | 17.0 s | **157.4 s** ✂ |

✂ hit the token cap mid-object.

Thread sweep (1B), showing why prefill and decode get different counts:

| threads | prefill | decode |
|---|---|---|
| 4 | 12.6 t/s | **2.0 t/s** |
| 8 | **15.6 t/s** | 1.5 t/s |

Prefill is compute-bound and parallelises across all cores; single-token decode is
memory-bound and finishes only when the slowest thread does, so the little cores
hold it back. AXON uses 8 for prefill, 4 for decode.

Sample output, valid and compact:

```json
{"action":"tap","target":{"by":"content_desc","value":"Ammi"},
 "expect":{"type":"app_foreground","value":"com.whatsapp"}}
```

---

## 6. The honest weaknesses

State these before a panel finds them.

**60 seconds per planning step is slow.** A six-step task is six minutes. Two
mitigations are identified and quantified, not hypothetical:

- *Prefill is 63% of a step*, and ~350 of the 547 prompt tokens are the invariant
  system prompt. KV-cache prefix reuse addresses roughly half the total cost.
  `PlannerPrompt` is already split into stable prefix and volatile suffix for
  precisely this.
- *Grammar sampling is 59% of decode.* The optimistic sampler above.

**This slowness is also the argument.** It is what makes contribution C1 matter
rather than being a nicety: a cold LLM-planned task costs a minute per step on a
$150 phone; the same task compiled to a skill replays deterministically with
**zero** model calls. §17 already advises leading the demo with a replayed
skill — on this hardware that stops being presentation tactics and becomes the
thesis. A flagship would have made skill compilation look like an optimisation.

**A grammar guarantees well-formed, never correct.** In the sample above the
model targeted `"Ammi"`, which on that screen is a non-interactive label and is
pruned from `CompactState` — a hallucinated target. That is exactly the failure
the executor's precondition gate (§7.5) exists to catch, *before* the device is
touched, and it is why AXON's reliability argument is architectural rather than a
claim about decoding alone. Phase 2 delivers that gate.

**The screen corpus is synthetic.** 24 hand-written approximations of real
Android screens. They measure whether the model emits structurally valid,
screen-grounded actions — which is what C3 claims and what does not need a live
device. Whether the chosen action actually accomplishes the task on a real phone
is a Phase 3 question, answered on hardware. The benefit of drawing that line is
that this half of the evaluation reproduces on any machine, without the handset.

---

## 7. Reproducing it

```bash
git submodule update --init --recursive
./tools/check-grammar.sh                    # validate grammar with llama.cpp's parser
./tools/fetch-model.sh --push               # fetch + push weights (no INTERNET permission, D7)
./gradlew :android:inference:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.axonSamples=500
```

Single-configuration measurement, no rebuild needed between runs:

```bash
adb shell am instrument -w \
  -e class dev.axon.android.inference.InferenceSweepTest \
  -e axonModel gemma-3-1b-it-Q4_K_M.gguf \
  -e axonThreads 4 -e axonThreadsBatch 8 -e axonCtx 2048 \
  dev.axon.android.inference.test/androidx.test.runner.AndroidJUnitRunner
```
