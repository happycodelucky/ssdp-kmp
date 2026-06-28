/*
 * ssdp-kmp — Android sample: the scanner list + device detail Composables.
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package com.happycodelucky.ssdp.example.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.DeviceDescription

@Composable
fun ScannerScreen(
    devices: List<DeviceRow>,
    scanning: Boolean,
    onScan: () -> Unit,
    onSelect: (DeviceRow) -> Unit,
) {
    // Kick off an initial scan on first composition.
    LaunchedEffect(Unit) { onScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (scanning) "Scanning…" else "SSDP Devices") },
                actions = {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                    } else {
                        IconButton(onClick = onScan) {
                            Icon(Icons.Default.Refresh, contentDescription = "Scan")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (devices.isEmpty()) {
            EmptyState(scanning, padding)
        } else {
            // Apply the Scaffold's `padding` (top-app-bar + system-bar insets) to
            // the list, or it renders UNDER the app bar and the top cards become
            // clipped/unreachable. `contentPadding` then adds the inner 16dp gutter
            // on top of those insets.
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it.udn }) { device ->
                    DeviceCard(device, onClick = { onSelect(device) })
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    scanning: Boolean,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (scanning) "Searching the local network for SSDP/UPnP devices…" else "No devices found. Tap refresh to scan.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DeviceCard(
    device: DeviceRow,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.friendlyName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            device.hostPort?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            device.server?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${device.serviceCount} service(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun DeviceDetailScreen(
    device: DeviceRow,
    loadDescription: suspend () -> DescriptionResult,
    onBack: () -> Unit,
) {
    var result by remember { mutableStateOf<DescriptionResult?>(null) }
    LaunchedEffect(device.udn) { result = loadDescription() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.friendlyName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // A device with many services (e.g. Sonos ~20) overflows the
                    // viewport — make the detail column scrollable.
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailRow("Address", device.hostPort ?: "—")
            DetailRow("LOCATION", device.location ?: "—")
            DetailRow("SERVER", device.server ?: "—")
            HorizontalDivider()

            when (val r = result) {
                null -> Text("Fetching description…", style = MaterialTheme.typography.bodyMedium)
                is DescriptionResult.Success -> DescriptionBody(r.description)
                DescriptionResult.NotFound -> Text("No description URL for this device.")
                is DescriptionResult.FetchFailed ->
                    Text("Fetch failed: ${r.statusCode ?: "transport"} ${r.message}", color = MaterialTheme.colorScheme.error)
                is DescriptionResult.ParseFailed ->
                    Text("Parse failed: ${r.message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DescriptionBody(description: DeviceDescription) {
    val device = description.device
    Text("Description", style = MaterialTheme.typography.titleMedium)
    DetailRow("Friendly name", device.friendlyName ?: "—")
    DetailRow("Manufacturer", device.manufacturer ?: "—")
    DetailRow("Model", listOfNotNull(device.modelName, device.modelNumber).joinToString(" ").ifEmpty { "—" })
    DetailRow("Device type", device.deviceType)
    DetailRow("UDN", device.udn)
    DetailRow("Icons", device.icons.size.toString())
    DetailRow("Embedded devices", device.embeddedDevices.size.toString())
    HorizontalDivider()
    Text("Services (${device.services.size})", style = MaterialTheme.typography.titleSmall)
    device.services.forEach { service ->
        Text("• ${service.serviceType}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
