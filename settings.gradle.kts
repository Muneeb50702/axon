// AXON — module graph. Mirrors spec §12 "Repository Structure".
//
// Layering rule (spec §7.1, §9.1) — enforced by this graph, not by convention:
//
//     :android:app ──▶ :android:driver ──▶ :core
//                 └──▶ :android:inference ─┘
//     :bench ──────────────────────────────▶ :core
//
// :core NEVER depends on anything Android. That is the portability seam (C4).
// If a change makes :core need an Android type, the build breaks — which is the
// point: the seam is compiler-enforced.

rootProject.name = "axon"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// The portable reliability core (§7.1 AGENT CORE, §9, §10).
include(":core")

// Android reference driver + app (§7.1 ANDROID DRIVER, §12 android/).
include(":android:driver")
include(":android:inference")
include(":android:app")

// AXON-Bench harness (§14). Pure JVM so the benchmark runs in CI without a phone.
include(":bench")
