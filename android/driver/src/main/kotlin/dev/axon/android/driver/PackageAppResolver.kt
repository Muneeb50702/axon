package dev.axon.android.driver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
        val found = pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.lowercase()?.trim() ?: return@mapNotNull null
            label to pkg
        }.distinctBy { it.second }

        // E21c. An empty list here is not "this phone has no apps" — it is
        // Android 11+ package-visibility filtering, and the manifest is missing
        // its <queries> declaration.
        //
        // Worth a loud log rather than a silent empty list, because every
        // downstream symptom is misleading: `resolve` returns null, which is a
        // legitimate answer meaning "ambiguous", so the planner falls back to
        // ordinary screen-grounded planning and behaves plausibly-badly. E21's
        // grammar collapse simply never fires, and nothing anywhere says why.
        // That is how a feature passes its unit tests and is inert on hardware.
        if (found.isEmpty()) {
            Log.e(
                TAG,
                "no launchable apps visible — package-visibility filtering is on and " +
                    "<queries> is missing from the manifest. E21 app-name resolution is " +
                    "DISABLED; every 'open X' goal will fall back to free planning.",
            )
        } else {
            Log.i(TAG, "E21c: ${found.size} launchable app(s) visible to the resolver")
        }
        found
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

    private companion object {
        const val TAG = "PackageAppResolver"
    }
}
