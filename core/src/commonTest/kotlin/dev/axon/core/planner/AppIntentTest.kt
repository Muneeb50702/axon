package dev.axon.core.planner

import dev.axon.core.inference.ScreenGrammar
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * App-launch intent recognition and the single-action grammar (E21).
 *
 * The fix for E18b, where a well-formed, screen-grounded, gate-approved action
 * opened the phone dialer instead of WhatsApp. "Open X" is one of the few goals
 * whose correct action is determinable without the model's judgement, so the
 * judgement is removed rather than improved.
 */
class AppIntentTest {

    @Test
    fun `plain launch requests are recognised`() {
        for (goal in listOf("open whatsapp", "launch whatsapp", "start whatsapp", "go to whatsapp")) {
            assertEquals("whatsapp", AppIntent.appName(goal), goal)
        }
    }

    @Test
    fun `roman-urdu launch requests are recognised`() {
        // §2.1 names South Asian users; an English-only matcher would fail them.
        assertEquals("whatsapp", AppIntent.appName("kholo whatsapp"))
    }

    @Test
    fun `trailing app and punctuation are ignored`() {
        assertEquals("whatsapp", AppIntent.appName("open whatsapp app"))
        assertEquals("whatsapp", AppIntent.appName("Open WhatsApp."))
    }

    @Test
    fun `a multi-step goal is NOT a launch intent`() {
        // The important negative case. Narrowing the grammar for a whole task
        // would leave the agent unable to do anything after the launch, and it
        // would treat the task as finished at step one.
        for (goal in listOf(
            "open whatsapp and send a message to ammi",
            "open whatsapp then message baba",
            "open settings to turn on wifi",
        )) {
            assertNull(AppIntent.appName(goal), goal)
        }
    }

    @Test
    fun `non-launch goals are not recognised`() {
        for (goal in listOf("send a message to ammi", "set an alarm for 6:30", "call baba")) {
            assertNull(AppIntent.appName(goal), goal)
        }
    }

    @Test
    fun `the single-action grammar admits exactly one action`() {
        val g = ScreenGrammar.forAppLaunch("com.whatsapp")

        assertTrue("com.whatsapp" in g.source)
        assertTrue("launch_app" in g.source)
        // One production, one path. Nothing else is reachable — not a tap, not a
        // different package. This is the strongest form the constraint takes
        // anywhere in AXON, and it is only safe because the determination was
        // made outside the model by a package lookup.
        assertEquals(1, g.source.lines().count { it.contains("::=") })
        assertTrue("\\\"tap\\\"" !in g.source, "no other action should be reachable")
    }

    @Test
    fun `the resolver defaults to answering nothing`() = runTest {
        // Opt-in by construction, so the ablation can measure what narrowing buys
        // and a missing resolver degrades to ordinary planning rather than to a
        // grammar with no legal action in it.
        assertNull(AppResolver.NONE.resolve("whatsapp"))
    }
}
