package dev.axon.android.app

import android.content.Context
import dev.axon.android.driver.AccessibilityDriver
import dev.axon.android.driver.AxonAccessibilityService
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.CompactState
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.StepOutcome
import dev.axon.core.model.UiTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wires the Android driver to the portable executor for the Phase 2 demo.
 *
 * §13's Phase 2 acceptance criterion: *"agent can perform a single correct action
 * on a real app from a hand-written plan; impossible actions are rejected
 * pre-execution."*
 *
 * Both halves matter, and the second is the interesting one to watch. Seeing a
 * tap land is unremarkable — any automation library does that. Seeing a
 * perfectly well-formed action *refused before the device is touched*, because
 * the element it names is not on screen, is the property that makes a 1B planner
 * safe to put in the loop.
 *
 * This class is intentionally thin. It holds no policy: the gate lives in
 * `:core`, the verification lives in `:core`, and the platform specifics live in
 * `:android:driver`. What it adds is the observable state a UI needs.
 */
class AgentController(context: Context) {

    private val driver = AccessibilityDriver(context.applicationContext)
    private val executor = DefaultExecutor(driver, nowMs = System::currentTimeMillis)

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    /** Is AXON allowed to see the screen? Null is the normal starting state. */
    fun isServiceEnabled(): Boolean = AxonAccessibilityService.isConnected

    /**
     * Capture what AXON currently perceives.
     *
     * Shown to the user as the element list the planner would receive — which is
     * also the most honest way to explain what the accessibility permission
     * actually grants. A permission screen says "can view and control your
     * screen"; this shows exactly what that means, including the count of fields
     * refused under §16.
     */
    suspend fun perceive(): Result<Unit> = runCatching {
        val tree = driver.observe()
        _state.value = _state.value.copy(
            tree = tree,
            compact = CompactState.from(tree),
            error = null,
        )
    }.onFailure { e ->
        _state.value = _state.value.copy(error = e.message ?: "capture failed")
    }

    /**
     * Run one hand-written action through the full gate → act → verify cycle.
     *
     * The action is gated against the tree captured by [perceive], not a fresh
     * one, exactly as the planner's would be: the gate must judge the action
     * against the world the plan was made in, or an ordinary race becomes an
     * unexplainable rejection.
     */
    suspend fun runOnce(action: DeviceAction): Result<StepOutcome> = runCatching {
        val tree = _state.value.tree ?: driver.observe()
        executor.reset()
        val outcome = executor.run(action, tree)
        _state.value = _state.value.copy(lastOutcome = outcome, error = null)
        outcome
    }.onFailure { e ->
        _state.value = _state.value.copy(error = e.message ?: "execution failed")
    }

    val deviceFamily: String get() = driver.deviceFamily
}

/** Everything the Phase 2 screen renders. */
data class AgentUiState(
    val tree: UiTree? = null,
    val compact: CompactState? = null,
    val lastOutcome: StepOutcome? = null,
    val error: String? = null,
)
