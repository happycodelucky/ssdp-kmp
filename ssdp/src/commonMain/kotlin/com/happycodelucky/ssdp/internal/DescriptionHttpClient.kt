/*
 * ssdp-kmp — the Ktor client used to fetch device description documents (v1.1).
 *
 * One factory, shared by every platform: the CIO engine is multiplatform
 * (iosArm64/iosSimulatorArm64/macosArm64/android/jvm), so there is no
 * expect/actual seam here (CLAUDE.md §4). A short request timeout ensures a dead
 * or unreachable LOCATION can't hang the `description()` suspend call.
 */
package com.happycodelucky.ssdp.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/** Request/connect timeout for a description fetch — a dead LOCATION must not hang the call. */
private const val DESCRIPTION_TIMEOUT_MS = 5_000L

/** The production HTTP client for description fetches. Closed by [SsdpClientImpl.close]. */
internal fun descriptionHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = DESCRIPTION_TIMEOUT_MS
            connectTimeoutMillis = DESCRIPTION_TIMEOUT_MS
        }
    }
