/*
 * ssdp-kmp — Android sample: the scanner view-model.
 *
 * Owns the SsdpClient, projects its USN-keyed device StateFlow into a list of
 * UDN-grouped [DeviceRow]s (one physical device per row, not one per service
 * USN), and fetches descriptions on demand.
 */
package com.happycodelucky.ssdp.example.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * One physical device, aggregated from the SSDP responses that share a UDN.
 *
 * @property udn the device's Unique Device Name (the USN prefix before `::`) —
 *   the stable per-device identity and the list key.
 * @property usn a representative full USN for the device (used to fetch the
 *   description; any of the device's USNs resolves to the same LOCATION).
 * @property friendlyName best-effort display name. Until a description is
 *   fetched we only have SSDP-level data, so this falls back to the host or UDN.
 * @property location the description-document URL.
 * @property server the SSDP `SERVER` string (OS/UPnP/product), if any.
 * @property serviceCount how many distinct service USNs this device advertised.
 */
data class DeviceRow(
    val udn: String,
    val usn: String,
    val friendlyName: String,
    val location: String?,
    val server: String?,
    val serviceCount: Int,
) {
    /** `192.168.1.42:1400` extracted from the LOCATION, for display. */
    val hostPort: String? = location?.substringAfter("://")?.substringBefore("/")?.takeIf { it.isNotEmpty() }
}

class ScannerViewModel(
    private val client: SsdpClient,
) : ViewModel() {
    /** Discovered devices, grouped by UDN, sorted by display name. */
    val devices: StateFlow<List<DeviceRow>> =
        client.devices
            .map { byUsn -> groupByUdn(byUsn.values) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Start (or restart) a bounded search for all SSDP targets. */
    fun scan() {
        viewModelScope.launch {
            _scanning.value = true
            // Clear first so refresh visibly empties the list and then re-populates;
            // stale devices that have gone but not yet hit their max-age deadline
            // drop immediately. The `devices` projection reacts to client.devices
            // becoming empty, then refills as search responses arrive.
            client.clearDevices()
            // Bounded — broadcasting stops after the window; passive listening
            // continues so late responders still appear.
            client.search(setOf(SearchTarget.All), timeout = SEARCH_WINDOW)
            kotlinx.coroutines.delay(SEARCH_WINDOW)
            _scanning.value = false
        }
    }

    /** Fetch (or return cached) the description for the device behind [row]. */
    suspend fun describe(row: DeviceRow): DescriptionResult = client.description(row.usn)

    override fun onCleared() {
        client.close()
    }

    private fun groupByUdn(devices: Collection<DiscoveredDevice>): List<DeviceRow> =
        devices
            .groupBy { udnOf(it.usn) }
            .map { (udn, records) ->
                // Prefer a record that carries a LOCATION (root/device USNs do).
                val primary = records.firstOrNull { it.location != null } ?: records.first()
                DeviceRow(
                    udn = udn,
                    usn = primary.usn,
                    friendlyName = primary.location?.let { hostOf(it) } ?: udn,
                    location = primary.location,
                    server = records.firstNotNullOfOrNull { it.server },
                    serviceCount = records.count { it.usn.contains("::urn:") && it.usn.contains(":service:") },
                )
            }.sortedBy { it.friendlyName }

    private fun udnOf(usn: String): String = usn.substringBefore("::")

    private fun hostOf(location: String): String = location.substringAfter("://").substringBefore("/")

    private companion object {
        val SEARCH_WINDOW = 6.seconds
    }
}
