package dev.axon.core.skills

/**
 * Which handle the compiler rewrites a recorded selector to (E26), and the
 * trade each choice makes (measured by **E26b**).
 *
 * ## Why this is an enum and not a boolean
 *
 * E26 shipped one behaviour — take the sturdiest handle available — described in
 * its own comments as *"the most drift-resistant form"*, as though the ordering
 * `id > content_desc > text > coord` made the choice strictly better.
 *
 * E26b measured it against eight classes of real UI drift and it is **not**
 * strictly better. It is a trade, and the arms differ on *which* changes they
 * survive rather than on how many:
 *
 * | drift class | `NONE` (text) | `LABEL` (content_desc) | `STURDIEST` (id) |
 * |---|---|---|---|
 * | copy + a11y label rewritten | ✘ | ✘ | ✔ |
 * | copy rewritten, a11y label kept | ✘ | ✔ | ✔ |
 * | icon-only redesign | ✘ | ✔ | ✔ |
 * | a11y label removed | ✔ | ✘ | ✔ |
 * | **view id renamed** | ✔ | ✔ | **✘** |
 * | **view ids removed (Compose migration)** | ✔ | ✔ | **✘** |
 * | a11y label added | ✔ | ✔ | ✔ |
 * | reorder | ✔ | ✔ | ✔ |
 *
 * `LABEL` and `STURDIEST` survive the same *number* of classes. Choosing between
 * them is a bet on whether an app's copy changes more often than its resource
 * ids, and **nobody here has measured that** — see `DriftClass` in `:bench`.
 *
 * The default stays [STURDIEST]: it is what has been running, and E26b gives no
 * evidence for switching, only evidence that the choice was never neutral. A
 * change of default should follow the frequency study, not this file.
 */
public enum class SelectorPolicy {
    /**
     * Keep whatever the planner chose.
     *
     * In practice `text`, because the screen grammar names elements by label and
     * a label is `contentDescription ?: text`. Not a straw man — it is what
     * shipped before E26.
     */
    NONE,

    /**
     * Promote to `content_desc` where the node carried one, never to `id`.
     *
     * Survives everything that renames or removes resource ids, which is the
     * failure class [STURDIEST] cannot see coming.
     */
    LABEL,

    /**
     * Promote to the sturdiest handle observed, preferring `id`.
     *
     * The shipping behaviour. Immune to every copy change, including
     * localisation; fragile to exactly the changes that touch ids.
     */
    STURDIEST,
}
