//
//  DeviceDetailView.swift
//  ssdp-kmp Apple samples (shared by iOS + macOS).
//
//  Fetches the selected device's UPnP description document via
//  client.description() and renders manufacturer / model / services / icons.
//  SKIE renders the sealed DescriptionResult as a Swift enum, switched here via
//  `onEnum(of:)`.
//

import SwiftUI
import Ssdp

struct DeviceDetailView: View {
    let device: DeviceRow
    let model: ScannerModel

    @State private var result: DescriptionResult?

    var body: some View {
        List {
            Section("Discovery") {
                labeled("Address", device.hostPort ?? "—")
                labeled("LOCATION", device.location ?? "—")
                labeled("SERVER", device.server ?? "—")
            }

            Section("Description") {
                if let result {
                    descriptionContent(result)
                } else {
                    HStack {
                        ProgressView()
                        Text("Fetching description…").foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle(device.displayName)
        .task {
            result = await model.describe(device)
        }
    }

    @ViewBuilder
    private func descriptionContent(_ result: DescriptionResult) -> some View {
        switch onEnum(of: result) {
        case .success(let success):
            let d = success.description_.device
            labeled("Friendly name", d.friendlyName ?? "—")
            labeled("Manufacturer", d.manufacturer ?? "—")
            labeled("Model", [d.modelName, d.modelNumber].compactMap { $0 }.joined(separator: " "))
            labeled("Device type", d.deviceType)
            labeled("UDN", d.udn)
            labeled("Icons", "\(d.icons.count)")
            labeled("Embedded devices", "\(d.embeddedDevices.count)")
            if !d.services.isEmpty {
                DisclosureGroup("Services (\(d.services.count))") {
                    ForEach(d.services, id: \.serviceId) { service in
                        Text(service.serviceType)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        case .notFound:
            Text("No description URL for this device.").foregroundStyle(.secondary)
        case .fetchFailed(let failure):
            Text("Fetch failed: \(failure.statusCode.map { "\($0)" } ?? "transport") \(failure.message)")
                .foregroundStyle(.red)
        case .parseFailed(let failure):
            Text("Parse failed: \(failure.message)").foregroundStyle(.red)
        }
    }

    private func labeled(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value.isEmpty ? "—" : value).font(.body)
        }
    }
}
