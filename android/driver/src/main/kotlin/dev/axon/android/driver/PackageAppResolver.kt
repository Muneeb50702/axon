package dev.axon.android.driver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.axon.core.planner.AppResolver

/**
 * Resolves a human app name to an installed package (E21).
 *
 * The Android half of [AppResolver]. Answers from the launcher's own list, so
 * "whatsapp" resolves the way it does for a person looking at their home screen.
 *
 * ## Why matching is deliberately strict
 *
 * A wrong answer here is worse than no answer. When this resolves, the planner's
 * grammar collapses to a single legal action — launching that package — and the
 * model has no way to disagree. Resolving "whats" to a wallpaper app would make
 * the wrong app *the only reachable outcome*.
 *
 * So matching goes exact → prefix → contains, and stops at the first tier that
 * yields exactly one candidate. A tier with several matches is ambiguous and
 * returns null, which sends the planner back to ordinary screen-grounded
 * planning: slower, but the model can still be corrected by the gate and the
 * verifier.
 */
public class PackageAppResolver(context: Context) : AppResolver {

    private val appContext = context.applicationContext

    private val launchable: List<Pair<String, String>> by lazy {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.lowercase()?.trim() ?: return@mapNotNull null
            label to pkg
        }.distinctBy { it.second }
    }

    override suspend fun resolve(name: String): String? {
        val query = name.lowercase().trim()
        if (query.isEmpty()) return null

        // Exact first. "phone" must not prefix-match "phone manager".
        launchable.filter { it.first == query }
            .let { if (it.size == 1) return it.single().second }

        launchable.filter { it.first.startsWith(query) }
            .let { if (it.size == 1) return it.single().second }

        launchable.filter { query in it.first }
            .let { if (it.size == 1) return it.single().second }

        // Ambiguous, or nothing. Either way the planner falls back to normal
        // screen-grounded planning rather than being handed a grammar whose one
        // legal action might be wrong.
        return null
    }

    /** Installed launchable apps, for diagnostics and the permissions screen. */
    public fun installedApps(): List<Pair<String, String>> = launchable.sortedBy { it.first }
}
