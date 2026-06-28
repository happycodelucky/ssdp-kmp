/*
 * ssdp-kmp — Android sample: SSDP device scanner + description detail.
 *
 * Constructs the SsdpClient, scans for all SSDP targets, and lists discovered
 * devices grouped by UDN (so a device's many service-USNs collapse to one row).
 * Tapping a device fetches its description document via client.description() and
 * shows manufacturer / model / services / icons.
 *
 * Client construction is one line: `Ssdp.createBridgeAwareClient(useBridge =
 * isSsdpBridgeNeeded())`. On a physical device that's a normal multicast client;
 * on an EMULATOR — which can't receive inbound UDP multicast — it tunnels over
 * TCP to a host-side bridge daemon (start it first: `mise run app:bridge`). The
 * library captures the application Context at startup (SsdpInitializer), so no
 * Context is threaded here, and emulator detection lives in the library
 * (`isSsdpBridgeNeeded()`).
 */
package com.happycodelucky.ssdp.example.android

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
import com.happycodelucky.ssdp.Ssdp
import com.happycodelucky.ssdp.isSsdpBridgeNeeded

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val model: ScannerViewModel =
                        viewModel {
                            // On an emulator this bridges over TCP to the host
                            // daemon; on a physical device it's normal multicast.
                            val client = Ssdp.createBridgeAwareClient(useBridge = isSsdpBridgeNeeded())
                            ScannerViewModel(client)
                        }
                    ScannerApp(model)
                }
            }
        }
    }
}

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
