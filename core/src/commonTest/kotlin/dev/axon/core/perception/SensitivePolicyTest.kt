package dev.axon.core.perception

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The §16 credential-refusal corpus.
 *
 * This is the evidence behind AXON's central ethical claim. §16 states that a
 * panel will ask about building on the substrate of a documented stalkerware
 * industry, and the answer needs to be something they can run, not a paragraph
 * in a report. Every case below is a field a real app actually ships.
 */
class SensitivePolicyTest {

    @Test
    fun `platform password flag is always honoured`() {
        assertTrue(
            SensitivePolicy.isSensitive(
                className = "android.widget.EditText",
                isPassword = true,
            ),
        )
    }

    @Test
    fun `app sensitivity flag is honoured when set`() {
        // Android 14+ isAccessibilityDataSensitive. Trusted when true; its
        // absence proves nothing, which is why every other rule still applies.
        assertTrue(SensitivePolicy.isSensitive(appFlaggedSensitive = true))
    }

    @Test
    fun `password fields are refused without the platform flag`() {
        // The case §16 calls out: apps that never set isPassword or
        // isAccessibilityDataSensitive. AXON must refuse these on its own.
        val cases = listOf(
            "com.bank.app:id/password_field",
            "com.bank.app:id/etPasswd",
            "com.wallet:id/pin_entry",
            "com.app:id/passphrase",
        )
        for (viewId in cases) {
            assertTrue(
                SensitivePolicy.isSensitive(className = "android.widget.EditText", viewId = viewId),
                "should refuse $viewId",
            )
        }
    }

    @Test
    fun `one-time codes and authenticator fields are refused`() {
        val cases = listOf(
            "com.bank:id/otp_input",
            "com.app:id/verification_code",
            "com.app:id/totp",
            "com.app:id/auth_code",
            "com.app:id/2fa_entry",
        )
        for (viewId in cases) {
            assertTrue(
                SensitivePolicy.isSensitive(className = "android.widget.EditText", viewId = viewId),
                "should refuse $viewId",
            )
        }
    }

    @Test
    fun `payment instrument fields are refused`() {
        for (viewId in listOf("com.shop:id/cvv", "com.shop:id/card_number", "com.bank:id/iban")) {
            assertTrue(
                SensitivePolicy.isSensitive(className = "android.widget.EditText", viewId = viewId),
                "should refuse $viewId",
            )
        }
    }

    @Test
    fun `crypto recovery phrases are refused`() {
        for (viewId in listOf("com.wallet:id/seed_phrase", "com.wallet:id/mnemonic")) {
            assertTrue(
                SensitivePolicy.isSensitive(className = "android.widget.EditText", viewId = viewId),
                "should refuse $viewId",
            )
        }
    }

    @Test
    fun `hint text alone is enough to refuse`() {
        // Many apps give the field no id worth reading and only a hint.
        assertTrue(
            SensitivePolicy.isSensitive(
                className = "android.widget.EditText",
                hintText = "Enter your OTP",
            ),
        )
    }

    @Test
    fun `content description alone is enough to refuse`() {
        assertTrue(
            SensitivePolicy.isSensitive(
                className = "androidx.compose.ui.platform.ComposeView",
                contentDescription = "Password",
                hintText = null,
            ),
        )
    }

    @Test
    fun `urdu and roman-urdu credential terms are refused`() {
        // §2.1 names South Asian users as the target population. An
        // English-only filter would fail precisely the users AXON claims to
        // serve, which makes this a correctness case, not a localisation one.
        for (hint in listOf("raaz lafz", "khufia code")) {
            assertTrue(
                SensitivePolicy.isSensitive(className = "android.widget.EditText", hintText = hint),
                "should refuse hint '$hint'",
            )
        }
    }

    @Test
    fun `ordinary fields are not refused`() {
        // The false-positive side. Over-refusal is the safer error but not a
        // free one — an agent that cannot see a message box cannot send a
        // message, which is task one of the benchmark.
        val benign = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp:id/search_input",
            "com.google.android.deskclock:id/label",
            "com.android.settings:id/switch_widget",
        )
        for (viewId in benign) {
            assertFalse(
                SensitivePolicy.isSensitive(className = "android.widget.EditText", viewId = viewId),
                "should NOT refuse $viewId",
            )
        }
    }

    @Test
    fun `compose and cross-platform fields are refused despite unfamiliar classes`() {
        // The gap that made the class gate an exclusion list rather than an
        // inclusion list. Compose, Flutter and React Native report class names
        // this module has never heard of; requiring a recognised text-entry
        // class would have silently passed the majority of modern UIs.
        val cases = listOf(
            "androidx.compose.ui.platform.ComposeView",
            "io.flutter.embedding.android.FlutterView",
            "com.facebook.react.views.textinput.ReactEditText",
        )
        for (className in cases) {
            assertTrue(
                SensitivePolicy.isSensitive(className = className, contentDescription = "Password"),
                "should refuse a credential field on $className",
            )
        }
    }

    @Test
    fun `buttons mentioning credentials are not refused`() {
        // "Forgot password?" is a navigation affordance, not a credential field.
        // Hiding it would break an ordinary flow while protecting nothing —
        // there is no secret in a Button.
        assertFalse(
            SensitivePolicy.isSensitive(
                className = "android.widget.Button",
                contentDescription = "Forgot password?",
            ),
        )
        assertFalse(
            SensitivePolicy.isSensitive(
                className = "android.widget.ImageButton",
                contentDescription = "Show password",
            ),
        )
    }

    @Test
    fun `a label displaying a one-time code is refused`() {
        // §16 says to skip "OTP and authenticator nodes" — nodes, not only input
        // fields. A TextView reading "Your code is 481920" is an OTP in plain
        // text, and reading it is exactly the harvesting behaviour the guardrail
        // exists to prevent. This is why TextView is absent from the
        // non-credential class list.
        assertTrue(
            SensitivePolicy.isSensitive(
                className = "android.widget.TextView",
                viewId = "com.bank:id/otp_display",
            ),
        )
    }

    @Test
    fun `a node with no metadata is not refused`() {
        assertFalse(SensitivePolicy.isSensitive(className = "android.widget.EditText"))
    }
}
