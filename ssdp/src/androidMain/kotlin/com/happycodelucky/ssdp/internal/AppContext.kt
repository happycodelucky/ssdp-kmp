/*
 * ssdp-kmp — Android application-Context holder.
 *
 * The Android multicast transport needs a Context to acquire a
 * WifiManager.MulticastLock. Rather than thread a Context through every factory
 * call, [SsdpInitializer] captures the application Context once at process
 * startup (via androidx.startup, before Application.onCreate) and stashes it
 * here. The Android `Ssdp.createClient()` factory then reads it on demand — so
 * the public factory needs no Context argument.
 *
 * Read [androidApplicationContext] lazily, at the point the socket is actually
 * created, not earlier — by then startup has long since run.
 */
package com.happycodelucky.ssdp.internal

import android.content.Context

private var captured: Context? = null

/**
 * The application Context captured by [SsdpInitializer] at startup.
 *
 * @throws IllegalStateException if accessed before the initializer ran — which
 *   only happens if the consumer disabled androidx.startup's
 *   `InitializationProvider` (or removed the `SsdpInitializer` meta-data). In
 *   that case, use the explicit `SsdpClient(context)` factory instead.
 */
internal val androidApplicationContext: Context
    get() =
        captured
            ?: error(
                "Android Context not initialized. The SsdpInitializer (androidx.startup) " +
                    "captures it at process startup; if you disabled InitializationProvider, " +
                    "construct the client with the explicit SsdpClient(context) factory instead.",
            )

/** Capture the application Context. Called by [SsdpInitializer]; idempotent. */
internal fun initAndroidContext(context: Context) {
    captured = context.applicationContext
}
