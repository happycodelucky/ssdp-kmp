//
//  ScannerScreen.swift
//  ssdp-kmp Apple samples (shared by iOS + macOS).
//
//  The scanner list + navigation to a per-device description detail. Shared
//  verbatim between iOS and macOS.
//

import SwiftUI
import Ssdp

struct ScannerScreen: View {
    @StateObject private var model = ScannerModel()

    var body: some View {
        NavigationStack {
            Group {
                if model.devices.isEmpty {
                    ContentUnavailableView(
                        model.scanning ? "Scanning…" : "No devices",
                        systemImage: "antenna.radiowaves.left.and.right",
                        description: Text(
                            model.scanning
                                ? "Searching the local network for SSDP/UPnP devices."
                                : "Tap refresh to scan the local network."
                        )
                    )
                } else {
                    List(model.devices) { device in
                        NavigationLink(value: device) {
                            DeviceRowView(device: device)
                        }
                    }
                }
            }
            .navigationTitle("SSDP Devices")
            .navigationDestination(for: DeviceRow.self) { device in
                DeviceDetailView(device: device, model: model)
            }
            .toolbar {
                ToolbarItem {
                    if model.scanning {
                        ProgressView()
                    } else {
                        Button {
                            model.scan()
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                        .accessibilityLabel("Scan")
                    }
                }
            }
            .task {
                // Initial scan when the screen first appears.
                model.scan()
            }
        }
    }
}

private struct DeviceRowView: View {
    let device: DeviceRow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(device.displayName)
                .font(.body.weight(.medium))
            if let hostPort = device.hostPort {
                Text(hostPort)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let server = device.server {
                Text(server)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Text("\(device.serviceCount) service(s)")
                .font(.caption2)
                .foregroundStyle(.tint)
        }
    }
}
