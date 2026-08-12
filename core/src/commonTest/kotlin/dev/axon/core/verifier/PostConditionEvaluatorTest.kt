package dev.axon.core.verifier

import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C2's decision procedure, pinned down on the JVM with no device.
 *
 * These tests are the evidence for the claim that verification is
 * *deterministic*. Every case below is a screen state and a verdict; nothing
 * here consults a model, and the same inputs always produce the same answer.
 * That property is the whole of the contribution — a reviewer should be able to
 * run this suite and see that AXON's notion of "did it work?" contains no
 * judgement at all.
 */
class PostConditionEvaluatorTest {

    private fun tree(
        pkg: String = "com.whatsapp",
        title: String? = "Ammi",
        vararg nodes: UiNode,
    ) = UiTree(pkg, title, nodes.toList(), capturedAtMs = 0)

    private fun node(index: Int, text: String? = null, desc: String? = null, id: String? = null) =
        UiNode(index = index, role = "text", text = text, contentDescription = desc, viewId = id)

    // -----------------------------------------------------------------

    @Test
    fun `app_foreground compares the package`() {
        val t = tree(pkg = "com.whatsapp")
        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.APP_FOREGROUND, "com.whatsapp"), t,
            ),
        )
        assertFalse(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.APP_FOREGROUND, "com.android.settings"), t,
            ),
        )
    }

    @Test
    fun `node_present matches text or content description`() {
        val t = tree(nodes = arrayOf(node(0, text = "Message sent"), node(1, desc = "Attach")))

        for (value in listOf("Message sent", "Attach", "sent")) {
            assertTrue(
                PostConditionEvaluator.evaluate(
                    PostCondition(PostConditionType.NODE_PRESENT, value), t,
                ),
                "expected '$value' to be found",
            )
        }
    }

    @Test
    fun `node_present uses containment, not equality`() {
        // Post-conditions are written by a small model against a screen it saw
        // only in summary. Demanding equality would fail on "Sent" vs "Sent ✓" —
        // a distinction with no task meaning and every chance to break
        // verification.
        val t = tree(nodes = arrayOf(node(0, text = "Sent ✓ 14:32")))
        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.NODE_PRESENT, "Sent"), t,
            ),
        )
    }

    @Test
    fun `node_absent is the mirror of node_present`() {
        // The workhorse for "the dialog closed", "the spinner finished" — cases
        // where success is an absence and a screenshot agent has nothing to look
        // at.
        val t = tree(nodes = arrayOf(node(0, text = "Inbox")))

        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.NODE_ABSENT, "Loading"), t,
            ),
        )
        assertFalse(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.NODE_ABSENT, "Inbox"), t,
            ),
        )
    }

    @Test
    fun `view ids are not matched`() {
        // Matching a resource name would let an assertion pass on something the
        // user cannot see, which defeats the point of asserting on observable
        // consequences.
        val t = tree(nodes = arrayOf(node(0, id = "com.whatsapp:id/send_button")))
        assertFalse(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.NODE_PRESENT, "send_button"), t,
            ),
        )
    }

    @Test
    fun `screen_title compares the title`() {
        val t = tree(title = "Alarm")
        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.SCREEN_TITLE, "Alarm"), t,
            ),
        )
        assertFalse(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.SCREEN_TITLE, "Timer"), t,
            ),
        )
    }

    // -----------------------------------------------------------------
    // Untrusted patterns
    // -----------------------------------------------------------------

    @Test
    fun `text_matches applies a valid regex`() {
        val t = tree(nodes = arrayOf(node(0, text = "delivered 14:32")))
        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.TEXT_MATCHES, "delivered|sent"), t,
            ),
        )
    }

    @Test
    fun `a malformed regex degrades to literal containment instead of throwing`() {
        // The pattern is model-authored. A 1B model writing "(unclosed" must not
        // be able to end a run — and, more subtly, must not be able to make a
        // step spuriously FAIL, because a false verification failure burns the
        // heal budget and can escalate a working task to the user for nothing.
        val t = tree(nodes = arrayOf(node(0, text = "an (unclosed thing")))

        assertTrue(TextMatching.isLiteralFallback("(unclosed"), "expected this to fail compilation")
        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.TEXT_MATCHES, "(unclosed"), t,
            ),
        )
    }

    @Test
    fun `a malformed regex still reports false when the literal is absent`() {
        // The fallback must not become a way to pass by accident.
        val t = tree(nodes = arrayOf(node(0, text = "something else entirely")))
        assertFalse(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.TEXT_MATCHES, "(unclosed"), t,
            ),
        )
    }

    @Test
    fun `pcre shorthands that models reach for still work`() {
        // §10.6 warns these break parts of the GBNF toolchain, and models emit
        // them constantly. Here they are evaluated in Kotlin, where they are
        // fine — worth a test so the distinction is documented rather than
        // assumed.
        val t = tree(nodes = arrayOf(node(0, text = "Code 481920 sent")))
        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.TEXT_MATCHES, "\\d{6}"), t,
            ),
        )
    }

    @Test
    fun `an empty tree satisfies absence and fails presence`() {
        // §17's canvas/DRM case. Both verdicts must be well-defined, because the
        // planner's escape route from an unreadable screen depends on being able
        // to assert something about it.
        val empty = UiTree.empty("com.example.game", capturedAtMs = 0)

        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.NODE_ABSENT, "anything"), empty,
            ),
        )
        assertFalse(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.NODE_PRESENT, "anything"), empty,
            ),
        )
        assertTrue(
            PostConditionEvaluator.evaluate(
                PostCondition(PostConditionType.APP_FOREGROUND, "com.example.game"), empty,
            ),
        )
    }

    @Test
    fun `evaluation is deterministic`() {
        // The property the whole contribution rests on: no model, no clock, no
        // randomness. Same screen, same claim, same verdict, every time.
        val t = tree(nodes = arrayOf(node(0, text = "Message sent")))
        val condition = PostCondition(PostConditionType.NODE_PRESENT, "sent")

        val verdicts = List(50) { PostConditionEvaluator.evaluate(condition, t) }
        assertTrue(verdicts.all { it }, "verdict varied across identical inputs")
    }
}
