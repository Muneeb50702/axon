// :android:inference — llama.cpp wiring (spec §7.3, §9.3, §12).
//
// Phase 1 lands the JNI bridge here. Two decisions worth recording, both
// resolved on 2026-08-11 against the current state of the ecosystem:
//
//  1. **Own JNI, not Llamatik.** §11 suggests Llamatik (KMP, Maven Central) as
//     the binding. It exists and is maintained, but its public surface exposes
//     only `generateJson(prompt, jsonSchema)` — a JSON-Schema path, with no way
//     to pass a raw GBNF grammar. §7.4 and §20.2 require the *raw grammar* path
//     specifically, because the JSON-Schema→GBNF converter mishandles the
//     patterns AXON's grammar needs. Independently, llama.cpp issue #22396
//     reports `--json-schema` failing to initialise samplers on gemma3n-family
//     models — the exact architecture of the primary model — while `--grammar`
//     works. Taking the library would block contribution C3 and break on the
//     chosen model. A thin JNI layer over `llama_sampler_init_grammar` is a few
//     hundred lines and puts the mechanism the thesis defends inside the repo.
//
//  2. **Build flags** come from §18: arm64-v8a, android-26, Vulkan on, shared
//     libs, weights loaded with mmap so the OS pages the model under memory
//     pressure instead of the app being killed (§7.3).

plugins {
    alias(libs.plugins.android.library)
    // The Phase 1 acceptance harness serialises its results table to JSON so the
    // §14 numbers are a file that can be diffed and re-plotted, not logcat output
    // someone transcribed by hand.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.axon.android.inference"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // The Phase 1 acceptance harness (§13) is an instrumented test: it needs
        // real arm64 silicon and a real 3 GB model, neither of which exists in a
        // JVM unit test. Run with:
        //   ./gradlew :android:inference:connectedDebugAndroidTest
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // §18: the deliverable targets arm64-v8a. Shipping other ABIs would
            // multiply APK size for devices the project does not claim to serve.
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                // §18: android-26 baseline, arm64-v8a only.
                //
                // AXON_VULKAN defaults OFF per D8 — the Vulkan backend is a
                // measurement arm, not an assumption. Build the comparison with:
                //   ./gradlew :android:app:assembleDebug -PaxonVulkan=true
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-26",
                    "-DAXON_VULKAN=" + if (project.hasProperty("axonVulkan")) "ON" else "OFF",
                )
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}


dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
