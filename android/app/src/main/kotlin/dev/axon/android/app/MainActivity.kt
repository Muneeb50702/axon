package dev.axon.android.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.VerifyResult
import dev.axon.core.planner.describeForPrompt
import dev.axon.core.storage.AuditEntry
import kotlinx.coroutines.launch

/**
 * Phase 2 demo surface (spec §13).
 *
 * > *Accept: agent can perform a single correct action on a real app from a
 * > hand-written plan; impossible actions are rejected pre-execution.*
 *
 * The screen is built around demonstrating the **second** half, because the
 * first is unremarkable — any automation library can tap a button. What
 * distinguishes AXON is that a well-formed action naming an element that is not
 * on screen is refused *before the device is touched*, and this UI makes that
 * refusal visible rather than a log line.
 *
 * Showing the perceived element list also does something the system permission
 * screen cannot: Android says AXON "can view and control your screen", which is
 * true and uninformative. This shows exactly what that means — every element
 * AXON can see, and the count of fields it refused to read under §16.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: AgentController
    private lateinit var agent: AxonAgent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AgentController(this)
        agent = AxonAgent(this)

        // Requested at launch, not at first use. Without it the gateway's
        // notification is suppressed and the agent would operate invisibly —
        // which the service now refuses to do (§16), so asking late would mean
        // the first task silently declining to start.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        enableEdgeToEdge()
        setContent { AxonApp(controller, agent) }
    }

    override fun onDestroy() {
        agent.close()
        super.onDestroy()
    }
}

@Composable
private fun AxonApp(controller: AgentController, agent: AxonAgent) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold { padding ->
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val state by controller.state.collectAsState()

                // Read what AXON already knows before the user does anything.
                // With skills in SQLite this is non-zero on a fresh launch, and
                // showing it at startup is what makes persistence visible
                // rather than merely true.
                LaunchedEffect(Unit) { agent.refreshLearned() }

                var targetText by remember { mutableStateOf("") }
                var goalText by remember { mutableStateOf("") }
                var serviceOn by remember { mutableStateOf(controller.isServiceEnabled()) }
                val agentState by agent.state.collectAsState()

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding)
                        .padding(20.dp),
                ) {
                    Text("AXON", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        controller.deviceFamily,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(20.dp))

                    // ---- permission gate ----
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Accessibility service", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (serviceOn) "connected" else "not enabled",
                                    color = if (serviceOn) Color(0xFF7DD3FC) else Color(0xFFFCA5A5),
                                )
                            }
                            if (!serviceOn) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "AXON cannot see the screen until you enable it. It cannot turn " +
                                        "itself on — only you can, and only by hand.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }) { Text("Open accessibility settings") }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { serviceOn = controller.isServiceEnabled() }) {
                                Text("Re-check")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ---- perception ----
                    Button(
                        onClick = { scope.launch { controller.perceive() } },
                        enabled = serviceOn,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Capture what AXON sees") }

                    state.compact?.let { compact ->
                        Spacer(Modifier.height(12.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(compact.foregroundPackage, fontWeight = FontWeight.SemiBold)
                                compact.screenTitle?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "${compact.elements.size} interactable elements",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (compact.sensitiveWithheld > 0) {
                                    // §16 made visible. A guardrail the user cannot
                                    // observe is a guardrail they have to take on faith.
                                    Text(
                                        "${compact.sensitiveWithheld} sensitive field(s) refused",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFDE047),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    compact.elements.joinToString("\n") { it.render() },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ---- the acceptance demo ----
                    Text("Run one action", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Type a label from above to tap it. Type something that is NOT there to " +
                            "watch the precondition gate refuse it before the device is touched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = { Text("element label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                controller.runOnce(
                                    DeviceAction.Tap(
                                        target = Target(TargetBy.TEXT, targetText),
                                        expect = PostCondition(
                                            PostConditionType.NODE_ABSENT,
                                            targetText,
                                        ),
                                    ),
                                )
                            }
                        },
                        enabled = serviceOn && targetText.isNotBlank() && state.compact != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Tap it") }

                    state.lastOutcome?.let { outcome ->
                        Spacer(Modifier.height(12.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                val headline = when {
                                    !outcome.preOk -> "REJECTED before the device was touched"
                                    outcome.postOk == true -> "action verified"
                                    else -> "acted, but the post-condition did not hold"
                                }
                                Text(
                                    headline,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        !outcome.preOk -> Color(0xFFFDE047)
                                        outcome.postOk == true -> Color(0xFF7DD3FC)
                                        else -> Color(0xFFFCA5A5)
                                    },
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    when (val r = outcome.actResult) {
                                        is dev.axon.core.model.ActResult.Failed -> r.reason
                                        is dev.axon.core.model.ActResult.Refused -> r.reason
                                        is dev.axon.core.model.ActResult.Dispatched -> r.detail
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                (outcome.verifyResult as? VerifyResult.Mismatch)?.let { m ->
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "expected ${m.expected}\nobserved ${m.observed}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${outcome.latencyMs} ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }

                    state.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(28.dp))

                    // ---- the full agent loop (§7.2) ----
                    Text("Run a task", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The whole loop: perceive → plan → gate → act → verify, repeating until " +
                            "the budget runs out. Roughly a minute per step on this device, which " +
                            "is exactly why compiled skills matter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(agentState.status, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { scope.launch { agent.load() } },
                                enabled = !agentState.running && agentState.modelId == null,
                            ) { Text("Load model") }

                            if (agentState.skillCount > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    // "remembered", not "learned this session".
                                    // Skills are in SQLite now (§11), so this
                                    // count is non-zero at launch — which is the
                                    // whole point, and the only part of C1′ a
                                    // user can see without a stopwatch.
                                    "${agentState.skillCount} skill(s) remembered · replay costs 0 model calls",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF7DD3FC),
                                )
                            }

                            agentState.lastResult?.let { r ->
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "${r.outcome}${agentState.lastPath?.let { " via $it" } ?: ""}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (r.outcome.name == "SUCCESS") Color(0xFF7DD3FC)
                                    else Color(0xFFFDE047),
                                )
                                Text(
                                    "${r.steps.size} steps · ${r.llmCalls} model calls · " +
                                        "${r.totalMs / 1000}s · ${r.healAttempts} heals",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.height(6.dp))
                                r.steps.forEachIndexed { i, step ->
                                    Text(
                                        "${i + 1}. ${if (step.committed) "ok " else if (!step.preOk) "gated" else "miss"} " +
                                            step.action.describeForPrompt(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (step.committed) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        label = { Text("what should AXON do?") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Routed through the foreground service, not run inline.
                    // Inline, the agent perceives AXON's own UI and dies when
                    // backgrounded (E18); through the gateway it perceives the
                    // app the user switches to, and the persistent notification
                    // keeps it alive and stoppable.
                    Button(
                        onClick = {
                            dev.axon.android.app.gateway.AxonGatewayService
                                .run(context, goalText)
                        },
                        enabled = serviceOn && goalText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Run in background") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Starts the agent as a foreground service, then switch to the app you " +
                            "want it to operate. Watch the notification; stop it from there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // ---- §16 audit log ---------------------------------
                    //
                    // The promise is "the user can see everything the agent
                    // did", and until this screen existed the data was queryable
                    // and shown to nobody. A log only the developer can read is
                    // not accountability.
                    //
                    // Loaded on demand rather than at launch: the table is
                    // unbounded by design, and a startup that reads all of it
                    // works on day one and not on day two hundred.
                    Spacer(Modifier.height(24.dp))
                    var audit by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
                    var auditShown by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                audit = agent.auditLog(limit = 50)
                                auditShown = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (auditShown) "Refresh activity log" else "What has AXON done?") }

                    if (auditShown) {
                        Spacer(Modifier.height(8.dp))
                        if (audit.isEmpty()) {
                            Text(
                                "Nothing yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        audit.forEach { entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                // Refusals are the entries most worth seeing, so
                                // they are marked rather than filtered out. An
                                // audit log of successes only is not an audit
                                // log — and a §16 refusal is precisely the thing
                                // a user would want evidence of.
                                Text(
                                    if (entry.wasPerformed) "✓" else "✕",
                                    color = if (entry.wasPerformed) Color(0xFF86EFAC) else Color(0xFFFCA5A5),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "${entry.actionType} · ${entry.goal}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    entry.failureReason?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Text(
                        "No network permission. Nothing AXON sees can leave this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
