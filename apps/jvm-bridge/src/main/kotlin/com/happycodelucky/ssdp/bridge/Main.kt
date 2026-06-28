/*
 * ssdp-kmp — SSDP bridge daemon entry point.
 *
 * Thin shell: all logic lives in :ssdp's jvmMain (runSsdpBridgeDaemon), where the
 * relay pipe is unit-tested. Run on the host so an Android emulator can discover
 * real LAN devices:
 *
 *   mise run app:bridge            # listen on the default port (1901)
 *   mise run app:bridge -- 1901    # explicit port
 *   ./gradlew :jvm-bridge:run --args="1901 192.168.1.5"   # port + multicast iface
 *
 * The emulator app then connects with `SsdpClient.bridged()` (default 10.0.2.2:1901).
 */
package com.happycodelucky.ssdp.bridge

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: DEFAULT_BRIDGE_PORT
    val bindInterface = args.getOrNull(1)
    runSsdpBridgeDaemon(port = port, bindInterface = bindInterface)
}
