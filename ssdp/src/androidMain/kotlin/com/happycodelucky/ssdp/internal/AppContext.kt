/*
 * ssdp-kmp — Android application-Context holder.
 *
 * The Android multicast transport needs a Context to acquire a
 * WifiManager.MulticastLock. Rather than thread a Context through every factory
 * call, [SsdpInitializer] captures the application Context once at process
 * startup (via androidx.startup, before Application.onCreate) and stashes it
 * here. The Context-free Android `SsdpClient()` factory then reads it when it
 * builds a client, so it needs no Context argument.
 */
package com.happycodelucky.ssdp.internal

import android.content.Context
import kotlinx.atomicfu.atomic

// Written once during startup, read later from whichever thread builds a client:
// the atomic reference publishes the write safely across threads.
private val captured = atomic<Context?>(null)

/**
 * The application Context captured by [SsdpInitializer] at startup, or `null` if
 * it never ran — the consumer disabled androidx.startup's `InitializationProvider`
 * or removed the `SsdpInitializer` meta-data. Without it, only the explicit
 * `SsdpClient(context)` factory can hold a multicast lock.
 */
internal val capturedApplicationContext: Context? get() = captured.value

/** Capture the application Context. Called by [SsdpInitializer]; idempotent. */
internal fun initAndroidContext(context: Context) {
    captured.value = context.applicationContext
}
