// :android:driver — the reference DeviceDriver implementation (spec §9.1, §12).
//
// This is the only module allowed to know about AccessibilityService, Shizuku and
// gesture dispatch. Swapping it for a Linux AT-SPI2 module is the whole of a port
// (contribution C4) — nothing above the seam changes.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.axon.android.driver"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
