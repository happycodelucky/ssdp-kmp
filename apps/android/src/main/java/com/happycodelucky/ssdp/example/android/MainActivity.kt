/*
 * ssdp-kmp — Android sample: SSDP device scanner + description detail.
 *
 * Constructs the SsdpClient, scans for all SSDP targets, and lists discovered
 * devices grouped by UDN (so a device's many service-USNs collapse to one row).
 * Tapping a device fetches its description document via client.description() and
 * shows manufacturer / model / services / icons.
 *
 * On a physical device it uses the real multicast client SsdpClient(context). On
 * an EMULATOR — which can't receive inbound UDP multicast — it instead uses
 * SsdpClient.bridged(), which tunnels SSDP over TCP to a host-side bridge daemon.
 * Start the daemon on your dev machine first: `mise run app:bridge`. The library
 * does NOT auto-detect emulators; the choice is made here in the app.
 */
package com.happycodelucky.ssdp.example.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.happycodelucky.ssdp.SsdpClient
import com.happycodelucky.ssdp.bridged

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application context drives the WifiManager MulticastLock the client
        // holds while listening — without it Android drops inbound multicast.
        val appContext = applicationContext
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val model: ScannerViewModel =
                        viewModel {
                            // Emulators can't receive inbound UDP multicast, so
                            // bridge over TCP to the host daemon; physical devices
                            // use the real multicast client.
                            val client =
                                if (isProbablyEmulator()) SsdpClient.bridged() else SsdpClient(appContext)
                            ScannerViewModel(client)
                        }
                    ScannerApp(model)
                }
            }
        }
    }
}

/**
 * Best-effort Android-emulator detection. The standard emulator runs the
 * `ranchu`/`goldfish` virtual hardware and ships `generic`/`sdk_gphone` build
 * fingerprints. This is app-side policy, not a library feature — the library
 * never swaps transport on its own.
 */
private fun isProbablyEmulator(): Boolean =
    Build.HARDWARE in setOf("ranchu", "goldfish") ||
        Build.FINGERPRINT.contains("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for") ||
        Build.PRODUCT.startsWith("sdk") ||
        Build.PRODUCT.contains("emulator") ||
        Build.PRODUCT.contains("simulator")

/** Top-level navigation between the scanner list and a device detail screen. */
@Composable
private fun ScannerApp(model: ScannerViewModel) {
    val devices by model.devices.collectAsStateWithLifecycle()
    val scanning by model.scanning.collectAsStateWithLifecycle()
    var selectedUdn by remember { mutableStateOf<String?>(null) }

    val selected = selectedUdn?.let { udn -> devices.firstOrNull { it.udn == udn } }
    if (selected == null) {
        ScannerScreen(
            devices = devices,
            scanning = scanning,
            onScan = { model.scan() },
            onSelect = { selectedUdn = it.udn },
        )
    } else {
        DeviceDetailScreen(
            device = selected,
            loadDescription = { model.describe(selected) },
            onBack = { selectedUdn = null },
        )
    }
}
