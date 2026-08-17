package dev.axon.core.skills

import dev.axon.core.model.Goal

/**
 * The one question a caller must ask before paying for a model — **E24d**.
 *
 * ## Why this is a named function and not two lines at the call site
 *
 * It *was* two lines at the call site, and it was wrong twice.
 *
 * The app loads an 806 MB GGUF before running a task. E22d established that this
 * should be skipped when a compiled skill can serve the goal — a replay measured
 * 42.8 s wall clock and **zero model calls**, essentially all of it spent loading
 * a model nothing consulted. The fix asked [SkillStore.match] and shipped.
 *
 * Then E24 added a second reuse path. A compound goal — *"open whatsapp then go
 * to linkedin"* — never matches a single skill **by construction**; that is the
 * entire reason [SkillComposer] exists. So the gateway's shortcut answered
 * `false`, loaded the model, and the runtime then composed the task from two
 * compiled skills at zero model calls. Measured on device: **8.35 s end to end,
 * of which 4.36 s was the model load** — 52% of the wait, for a model that was
 * never called.
 *
 * The same bug, in the same place, one release apart. Not because either fix was
 * careless, but because the shortcut and the runtime encode the same decision in
 * two places, and only one of them was updated. Every layer was individually
 * correct the whole time: the store matched correctly, the composer composed
 * correctly, and the gateway's shortcut was correct *for the case it knew about*.
 *
 * ## What this file is actually for
 *
 * It puts that decision somewhere a test can reach it. The previous version lived
 * in `:android:app`, which has no test suite, so nothing could have caught either
 * regression except running the app and reading a timeline — which is how both
 * were in fact found.
 *
 * That is the E29 lesson restated: a mechanism whose correctness cannot be
 * checked where it lives will be wrong eventually, and its being wrong will be
 * invisible. The rule now has one home, and adding a third reuse path means
 * changing a function whose test says what it is for.
 */
public suspend fun SkillStore.canServeWithoutModel(goal: Goal): Boolean =
    // Ordered cheapest-first: `match` is an indexed lookup, `plan` splits the
    // utterance and matches every fragment. Both are far below the ~4 s this
    // exists to avoid, so the ordering is tidiness rather than optimisation.
    match(goal) != null || SkillComposer(this).plan(goal) != null
