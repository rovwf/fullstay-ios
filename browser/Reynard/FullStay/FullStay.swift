//
//  FullStay.swift
//  Reynard (FullStay)
//
//  FullStay keeps you in fullscreen when you leave the app and come back.
//  Whether it does is decided by a switch on rovwf.com, one per phone. The first time the app runs,
//  the phone signs up there (with the key built into the app) and gets its own id and secret token;
//  from then on it only asks for its own switch:
//      on  = stays in fullscreen when you come back (the default);
//      off = leaves fullscreen when you come back.
//

import UIKit

enum FullStay {
    static let base = "https://rovwf.com"

    private enum Key {
        static let fullscreen = "fullstay.fullscreen"
        static let exitOnReturn = "fullstay.exitOnReturn"
        static let deviceID = "fullstay.deviceID"
        static let token = "fullstay.token"
        static let hintCount = "fullstay.hintCount"
    }

    private static var defaults: UserDefaults { .standard }

    /// Fullscreen browsing was on when you last left (restored after iOS closes the app in the background).
    static var savedFullscreen: Bool {
        get { defaults.bool(forKey: Key.fullscreen) }
        set { defaults.set(newValue, forKey: Key.fullscreen) }
    }

    /// The switch on rovwf.com, as last fetched. true = leave fullscreen when you come back. Defaults to staying.
    static var exitOnReturn: Bool {
        get { defaults.bool(forKey: Key.exitOnReturn) }
        set { defaults.set(newValue, forKey: Key.exitOnReturn) }
    }

    static var hintCount: Int {
        get { defaults.integer(forKey: Key.hintCount) }
        set { defaults.set(newValue, forKey: Key.hintCount) }
    }

    static var deviceID: String? {
        get { defaults.string(forKey: Key.deviceID) }
        set { defaults.set(newValue, forKey: Key.deviceID) }
    }

    static var token: String? {
        get { defaults.string(forKey: Key.token) }
        set { defaults.set(newValue, forKey: Key.token) }
    }

    // MARK: - What the site shows so you can tell phones apart

    static func deviceInfo() -> [String: String] {
        let device = UIDevice.current
        var info: [String: String] = [:]
        // Since iOS 16 this is just "iPhone" or "iPad" unless Apple grants a special entitlement,
        // so the model below is what really tells phones apart. You can rename phones on the site.
        info["name"] = device.name
        let identifier = modelIdentifier()
        info["modelId"] = identifier
        info["model"] = modelName(for: identifier) ?? device.model
        info["maker"] = "Apple"
        info["os"] = "\(device.systemName) \(device.systemVersion)"
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        info["app"] = "\(version) (FullStay)"
        let bounds = UIScreen.main.nativeBounds
        info["screen"] = "\(Int(min(bounds.width, bounds.height)))\u{00d7}\(Int(max(bounds.width, bounds.height))) @\(Int(UIScreen.main.scale))x"
        if let language = Locale.preferredLanguages.first {
            info["lang"] = language
        }
        info["tz"] = TimeZone.current.identifier
        if let capacity = try? URL(fileURLWithPath: NSHomeDirectory())
            .resourceValues(forKeys: [.volumeTotalCapacityKey]).volumeTotalCapacity, capacity > 0 {
            info["storage"] = "\(capacity / 1_000_000_000) GB"
        }
        return info
    }

    static func modelIdentifier() -> String {
        var system = utsname()
        uname(&system)
        let identifier = withUnsafePointer(to: &system.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
        if identifier == "arm64" || identifier == "x86_64" {
            return ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? identifier
        }
        return identifier
    }

    /// Marketing names for the iPhones that can run this build (iOS 17.4+ for AltStore/SideStore, older with TrollStore).
    /// The newest entries are from memory and may be off; the site always shows the raw model code as well.
    static func modelName(for identifier: String) -> String? {
        let names: [String: String] = [
            "iPhone10,1": "iPhone 8", "iPhone10,4": "iPhone 8", "iPhone10,2": "iPhone 8 Plus", "iPhone10,5": "iPhone 8 Plus",
            "iPhone10,3": "iPhone X", "iPhone10,6": "iPhone X",
            "iPhone11,2": "iPhone XS", "iPhone11,4": "iPhone XS Max", "iPhone11,6": "iPhone XS Max", "iPhone11,8": "iPhone XR",
            "iPhone12,1": "iPhone 11", "iPhone12,3": "iPhone 11 Pro", "iPhone12,5": "iPhone 11 Pro Max", "iPhone12,8": "iPhone SE (2nd gen)",
            "iPhone13,1": "iPhone 12 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro", "iPhone13,4": "iPhone 12 Pro Max",
            "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
            "iPhone14,6": "iPhone SE (3rd gen)", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus",
            "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max",
            "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
            "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus", "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max",
            "iPhone17,5": "iPhone 16e",
            "iPhone18,3": "iPhone 17", "iPhone18,4": "iPhone Air", "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max",
        ]
        if let name = names[identifier] {
            return name
        }
        if identifier.hasPrefix("iPad") {
            return "iPad"
        }
        return nil
    }
}

/// Talks to rovwf.com: signs this phone up once, then reads its switch. Fails quietly; the last value is kept.
/// Everything here runs on the main actor (the project's default); only the network wait happens elsewhere.
final class FullStayRemote {
    static let shared = FullStayRemote()

    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 15
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }()
    private var lastCheck = Date.distantPast
    private var running = false
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    /// No key built in = no remote control (the app simply always stays in fullscreen).
    var isEnabled: Bool { !FullStayKey.value.isEmpty }

    /// Fetches this phone's switch. `completion` gets the fresh exit-on-return value, only if an answer arrived.
    /// Checks at most every 20 seconds unless `force` is set.
    func refresh(force: Bool = false, completion: ((Bool) -> Void)? = nil) {
        guard isEnabled, !running, force || Date().timeIntervalSince(lastCheck) > 20 else {
            return
        }
        running = true
        lastCheck = Date()
        Task { @MainActor [weak self] in
            guard let self else { return }
            let exit = await self.sync()
            self.running = false
            self.endBackgroundTask()
            guard let exit else { return }
            FullStay.exitOnReturn = exit
            completion?(exit)
        }
    }

    /// Same as `refresh`, but asks iOS for a few seconds to finish while the app goes to the background.
    func refreshWhileLeaving() {
        guard isEnabled, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "FullStay check") { [weak self] in
            self?.endBackgroundTask()
        }
        refresh(force: true)
        if !running {
            endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    /// Signs up if needed, then reads the switch. Returns exit-on-return, or nil if there was no usable answer.
    private func sync() async -> Bool? {
        for _ in 0..<2 {
            let info = FullStay.deviceInfo()
            guard let id = FullStay.deviceID, let token = FullStay.token else {
                var body: [String: Any] = info
                body["key"] = FullStayKey.value
                body["platform"] = "ios"
                let (status, json) = await post("/_fs/register", body)
                guard status == 200, let json, json["ok"] as? Bool == true,
                      let id = json["id"] as? String, let token = json["token"] as? String,
                      let stay = json["stay"] as? Bool else {
                    return nil                                          // no connection, not set up, or wrong key
                }
                FullStay.deviceID = id
                FullStay.token = token
                return !stay
            }
            let (status, json) = await post("/_fs/state", ["id": id, "token": token, "info": info])
            if status == 404, json?["gone"] as? Bool == true {         // removed on the site: sign up again
                FullStay.deviceID = nil
                FullStay.token = nil
                continue
            }
            guard status == 200, let json, json["ok"] as? Bool == true, let stay = json["stay"] as? Bool else {
                return nil
            }
            return !stay
        }
        return nil
    }

    private func post(_ path: String, _ body: [String: Any]) async -> (Int, [String: Any]?) {
        guard let url = URL(string: FullStay.base + path),
              let data = try? JSONSerialization.data(withJSONObject: body) else {
            return (0, nil)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = data
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let session = self.session
        let (status, reply): (Int, Data?) = await withCheckedContinuation { continuation in
            session.dataTask(with: request) { data, response, _ in
                continuation.resume(returning: ((response as? HTTPURLResponse)?.statusCode ?? 0, data))
            }.resume()
        }
        let json = reply.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
        return (status, json)
    }
}

/// Lets the two-finger double tap work on top of the page without stealing its touches.
final class FullStayGestureDelegate: NSObject, UIGestureRecognizerDelegate {
    static let shared = FullStayGestureDelegate()

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        return true
    }
}

/// What happened around the last app switch (kept on the browser view controller).
struct FullStayState {
    var isAway = false
    var returnedAt: Date?

    /// You're away, or came back less than 3 seconds ago.
    var isNearAppSwitch: Bool {
        if isAway { return true }
        guard let returnedAt else { return false }
        return Date().timeIntervalSince(returnedAt) < 3
    }
}
