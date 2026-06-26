//
//  iOSApp.swift
//  ssdp-kmp iOS sample — SSDP device scanner + description detail.
//
//  The scanner/detail UI is shared with the macOS sample (apps/shared); this
//  file is just the iOS @main entry point.
//

import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ScannerScreen()
        }
    }
}
