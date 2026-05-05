// WorkTickConfig.example.swift
//
// Copy this file to WorkTickConfig.swift and fill in real values.
// WorkTickConfig.swift is gitignored — do NOT commit it.
//
// Add WorkTickConfig.swift to BOTH the app target AND the widget extension target
// (right-click in Xcode → Target Membership).

import Foundation

enum WorkTickConfig {
    /// Backend base URL (your Fly.io app URL)
    static let backendURL = URL(string: "https://YOUR-APP.fly.dev")!

    /// API_SHARED_SECRET from your Fly secrets — same value as in
    /// android/local.properties API_SECRET
    static let apiSecret = "PASTE_API_SHARED_SECRET_HERE"
}
