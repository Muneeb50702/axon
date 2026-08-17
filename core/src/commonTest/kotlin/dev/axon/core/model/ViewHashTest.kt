package dev.axon.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The repetition guard's key must survive a screen that has not meaningfully
 * changed — **E32**.
 *
 * `UiTree.contentHash` already excludes timestamps and bounds, so a settling
 * animation does not read as a change. It still hashes **every node, in order**,
 * which means a blinking cursor, a keyboard row, a spinner or a toast moves it.
 *
 * `RepetitionGuard` keyed on that hash, so on a real app screen the guard's key
 * drifted and a known-failing action was re-dispatched. Observed on device
 * during E31's run: the planner proposed the same tap on CamScanner's search
 * screen twice, the first failed, and the second went through anyway.
 *
 * This is E21c's failure shape — a structural defence that passes every unit
 * test and stops working on hardware — so the fix gets its own tests rather than
 * riding on the runtime's.
 */
class ViewHashTest {

    private fun node(
        index: Int,
        label: String?,
        role: String = "button",
        clickable: Boolean = true,
    ) = UiNode(
        index = index,
        role = role,
        text = label,
        contentDescription = label,
        bounds = Bounds(0, index * 60, 200, index * 60 + 50),
        clickable = clickable,
    )

    private fun stateOf(vararg nodes: UiNode) =
        CompactState.from(UiTree("com.example", "Search", nodes.toList(), capturedAtMs = 0))

    @Test
    fun `an unchanged screen keeps its key`() {
        val a = stateOf(node(0, "Send"), node(1, "Attach"))
        val b = stateOf(node(0, "Send"), node(1, "Attach"))
        assertEquals(a.viewHash, b.viewHash)
    }

    @Test
    fun `a non-interactable decoration does not move the key`() {
        // THE REGRESSION. A spinner, toast or static hint is pruned out of the
        // projection, so the planner never saw it — and the guard must not treat
        // its arrival as a new screen.
        val before = stateOf(node(0, "Send"), node(1, "Attach"))
        val after = stateOf(
            node(0, "Send"),
            node(1, "Attach"),
            node(2, "Loading…", role = "progress", clickable = false),
        )

        assertNotEquals(
            before.sourceHash, after.sourceHash,
            "precondition: the raw tree hash *does* move — that is the bug being fixed",
        )
        assertEquals(
            before.viewHash, after.viewHash,
            "a decoration the planner never saw must not reset the repetition guard",
        )
    }

    @Test
    fun `reordering the same elements does not move the key`() {
        // A transient element inserted mid-list shifts every index after it.
        // The set of available actions is unchanged, so retrying a failed one is
        // just as futile as before.
        val a = stateOf(node(0, "Send"), node(1, "Attach"))
        val b = stateOf(node(0, "Attach"), node(1, "Send"))
        assertEquals(a.viewHash, b.viewHash)
    }

    @Test
    fun `a genuinely different screen gets a different key`() {
        // The guard must not become so stable that it blocks an action which
        // would now work. Over-blocking is the opposite failure and no better.
        val a = stateOf(node(0, "Send"))
        val b = stateOf(node(0, "Delete"))
        assertNotEquals(a.viewHash, b.viewHash)

        val other = CompactState.from(
            UiTree("com.other", "Search", listOf(node(0, "Send")), capturedAtMs = 0),
        )
        assertNotEquals(a.viewHash, other.viewHash, "a different app is a different screen")
    }

    @Test
    fun `a new interactable element does move the key`() {
        // The planner now has an option it did not have before, so the screen it
        // is reasoning about is genuinely different.
        val before = stateOf(node(0, "Send"))
        val after = stateOf(node(0, "Send"), node(1, "Cancel"))
        assertNotEquals(before.viewHash, after.viewHash)
    }
}
