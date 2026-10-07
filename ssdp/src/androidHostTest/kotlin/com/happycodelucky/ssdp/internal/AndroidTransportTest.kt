/*
 * ssdp-kmp — which transport each Android factory chooses.
 *
 * The client opens its socket on construction, so these tests check the choice
 * (AndroidTransport) rather than building a client: no real multicast socket, no
 * WifiManager. The fake Context is a ContextWrapper — AGP's mockable android.jar
 * keeps constructors callable — that answers getApplicationContext() itself.
 */
package com.happycodelucky.ssdp.internal

import android.content.Context
import android.content.ContextWrapper
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidTransportTest {
    private val fakeContext: Context =
        object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }

    private val warnings = mutableListOf<String>()
    private val previousWriters = Logger.config.logWriterList

    @BeforeTest
    fun captureWarnings() {
        Logger.setLogWriters(
            object : LogWriter() {
                override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                    if (severity == Severity.Warn) warnings += message
                }
            },
        )
    }

    @AfterTest
    fun restoreWriters() {
        Logger.setLogWriters(previousWriters)
    }

    @Test
    fun contextFreeClientLocksWithTheStartupCapturedContext() {
        initAndroidContext(fakeContext)

        val transport = contextFreeMulticast(bindInterface = "wlan0")

        assertSame(fakeContext, transport.lockContext)
        assertEquals("wlan0", transport.bindInterface)
        assertTrue(warnings.isEmpty(), "unexpected warnings: $warnings")
    }

    @Test
    fun contextFreeClientFallsBackToNoLockAndWarnsWithoutACapturedContext() {
        val transport = contextFreeMulticast(bindInterface = null, captured = null)

        assertNull(transport.lockContext)
        assertTrue(warnings.single().contains("SsdpClient(context)"), "warnings: $warnings")
    }

    @Test
    fun bridgeAwareWithBridgeTunnelsToTheDaemon() {
        val transport = bridgeAwareTransport(useBridge = true, host = "10.0.2.2", port = 1901, captured = fakeContext)

        assertEquals(AndroidTransport.Bridge(host = "10.0.2.2", port = 1901), transport)
        assertTrue(warnings.isEmpty(), "unexpected warnings: $warnings")
    }

    @Test
    fun bridgeAwareWithoutBridgeIsTheLockedMulticastClient() {
        val transport = bridgeAwareTransport(useBridge = false, host = "10.0.2.2", port = 1901, captured = fakeContext)

        val multicast = assertIs<AndroidTransport.Multicast>(transport)
        assertSame(fakeContext, multicast.lockContext)
        assertNull(multicast.bindInterface)
    }

    @Test
    fun bridgeAwareWithoutBridgeOrCapturedContextWarns() {
        val transport = bridgeAwareTransport(useBridge = false, host = "10.0.2.2", port = 1901, captured = null)

        assertNull(assertIs<AndroidTransport.Multicast>(transport).lockContext)
        assertTrue(warnings.single().contains("SsdpClient(context)"), "warnings: $warnings")
    }
}
