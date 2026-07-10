/*
 * ssdp-kmp — Kotlin-only, device-centric conveniences for description access.
 *
 * These are thin delegators to the canonical [SsdpClient.description] /
 * [SsdpClient.cachedDescription] methods, offered only so Kotlin call sites can
 * read in subject-verb order on a device the caller already holds
 * (`device.description(client)` rather than `client.description(device)`).
 *
 * They are @HiddenFromObjC on purpose: as free functions they would bridge to
 * Swift as awkward globals (`SsdpKt.description(device, client, …)`), defeating
 * the readability goal. Swift consumers keep the idiomatic client methods
 * (`client.description(of:)` / `client.cachedDescription(of:)`), which SKIE
 * renders cleanly. The client API stays the single source of truth; this file
 * adds no behavior, only Kotlin ergonomics (CLAUDE.md §7: don't degrade the
 * Swift surface).
 */
@file:OptIn(ExperimentalObjCRefinement::class)

package com.happycodelucky.ssdp

import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Fetch (or return cached) this device's description via [client] — the
 * device-centric spelling of [SsdpClient.description]. Identical semantics,
 * including [refresh] and the sealed [DescriptionResult] return.
 *
 * Kotlin-only; Swift uses `client.description(of: device)`.
 */
@HiddenFromObjC
@Throws(CancellationException::class)
public suspend fun DiscoveredDevice.description(
    client: SsdpClient,
    refresh: Boolean = false,
): DescriptionResult = client.description(this, refresh)

/**
 * The already-fetched description for this device held by [client], or `null`
 * if none is cached — the device-centric spelling of
 * [SsdpClient.cachedDescription]. Synchronous; never fetches.
 *
 * Kotlin-only; Swift uses `client.cachedDescription(of: device)`.
 */
@HiddenFromObjC
public fun DiscoveredDevice.cachedDescription(client: SsdpClient): DeviceDescription? = client.cachedDescription(this)
