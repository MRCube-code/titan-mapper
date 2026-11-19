plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.titanmapper.service"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Elevated-permission backends (Architecture §4.4 / §4.5)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
