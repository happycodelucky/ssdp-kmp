/*
 * ssdp-kmp — JVM CLI live discovery harness.
 *
 * Constructs the real SsdpClient, searches for all SSDP targets, and prints
 * device-registry changes as they arrive for a fixed window. This exercises the
 * actual multicast send/receive + retransmit + registry path against whatever
 * UPnP devices are on the local network.
 *
 * Run: ./gradlew :cli:run   (optionally -PdurationSeconds=20)
 */
package com.happycodelucky.ssdp.cli

import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) =
    runBlocking {
        val durationSeconds = args.firstOrNull()?.toIntOrNull() ?: 15
        println("SSDP discovery — searching ssdp:all for ${durationSeconds}s on the local network…")
        println("(Looking for UPnP devices: media servers/renderers, routers, smart-home bridges, printers…)\n")

        val client = SsdpClient()

        // Print every registry change as it happens.
        val changeJob =
            launch {
                client.changes.collect { change ->
                    when (change) {
                        is DeviceChange.Found ->
                            println("  + FOUND   ${change.device.usn}\n            ${change.device.location}")
                        is DeviceChange.Updated ->
                            println("  ~ UPDATED ${change.device.usn}")
                        is DeviceChange.Removed ->
                            println("  - REMOVED ${change.device.usn}  (${change.reason})")
                    }
                }
            }

        // Broadcast only for the discovery window — devices on the LAN are
        // found within a few seconds, after which continued M-SEARCH is noise.
        // Passive NOTIFY listening continues after the timeout, so late joiners
        // still appear in the registry while we wait.
        client.search(setOf(SearchTarget.All), timeout = durationSeconds.seconds)

        delay(durationSeconds * 1000L)

        val devices = client.devices.value.values
        println("\n=== Final registry: ${devices.size} device(s) ===")
        devices.sortedBy { it.usn }.forEach { d ->
            println("• ${d.usn}")
            println("    location:   ${d.location}")
            println("    server:     ${d.server ?: "—"}")
            println("    target:     ${d.target.rawValue}")
            println("    cacheCtrl:  ${d.cacheControl ?: "—"}")
        }

        // v1.1: fetch + parse each device's description document. Proves the real
        // HTTP-fetch + XML-parse + cache path against live UPnP hardware.
        // De-duplicate by location so we don't fetch the same document once per
        // service USN a device exposes.
        val byLocation = devices.filter { it.location != null }.distinctBy { it.location }
        println("\n=== Descriptions (${byLocation.size} unique location(s)) ===")
        byLocation.sortedBy { it.location }.forEach { d ->
            when (val result = client.description(d)) {
                is DescriptionResult.Success -> {
                    val dev = result.description.device
                    println("• ${d.location}")
                    println("    ${dev.friendlyName ?: "—"} — ${dev.manufacturer ?: "—"} ${dev.modelName ?: ""}".trimEnd())
                    println(
                        "    ${dev.services.size} service(s), ${dev.icons.size} icon(s), " +
                            "${dev.embeddedDevices.size} embedded device(s)",
                    )
                }
                is DescriptionResult.FetchFailed ->
                    println("• ${d.location}\n    fetch failed: ${result.statusCode ?: "transport"} ${result.message}")
                is DescriptionResult.ParseFailed ->
                    println("• ${d.location}\n    parse failed: ${result.message}")
                DescriptionResult.NotFound ->
                    println("• ${d.location}\n    no description")
            }
        }

        changeJob.cancel()
        client.close()
        println("\nDone.")
    }
