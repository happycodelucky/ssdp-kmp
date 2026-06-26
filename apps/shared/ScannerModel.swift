//
//  ScannerModel.swift
//  ssdp-kmp Apple samples (shared by iOS + macOS).
//
//  Owns the KMP SsdpClient, observes its USN-keyed device StateFlow via SKIE's
//  AsyncSequence bridge, groups devices by UDN for display, and fetches
//  descriptions on demand. The view layer (ScannerScreen / DeviceDetailView) is
//  identical on iOS and macOS — the only platform difference is the @main App
//  entry under apps/ios and apps/macos.
//

import Foundation
import Ssdp

/// One physical device, aggregated from the SSDP responses that share a UDN.
struct DeviceRow: Identifiable, Hashable {
    /// The UDN (USN prefix before `::`) — the stable per-device identity / list id.
    let id: String
    /// A representative full USN, used to fetch the description.
    let usn: String
    let displayName: String
    let location: String?
    let server: String?
    let serviceCount: Int

    /// `192.168.1.42:1400` from the LOCATION, for display.
    var hostPort: String? {
        guard let location, let afterScheme = location.components(separatedBy: "://").last else { return nil }
        let hostPort = afterScheme.components(separatedBy: "/").first ?? ""
        return hostPort.isEmpty ? nil : hostPort
    }
}

@MainActor
final class ScannerModel: ObservableObject {
    @Published private(set) var devices: [DeviceRow] = []
    @Published private(set) var scanning = false

    private let client: SsdpClient
    private var observation: Task<Void, Never>?

    init() {
        // Apple factory: no Context needed (unlike Android). Self-contained.
        // SKIE bridges the @Throws factory as a Swift throwing function (the
        // multicast join can fail — e.g. a missing entitlement on iOS); we
        // crash early in the sample if discovery can't start.
        client = try! SsdpClient(bindInterface: nil)
        observe()
    }

    deinit {
        observation?.cancel()
        client.close()
    }

    /// Subscribe to the device StateFlow. SKIE bridges StateFlow<T> to an
    /// AsyncSequence, so a `for await` loop receives every registry update.
    private func observe() {
        observation = Task { [weak self] in
            guard let self else { return }
            for await byUsn in client.devices {
                // SKIE preserves the element type as a typed Swift dictionary
                // [String: DiscoveredDevice].
                self.devices = Self.groupByUdn(Array(byUsn.values))
            }
        }
    }

    /// Start a bounded scan for all SSDP targets. We drive the window from Swift
    /// (search → sleep → stopSearch) rather than the Kotlin `timeout:` parameter:
    /// that param is a `kotlin.time.Duration`, an inline value class SKIE can't
    /// bridge cleanly (it surfaces as `Any?`), so the idiomatic Swift path is an
    /// explicit stopSearch(). Passive listening continues after stopSearch, so
    /// late responders still appear.
    func scan() {
        Task {
            scanning = true
            // SearchTarget.All is a Kotlin `data object` → SKIE flattens it to the
            // top-level `SearchTargetAll.shared` singleton (not a nested `.All`).
            try? await client.search(
                targets: [SearchTargetAll.shared],
                maxWaitSeconds: 1,
                timeout: nil
            )
            try? await Task.sleep(for: .seconds(scanWindowSeconds))
            try? await client.stopSearch()
            scanning = false
        }
    }

    /// Fetch (or return cached) the description for [row]. The USN overload is
    /// `descriptionForUsn` in Swift (the @ObjCName that disambiguates it from the
    /// device overload), and it's throwing.
    func describe(_ row: DeviceRow) async -> DescriptionResult {
        // DescriptionResult.NotFound is a Kotlin `data object` → top-level
        // `DescriptionResultNotFound.shared` singleton in Swift.
        (try? await client.descriptionForUsn(usn: row.usn)) ?? DescriptionResultNotFound.shared
    }

    private let scanWindowSeconds: TimeInterval = 6

    private static func groupByUdn(_ devices: [DiscoveredDevice]) -> [DeviceRow] {
        let groups = Dictionary(grouping: devices) { udnOf($0.usn) }
        return groups.map { udn, records in
            let primary = records.first(where: { $0.location != nil }) ?? records[0]
            let serviceCount = records.filter { $0.usn.contains("::urn:") && $0.usn.contains(":service:") }.count
            return DeviceRow(
                id: udn,
                usn: primary.usn,
                displayName: primary.location.map(hostOf) ?? udn,
                location: primary.location,
                server: records.compactMap(\.server).first,
                serviceCount: serviceCount
            )
        }
        .sorted { $0.displayName < $1.displayName }
    }

    private static func udnOf(_ usn: String) -> String {
        usn.components(separatedBy: "::").first ?? usn
    }

    private static func hostOf(_ location: String) -> String {
        let afterScheme = location.components(separatedBy: "://").last ?? location
        return afterScheme.components(separatedBy: "/").first ?? location
    }
}
