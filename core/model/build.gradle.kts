// :core:model is deliberately a pure Kotlin/JVM module (NOT an Android library).
// It contains zero android.* imports so it can be unit-tested on the plain JVM
// (fast, no emulator/Robolectric needed) and so the MappingEngine/MacroExecutor
// that consume it stay portable. Android-specific key-code *mapping* (translating
// android.view.KeyEvent.KEYCODE_* into our own KeyCode enum) lives in :service,
// at the capture-adapter boundary — never here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

kotlin {
    jvmToolchain(17)
}
