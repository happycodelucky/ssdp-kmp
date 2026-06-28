/*
 * ssdp-kmp — androidx.startup.Initializer that captures the application Context.
 *
 * Wired by the library's AndroidManifest.xml into the merged manifest's
 * `androidx.startup.InitializationProvider` ContentProvider, which runs early in
 * process startup — before Application.onCreate. We stash the application Context
 * (via initAndroidContext) so the Context-free Android `Ssdp.createClient()`
 * factory can acquire the WifiManager.MulticastLock without the caller passing a
 * Context. Mirrors reachable's ReachabilityInitializer.
 *
 * Consumers who disable InitializationProvider won't get auto-capture; they must
 * use the explicit `SsdpClient(context)` factory instead.
 */
package com.happycodelucky.ssdp

import android.content.Context
import androidx.startup.Initializer
import com.happycodelucky.ssdp.internal.initAndroidContext

/**
 * Captures the application [Context] for the library at process startup.
 * Registered in the library manifest under `androidx.startup
 * .InitializationProvider`; the manifest merger combines this entry with any
 * consumer-side `InitializationProvider`.
 */
public class SsdpInitializer : Initializer<Context> {
    override fun create(context: Context): Context {
        val app = context.applicationContext
        initAndroidContext(app)
        return app
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
