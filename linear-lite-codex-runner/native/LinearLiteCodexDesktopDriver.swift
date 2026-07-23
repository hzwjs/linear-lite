import AppKit
import ApplicationServices
import Foundation
import ScreenCaptureKit
import Vision

struct LaunchRequest: Decodable {
    let appBundleIdentifier: String
    let projectDirectory: String
    let worktreePath: String
    let branchName: String
    let prompt: String
    let activationMarker: String
    let visualActivationMarker: String
    let timeoutSeconds: Int
}

enum DriverFailure: Error {
    case invalidArguments
    case invalidRequest
    case accessibilityPermissionRequired
    case screenRecordingPermissionRequired
    case codexAppNotRunning
    case composerNotFound
    case sendNotConfirmed
}

func failureCode(_ error: Error) -> String {
    switch error {
    case DriverFailure.invalidArguments: return "CODEX_DESKTOP_DRIVER_ARGUMENT_INVALID"
    case DriverFailure.invalidRequest: return "CODEX_DESKTOP_REQUEST_INVALID"
    case DriverFailure.accessibilityPermissionRequired: return "CODEX_ACCESSIBILITY_PERMISSION_REQUIRED"
    case DriverFailure.screenRecordingPermissionRequired: return "CODEX_SCREEN_RECORDING_PERMISSION_REQUIRED"
    case DriverFailure.codexAppNotRunning: return "CODEX_DESKTOP_APP_NOT_RUNNING"
    case DriverFailure.composerNotFound: return "CODEX_DESKTOP_COMPOSER_NOT_FOUND"
    case DriverFailure.sendNotConfirmed: return "CODEX_DESKTOP_SEND_NOT_CONFIRMED"
    default: return "CODEX_DESKTOP_DRIVER_FAILED"
    }
}

func waitUntil(timeoutSeconds: Int, _ condition: () -> Bool) -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutSeconds))
    while Date() < deadline {
        if condition() { return true }
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    }
    return false
}

func waitForMatch(timeoutSeconds: Int, _ match: () -> VisibleText?) -> VisibleText? {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutSeconds))
    while Date() < deadline {
        if let result = match() { return result }
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    }
    return nil
}

func activate(_ app: NSRunningApplication) {
    app.activate(options: [])
}

struct VisibleText {
    let text: String
    let point: CGPoint
}

func requireScreenRecordingPermission() throws {
    _ = NSApplication.shared
    if CGPreflightScreenCaptureAccess() { return }
    CGRequestScreenCaptureAccess()
    throw DriverFailure.screenRecordingPermissionRequired
}

func requireAccessibilityPermission(prompt: Bool = false) throws {
    if AXIsProcessTrusted() { return }
    if prompt {
        let options = [kAXTrustedCheckOptionPrompt.takeRetainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }
    throw DriverFailure.accessibilityPermissionRequired
}

func windowCapture(for app: NSRunningApplication) throws -> (CGImage, CGRect) {
    let semaphore = DispatchSemaphore(value: 0)
    var result: Result<(CGImage, CGRect), Error>?
    Task {
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let window = content.windows.first(where: { $0.owningApplication?.processID == app.processIdentifier }) else { throw DriverFailure.codexAppNotRunning }
            let configuration = SCStreamConfiguration()
            configuration.width = Int(window.frame.width * 2)
            configuration.height = Int(window.frame.height * 2)
            let image = try await SCScreenshotManager.captureImage(contentFilter: SCContentFilter(desktopIndependentWindow: window), configuration: configuration)
            result = .success((image, window.frame))
        } catch {
            result = .failure(error)
        }
        semaphore.signal()
    }
    semaphore.wait()
    guard let result else { throw DriverFailure.codexAppNotRunning }
    return try result.get()
}

func visibleText(in app: NSRunningApplication) throws -> [VisibleText] {
    try requireScreenRecordingPermission()
    let (image, bounds) = try windowCapture(for: app)
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .fast
    request.recognitionLanguages = ["en-US", "zh-Hans"]
    request.usesLanguageCorrection = false
    try VNImageRequestHandler(cgImage: image).perform([request])
    return (request.results ?? []).compactMap { observation -> VisibleText? in
        guard let candidate = observation.topCandidates(1).first else { return nil }
        let box = observation.boundingBox
        return VisibleText(text: candidate.string, point: CGPoint(x: bounds.minX + box.midX * bounds.width, y: bounds.minY + (1 - box.midY) * bounds.height))
    }
}

func match(_ app: NSRunningApplication, _ needles: [String]) -> VisibleText? {
    guard let text = try? visibleText(in: app) else { return nil }
    let normalizedNeedles = needles.map(normalizeForOCRMatch)
    return text.first { item in
        let normalizedText = normalizeForOCRMatch(item.text)
        return normalizedNeedles.contains { normalizedText.contains($0) }
    }
}

func normalizeForOCRMatch(_ value: String) -> String {
    let folded = value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX")).uppercased()
    return String(folded.unicodeScalars.compactMap { scalar -> Character? in
        guard CharacterSet.alphanumerics.contains(scalar) else { return nil }
        if scalar == "I" { return "1" }
        if scalar == "O" { return "0" }
        return Character(String(scalar))
    })
}

func send() -> Bool {
    guard let keyDown = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: true), let keyUp = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: false) else { return false }
    keyDown.post(tap: .cghidEventTap)
    keyUp.post(tap: .cghidEventTap)
    return true
}

func launch(_ encoded: String) throws {
    let normalized = encoded.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    let padded = normalized.padding(toLength: ((normalized.count + 3) / 4) * 4, withPad: "=", startingAt: 0)
    guard let data = Data(base64Encoded: padded), let request = try? JSONDecoder().decode(LaunchRequest.self, from: data),
          !request.appBundleIdentifier.isEmpty, !request.projectDirectory.isEmpty, !request.worktreePath.isEmpty, !request.branchName.isEmpty, !request.prompt.isEmpty, !request.activationMarker.isEmpty, !request.visualActivationMarker.isEmpty else { throw DriverFailure.invalidRequest }
    try requireAccessibilityPermission()
    guard let prompt = request.prompt.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed), let path = request.projectDirectory.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed), let url = URL(string: "codex://new?path=\(path)&prompt=\(prompt)") else { throw DriverFailure.invalidRequest }
    NSWorkspace.shared.open(url)
    guard waitUntil(timeoutSeconds: request.timeoutSeconds, { NSRunningApplication.runningApplications(withBundleIdentifier: request.appBundleIdentifier).first != nil }), let app = NSRunningApplication.runningApplications(withBundleIdentifier: request.appBundleIdentifier).first else { throw DriverFailure.codexAppNotRunning }
    activate(app)
    guard waitForMatch(timeoutSeconds: request.timeoutSeconds, { match(app, [request.visualActivationMarker]) }) != nil else { throw DriverFailure.composerNotFound }
    guard send() else { throw DriverFailure.sendNotConfirmed }
    print("{\"status\":\"sent\"}")
}

func inspect(_ bundleIdentifier: String) throws {
    guard let app = NSRunningApplication.runningApplications(withBundleIdentifier: bundleIdentifier).first else { throw DriverFailure.codexAppNotRunning }
    let output = try visibleText(in: app).map { "\($0.text)\t\(Int($0.point.x)),\(Int($0.point.y))" }.joined(separator: "\n")
    print(output)
}

func requestAccessibilityAuthorization() throws {
    try requireAccessibilityPermission(prompt: true)
}

func report(_ resultPath: String, status: String, errorCode: String? = nil) {
    var payload: [String: String] = ["status": status]
    if let errorCode { payload["errorCode"] = errorCode }
    guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
    try? data.write(to: URL(fileURLWithPath: resultPath), options: .atomic)
}

let arguments = CommandLine.arguments
if arguments.count == 4 && arguments[1] == "launch" {
    let resultPath = arguments[3]
    do {
        try launch(arguments[2])
        report(resultPath, status: "sent")
        exit(0)
    } catch {
        report(resultPath, status: "failed", errorCode: failureCode(error))
        exit(1)
    }
}
if arguments.count == 3 && arguments[1] == "authorize" {
    let resultPath = arguments[2]
    do {
        try requestAccessibilityAuthorization()
        report(resultPath, status: "authorized")
        exit(0)
    } catch {
        report(resultPath, status: "failed", errorCode: failureCode(error))
        exit(1)
    }
}
if arguments.count == 3 && arguments[1] == "inspect" {
    do {
        try inspect(arguments[2])
        exit(0)
    } catch {
        fputs("\(failureCode(error))\n", stderr)
        exit(1)
    }
}
fputs("CODEX_DESKTOP_DRIVER_ARGUMENT_INVALID\n", stderr)
exit(1)
