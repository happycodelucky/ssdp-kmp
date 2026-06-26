/*
 * ssdp-kmp — gradle/plugins plugin host.
 *
 * `kotlin-dsl` compiles the precompiled script plugins under src/main/kotlin.
 * The dependencies below put the third-party plugin classes on the convention
 * plugins' compile classpath; versions come from the shared catalog
 * (gradle/libs.versions.toml), so gradle/plugins and the main build can never
 * disagree on a plugin version.
 */

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.skie.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.vanniktech.maven.publish.gradle.plugin)
}
