/*
 * ssdp-kmp — gradle/plugins included build.
 *
 * Hosts the precompiled convention plugins (`ssdp.kmp-library`,
 * `ssdp.publish`) that deduplicate the module build scripts. Wired into the
 * main build via `pluginManagement { includeBuild("gradle/plugins") }` in the
 * root settings.gradle.kts.
 */

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
    versionCatalogs {
        // Share the main build's catalog so plugin versions stay single-sourced
        // in gradle/libs.versions.toml (CLAUDE.md §10).
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "plugins"
