// Pure JVM: result wrapper, dispatcher qualifiers and tiny utilities shared by every module.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

kotlin { jvmToolchain(17) }
