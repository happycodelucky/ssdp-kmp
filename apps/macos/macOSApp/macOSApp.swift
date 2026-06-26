//
//  macOSApp.swift
//  ssdp-kmp macOS sample — SSDP device scanner + description detail.
//
//  The scanner/detail UI is shared with the iOS sample (apps/shared); this file
//  is just the macOS @main entry point.
//

import SwiftUI

@main
struct macOSApp: App {
    var body: some Scene {
        WindowGroup {
            ScannerScreen()
                .frame(minWidth: 480, minHeight: 480)
        }
        .windowResizability(.contentSize)
    }
}
