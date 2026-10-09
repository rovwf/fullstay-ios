//
//  FullStayUserAgents.swift
//  Reynard (FullStay)
//
//  Ready-made user-agents (the same list as FullStay for Android). Picking one fills in Reynard's own
//  user-agent overrides (Settings > Advanced > Compatibility > Advanced Options), including the matching
//  navigator.platform / oscpu / appVersion, so pages see one consistent browser.
//

import Foundation

struct FullStayUserAgent {
    let name: String
    let userAgent: String
    let platform: String
    let oscpu: String
    let appVersion: String

    private static let chrome = "154"
    private static let firefox = "155"

    static let presets: [FullStayUserAgent] = [
        FullStayUserAgent(
            name: "Chrome \u{2014} Windows",
            userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/\(chrome).0.0.0 Safari/537.36",
            platform: "Win32", oscpu: "Windows NT 10.0; Win64; x64", appVersion: "5.0 (Windows)"
        ),
        FullStayUserAgent(
            name: "Chrome \u{2014} macOS",
            userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/\(chrome).0.0.0 Safari/537.36",
            platform: "MacIntel", oscpu: "Intel Mac OS X 10.15", appVersion: "5.0 (Macintosh)"
        ),
        FullStayUserAgent(
            name: "Chrome \u{2014} Android phone",
            userAgent: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/\(chrome).0.0.0 Mobile Safari/537.36",
            platform: "Linux armv8l", oscpu: "Linux armv8l", appVersion: "5.0 (Linux; Android 10)"
        ),
        FullStayUserAgent(
            name: "Firefox \u{2014} Windows",
            userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:\(firefox).0) Gecko/20100101 Firefox/\(firefox).0",
            platform: "Win32", oscpu: "Windows NT 10.0; Win64; x64", appVersion: "5.0 (Windows)"
        ),
        FullStayUserAgent(
            name: "Firefox \u{2014} macOS",
            userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:\(firefox).0) Gecko/20100101 Firefox/\(firefox).0",
            platform: "MacIntel", oscpu: "Intel Mac OS X 10.15", appVersion: "5.0 (Macintosh)"
        ),
        FullStayUserAgent(
            name: "Firefox \u{2014} Linux",
            userAgent: "Mozilla/5.0 (X11; Linux x86_64; rv:\(firefox).0) Gecko/20100101 Firefox/\(firefox).0",
            platform: "Linux x86_64", oscpu: "Linux x86_64", appVersion: "5.0 (X11)"
        ),
        FullStayUserAgent(
            name: "Edge \u{2014} Windows",
            userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/\(chrome).0.0.0 Safari/537.36 Edg/\(chrome).0.0.0",
            platform: "Win32", oscpu: "Windows NT 10.0; Win64; x64", appVersion: "5.0 (Windows)"
        ),
        FullStayUserAgent(
            name: "Safari \u{2014} macOS",
            userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            platform: "MacIntel", oscpu: "Intel Mac OS X 10.15", appVersion: "5.0 (Macintosh)"
        ),
        FullStayUserAgent(
            name: "Safari \u{2014} iPhone",
            userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1",
            platform: "iPhone", oscpu: "", appVersion: "5.0 (iPhone)"
        ),
        FullStayUserAgent(
            name: "Safari \u{2014} iPad",
            userAgent: "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1",
            platform: "iPad", oscpu: "", appVersion: "5.0 (iPad)"
        ),
    ]

    /// The preset currently in use, if the saved overrides match one exactly.
    static func current() -> FullStayUserAgent? {
        let saved = Prefs.CompatibilitySettings.customUserAgent
        return presets.first { $0.userAgent == saved }
    }

    func apply() {
        Prefs.CompatibilitySettings.customUserAgent = userAgent
        Prefs.CompatibilitySettings.customPlatform = platform
        Prefs.CompatibilitySettings.customOscpu = oscpu
        Prefs.CompatibilitySettings.customAppVersion = appVersion
    }

    /// Back to Reynard's own user-agent.
    static func clear() {
        Prefs.CompatibilitySettings.customUserAgent = ""
        Prefs.CompatibilitySettings.customPlatform = ""
        Prefs.CompatibilitySettings.customOscpu = ""
        Prefs.CompatibilitySettings.customAppVersion = ""
    }
}
