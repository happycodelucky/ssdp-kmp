/*
 * ssdp-kmp — the library's shared logger (CLAUDE.md §5: Kermit for logging).
 *
 * One tagged Kermit logger for all library diagnostics, so log lines are
 * consistently tagged "ssdp" and a consumer can filter/redirect them. The
 * library logs sparingly — discovery is a hot path — and never logs payloads or
 * device identifiers at info level. Current uses: the Android-emulator bridge
 * warning.
 */
package com.happycodelucky.ssdp.internal

import co.touchlab.kermit.Logger

internal val ssdpLog: Logger = Logger.withTag("ssdp")
