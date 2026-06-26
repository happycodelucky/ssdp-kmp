@file:Suppress("UnstableApiUsage")

/*
 * ssdp-kmp — apps/android sample.
 *
 * A Jetpack Compose app that scans for SSDP/UPnP devices on the local network
 * and lists them; tapping a device fetches and shows its description document
 * (friendly name, manufacturer, model, services, icons). Useful as a manual
 * end-to-end test of the `:ssdp` library against real hardware.
 *
 * Standalone Android application module — a separate Gradle subproject from the
 * headless `:ssdp` library. Consumes `:ssdp` as a project dependency for local
 * development; downstream apps would consume the AAR via Maven coordinates.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9.0+ ships Kotlin support built in — applying `kotlin.android`
    // separately is now a hard error. Compose Compiler still ships as a
    // standalone Kotlin plugin and is applied explicitly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.happycodelucky.ssdp.example.android"
    compileSdk =
        libs.versions.android.compile.sdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.happycodelucky.ssdp.example.android"
        minSdk =
            libs.versions.android.min.sdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.compile.sdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // The library under test.
    implementation(project(":ssdp"))

    // Compose runtime + UI + Material3 + extended icons (for the detail screen).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Activity entry point + lifecycle-aware Flow collection + viewModel { }.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines for launching the search.
    implementation(libs.kotlinx.coroutines.core)
}
