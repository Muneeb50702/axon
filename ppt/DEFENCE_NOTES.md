# AXON — Proposal Defence Notes

**Room 1 · Slot 8 · 10:45–11:00 AM · Tuesday 18 August 2026**
Group CS-025 · BS CS Section J · Supervisor: Dr. Saleem Mustafa
Room Manager: Mr. Affan Ahmad

File to submit: `Room 1-8-Dr. Saleem Mustafa.pptx` — **to Dr. Saleem Mustafa by
1:00 PM, Monday 17 August**.

---

## ⚠ Fill these in before submitting

1. **SAP IDs** on slide 1 — three placeholders read `SU92-BSCSM-F23-___`.
   The schedule sheet did not list them for our group.
2. **Confirm the file name.** The instruction example was `Room 1-4-Dr. Abdul
   Rehman`, where `4` is the group's number *within the room*. Ours is slot **8**
   in Room 1, so the file is named `Room 1-8-Dr. Saleem Mustafa.pptx`. If your
   supervisor means the Group ID instead, rename to
   `Room 1-CS-025-Dr. Saleem Mustafa.pptx`. **Ask him — it takes one message.**
3. **Show the deck to Dr. Saleem Mustafa before Monday.** The department requires
   the presentation to be prepared in consultation with the supervisor.

---

## Timing — 15 minutes total, including Q&A

Budget **9–10 minutes of speaking** and leave 5 for questions. All three members
must speak or the group is not enrolled.

| slides | who | minutes |
|---|---|---|
| 1–4 · Title, Introduction, Background, Problem | **Muneeb** (leader) | 3.0 |
| 5–7 · Objectives, Literature, Methodology | **Nasar** | 3.0 |
| 8–10 · Block Diagram, Results, Timeline | **Danish** | 3.0 |

| 11 · References + close | **Muneeb** | 0.5 |

Practise once with a timer. Going over time is the most common way to lose marks.

---

## What makes this deck strong — lean on it

Most proposal defences present a *plan*. **You have a working system with
measured results, taken on a real phone driving a real app.** That is slide 9,
and it is your strongest 45 seconds.

Say this out loud, once, clearly:

> "This is a proposal defence, but we have already built and measured the core
> system. It runs on a forty-thousand-rupee phone, completely offline, it learns
> a task and then repeats it with no AI at all — and the numbers on this slide
> were measured on that device last week, not estimated."

---

## Slide-by-slide talking points

### 1 · Title — 15 s
Name, project, one line. Do not read the slide.

> "Project AXON — an offline AI agent that operates an Android phone, and learns
> to do repeated tasks without the AI at all."

### 2 · Introduction — 60 s
The hook is the first sentence. Deliver it slowly.

> "Every AI assistant on a phone today sends your screen to a cloud server. For
> two billion people on slow or expensive data, that assistant simply does not
> work. And for a banking or health screen, most people would not want it to."

Then the turn:

> "AXON runs the whole model on the phone. No internet — the app does not even
> have permission to use the internet."

### 3 · Background — 60 s
Three beats: why cloud, what changed, what is still missing.

> "Small models became good enough in 2025. But 'good enough to run' is not
> 'good enough to trust'. A one-billion-parameter model asked to operate a phone
> will invent a button that is not on the screen."

Land the thesis line — this is the sentence the panel should remember:

> **"Our thesis is that reliability is an architecture problem, not a model-size
> problem. We do not make the model smarter. We make the model's freedom
> smaller."**

### 4 · Problem Statement — 45 s
Problem, then impact. Do not rush the privacy point — it is the one a
non-technical panellist will connect with.

### 5 · Aims & Objectives — 60 s
Read the five objective *titles* only, not the sub-points. Emphasise that each
has a **number attached** — that is what makes them measurable.

### 6 · Literature Study — 60 s
This slide answers "hasn't this been done?" before it is asked. **Be honest
about SkillDroid** — it is the closest work and a panellist may know it.

> "The closest published work is SkillDroid, from April this year. It compiles
> skills the way we do — but it calls cloud GPT models. A cloud-scale model never
> has the problem we are solving, which is that a one-billion-parameter model
> produces malformed output. That is our gap."

Volunteering this makes you look like researchers. Being caught by it does the
opposite.

### 7 · Methodology — 60 s
Walk the four boxes left to right. One sentence each. The key framing:

> "Each defence catches a different class of error. The grammar catches wrong
> *shape*. The gate catches wrong *world* — naming something that is not there.
> The verifier catches wrong *outcome* — an action that looked right and did
> nothing."

### 8 · Block Diagram — 75 s
Trace one request with your finger:

> "Goal comes in. First we ask: have we done this before? If yes, we replay it —
> no AI at all, 17 milliseconds. If no, we take the slow path: perceive, plan,
> gate, act, verify. And when it succeeds, we record it, compile it into a skill,
> and the next time that same request is free."

Then point at the safety bar along the bottom and say one sentence about it.

### 9 · Progress & Early Results — 75 s ⭐
**Your best slide. Slow down.**

Lead with the live demo number, not the biggest one:

> "We asked it to open WhatsApp. The first time, it plans: 66 seconds, one AI
> call. The second time it replays what it learned: 2.3 seconds, and **zero AI
> calls**. That is on a real phone driving the real WhatsApp app — the 2.3
> seconds includes WhatsApp's own start-up time."

Then persistence, because it is the part a person can see:

> "And it remembers. We killed the app the way Android kills it, reopened it, and
> it still knew the task — in 39 milliseconds."

Then **two** honesty moves. These are worth more than any number.

> "One experiment we voided ourselves. We got a 100%-versus-0% result that looked
> excellent, then found our own baseline was unfair — the comparison prompt never
> asked for the right output format. The honest number is 46.2%."

> "And when we finally measured on the device, we found our strongest safeguard
> had never actually run. Android hides the list of installed apps from an app
> that does not request it, and we had deliberately not requested it, for privacy
> reasons. So the safeguard was switched off — silently — while passing every
> single unit test. Tests prove your code is correct. They do not prove it is
> reachable."

If you deliver only one sentence well in the whole presentation, make it that
last one. It is the difference between a student who built something and a
researcher who knows what their evidence does and does not support.

**If asked where 8,407× comes from** (it is in the report, not on the slide):
that is a separate experiment isolating the cost of *deciding*, against a
simulated screen. The 29× on the slide is the whole task on a real app. We lead
with the smaller number because it is the one that cannot be argued with.

### 10 · Timeline — 45 s
Point out that phases 0–5 are already shaded as complete.

> "Six of eight phases are done. The remaining work is evaluation and write-up."

### 11 · References — 15 s
"IEEE format, eight sources." Move on.

---

## Likely questions — and answers

**"How is this different from Google Assistant / Gemini?"**
> Those send your screen to a server and need internet. AXON has no internet
> permission at all — you can verify that from the app manifest. And it works on
> a phone that costs forty thousand rupees.

**"A 1B model is too small to be reliable. How can this work?"**
> That is exactly our research question, and the answer is that we do not rely on
> the model being reliable. The grammar makes malformed actions impossible to
> generate. The gate refuses actions that name things not on screen. The verifier
> checks the result without asking the model. Measured: 100% valid actions versus
> 46.2% without those.

**"Hasn't someone already done skill compilation?"** *(the dangerous one)*
> Yes — SkillDroid, April 2026. We cite it. They use cloud models, where replay
> saves a network round-trip. We are offline on a low-end phone where a planning
> step costs 60 seconds, so compilation is not an optimisation — it is what makes
> a multi-step task finish at all.

**"Is it safe? Accessibility APIs are used by spyware."**
> We know, and we designed against it. No internet permission, so screen contents
> physically cannot leave the phone. Password and OTP fields are never read — we
> refuse them before reading, with a published test suite. It runs as a visible
> foreground service with a persistent notification, and it refuses to run at all
> if that notification is blocked. Irreversible actions like placing a call need
> your explicit approval.

**"60 seconds per step is very slow."**
> On a cold run, yes — and that is precisely why skill compilation matters. The
> second time you ask for the same task it is 17 milliseconds. On a flagship this
> would look like a minor optimisation; on this hardware it is the difference
> between usable and unusable.

**"What is your novelty?"**
> Making a sub-2-billion-parameter, fully offline model reliable enough to drive
> a phone, and measuring where architecture can substitute for model size. Nobody
> has that number.

**"If I say 'launch WhatsApp' instead of 'open WhatsApp', does the learned skill still work?"**
> Not yet — and it fails safely. Matching is literal right now, so a different
> phrasing misses and falls back to the slow path: correct answer, wrong speed.
> We built it that way on purpose, because a *wrong* match would replay the wrong
> skill on a live phone — messaging the wrong person. We would rather be slow
> than wrong. The fix for phrasing is already half-built: our app-name resolver
> already treats "open", "launch", "start" and the Urdu "kholo" as the same
> intent, so we store the meaning rather than the words. Full paraphrase matching
> needs a second AI model for sentence similarity, which is a real memory cost on
> this hardware — that is in our limitations, not hidden.

**"How do you know it really replays without the AI?"**
> Three ways. The skill store counts every replay and every repair. The trace log
> records model calls per task and shows zero. And in our tests the replayer is
> handed a planner that fails the test loudly if it is ever called.

**"What is the weakest part of your system?"**
> The replay path. Our three safeguards — the grammar, the gate, the verifier —
> all protect the planning path. A compiled skill answers to none of them. We
> found that on the device: our compiler was dropping part of the recorded action,
> and a skill that recorded "press Home" would have replayed "press Back". It now
> refuses to act rather than guess, and every action type is tested end to end.
> We would not have found that without running on real hardware.

**"Have you tested with real users?"**
> Not yet — it is in the timeline. We will complete the ethics process before
> user trials.

**"What if the app updates and the skill breaks?"**
> Every replayed step carries an assertion. If the UI has changed, the skill stops
> at the step that broke and asks the AI to repair just that step — the rest still
> replays. We treat app drift as something to recover from, not something that
> breaks the feature.

---

## Practical

- **Dress formally.** No T-shirts, no jeans, no sneakers — the department was
  explicit and they do check.
- Arrive **before 10:45**; slots are 15 minutes back to back.
- Bring the deck on a **USB stick and email it to yourself**. Do not rely on one
  copy.
- **Optional but powerful:** have the app open on the phone. If a panellist asks
  whether it really works, showing the running app for ten seconds is worth more
  than any slide. Only do this if you have practised it — a failed live demo
  costs more than it gains.
- If asked something you do not know: *"We haven't measured that yet — it's in
  our evaluation phase."* Never invent a number.
