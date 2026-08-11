package dev.axon.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.axon.core.inference.ActionGrammar
import dev.axon.core.model.DeviceAction

/**
 * Phase 0 shell (spec §13): the app launches and reports what is actually wired.
 *
 * Deliberately a status screen rather than a mock of the finished UI. The Phase 0
 * acceptance criterion is "empty app launches", and a screen that reads live
 * values out of `:core` proves something a hardcoded mock cannot — that the
 * portable core is on the Android classpath and its contracts resolve at runtime,
 * not merely at compile time.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AxonApp() }
    }
}

@Composable
private fun AxonApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding)
                        .padding(24.dp),
                ) {
                    Text("AXON", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Agentic eXecution & Orchestration Nucleus",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))

                    PhaseCard(
                        phase = "Phase 0 — Scaffolding",
                        status = "complete",
                        done = true,
                        detail = "KMP module graph, §9 interface contracts, §10 schemas, " +
                            "§10.6 action grammar. Core carries no Android dependency.",
                    )
                    Spacer(Modifier.height(12.dp))
                    PhaseCard(
                        phase = "Phase 1 — Inference + constrained output",
                        status = "next",
                        done = false,
                        detail = "llama.cpp JNI, mmap model load, GBNF sampler, thermal telemetry.",
                    )

                    Spacer(Modifier.height(24.dp))
                    Text("Live from :core", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    // Read at runtime from the shared module — this is the part
                    // that makes the screen evidence rather than decoration.
                    Fact("action types", DeviceAction.ACTION_TYPES.size.toString())
                    Fact("grammar productions", ActionGrammar.SOURCE.countProductions().toString())
                    Fact("grammar literals", ActionGrammar.literals().size.toString())

                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Not yet connected: accessibility service, model weights, gateway. " +
                            "This build cannot operate your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseCard(phase: String, status: String, done: Boolean, detail: String) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(phase, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (done) Color(0xFF7DD3FC) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** Count `name ::=` rules, ignoring comments and continuation lines. */
private fun String.countProductions(): Int =
    lineSequence().count { Regex("^\\s*[a-z][a-z0-9-]*\\s*::=").containsMatchIn(it) }

@Preview
@Composable
private fun AxonAppPreview() = AxonApp()
