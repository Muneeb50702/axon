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
}

android {
    namespace = "dev.axon.android.inference"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

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

    // externalNativeBuild is wired in Phase 1, alongside the llama.cpp submodule.
    // Left out here so Phase 0 builds without the native toolchain step.
}


dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
}
