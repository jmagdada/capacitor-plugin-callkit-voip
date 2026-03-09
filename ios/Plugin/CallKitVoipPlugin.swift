import Foundation
import Capacitor
import UIKit
import CallKit
import PushKit
import AVFoundation

@objc(CallKitVoipPlugin)
public class CallKitVoipPlugin: CAPPlugin {

    private var provider: CXProvider?
    private var connectionIdRegistry: [UUID: CallConfig] = [:]
    private var cachedVoipToken: String?

    // Track which calls were answered (to differentiate from declined)
    private var answeredCalls: Set<UUID> = []

    // Track timeout timers for auto-reject
    private var timeoutTimers: [UUID: Timer] = [:]

    // Reject-call backend API config (stored in UserDefaults for use when app is backgrounded)
    private let kRejectConfigBaseUrl = "CallKitVoip.rejectConfig.baseUrl"
    private let kRejectConfigPath = "CallKitVoip.rejectConfig.path"
    private let kRejectConfigAuthToken = "CallKitVoip.rejectConfig.authToken"
    private let kRejectConfigHeaders = "CallKitVoip.rejectConfig.headers"

    // MARK: - Plugin Lifecycle

    // ✅ Called before JS bridge is ready — ideal place to set up CXProvider and observers
    override public func load() {
        super.load()

        setupCallKitProvider()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleVoIPToken(_:)),
            name: NSNotification.Name("VoIPTokenReceived"),
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleVoIPPush(_:)),
            name: NSNotification.Name("VoIPPushReceived"),
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTokenInvalidated),
            name: NSNotification.Name("VoIPTokenInvalidated"),
            object: nil
        )

        print("✅ CallKitVoip: Plugin loaded — CXProvider ready, observers registered")
    }

    private func setupCallKitProvider() {
        let config = CXProviderConfiguration()
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportsVideo = true
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = false

        if let iconImage = UIImage(named: "AppIcon") {
            config.iconTemplateImageData = iconImage.pngData()
        }

        // Uncomment if you have a ringtone in your bundle:
        // config.ringtoneSound = "ringtone.caf"

        provider = CXProvider(configuration: config)
        provider?.setDelegate(self, queue: DispatchQueue.main)

        print("✅ CallKitVoip: CXProvider initialized")
    }

    // MARK: - NotificationCenter Handlers (forwarded from AppDelegate)

    @objc private func handleVoIPToken(_ notification: Notification) {
        guard let token = notification.userInfo?["token"] as? String else { return }
        cachedVoipToken = token
        print("📱 CallKitVoip: VoIP token received from AppDelegate: \(token)")
        notifyListeners("registration", data: ["value": token])
    }

    @objc private func handleVoIPPush(_ notification: Notification) {
        guard let payload = notification.userInfo?["payload"] as? [AnyHashable: Any] else {
            print("❌ CallKitVoip: handleVoIPPush — missing payload")
            return
        }

        // Extract the PushKit completion handler — we MUST call this inside
        // reportNewIncomingCall's callback to keep the app alive on iOS 16
        let completion = notification.userInfo?["completion"] as? () -> Void

        guard let custom = payload["custom"] as? [String: Any],
              let aData = custom["a"] as? [String: Any] else {
            print("❌ CallKitVoip: Invalid payload structure")
            completion?()
            return
        }

        if let callType = aData["call_type"] as? String, callType == "call_cancelled" {
            print("📱 CallKitVoip: Call cancellation received — ending all active calls")
            endAllCalls()
            completion?()
            return
        }

        guard let callId = aData["callId"] as? String else {
            print("❌ CallKitVoip: Invalid payload — missing callId")
            completion?()
            return
        }

        let media = (aData["media"] as? String) ?? "voice"
        let duration = (aData["duration"] as? String) ?? "0"
        let bookingId = (aData["bookingId"] as? String) ??
                        (aData["bookingId"] as? Int).map { String($0) } ?? ""
        let type = (aData["type"] as? String) ?? ""
        let callType = (aData["call_type"] as? String) ?? ""
        let channelId = (aData["channel_id"] as? String) ?? ""

        print("📱 CallKitVoip: Processing incoming call — ID: \(callId), Media: \(media)")

        incomingCall(
            callId: callId,
            media: media,
            duration: duration,
            bookingId: bookingId,
            type: type,
            call_type: callType,
            channel_id: channelId,
            pushCompletion: completion
        )
    }

    @objc private func handleTokenInvalidated() {
        print("⚠️ CallKitVoip: VoIP token invalidated")
        cachedVoipToken = nil
        notifyListeners("tokenInvalidated", data: [:])
    }

    // MARK: - JS-Callable Methods

    @objc dynamic func register(_ call: CAPPluginCall) {
        print("📱 CallKitVoip: JS register() called")

        if let token = cachedVoipToken {
            print("📱 CallKitVoip: Emitting cached token: \(token)")
            notifyListeners("registration", data: ["value": token])
        }

        call.resolve()
    }

    @objc dynamic func getVoipToken(_ call: CAPPluginCall) {
        if let token = cachedVoipToken {
            call.resolve(["value": token])
        } else {
            call.reject("Token not available yet")
        }
    }

    @objc dynamic func setRejectCallConfig(_ call: CAPPluginCall) {
        print("🔍 CallKitVoip: setRejectCallConfig called")
        print("🔍 CallKitVoip: setRejectCallConfig - baseUrl: \(call.getString("baseUrl") ?? "nil"), path: \(call.getString("path") ?? "nil"), authToken: \(call.getString("authToken") ?? "nil"), headers: \(call.getObject("headers") as? [String: String] ?? [:])")
        guard let baseUrl = call.getString("baseUrl"), !baseUrl.isEmpty else {
            call.reject("baseUrl is required")
            return
        }
        let path = call.getString("path") ?? "/api/voip/{channel_id}/drop"
        let authToken = call.getString("authToken")
        let headers = call.getObject("headers") as? [String: String]

        UserDefaults.standard.set(baseUrl, forKey: kRejectConfigBaseUrl)
        UserDefaults.standard.set(path, forKey: kRejectConfigPath)
        UserDefaults.standard.set(authToken, forKey: kRejectConfigAuthToken)
        if let headers = headers, let data = try? JSONSerialization.data(withJSONObject: headers) {
            UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: kRejectConfigHeaders)
        } else {
            UserDefaults.standard.removeObject(forKey: kRejectConfigHeaders)
        }
        call.resolve()
    }

    // Called from JS when PJSIP call is actually connected.
    // We no longer delay action.fulfill() here — it was already called immediately
    // in CXAnswerCallAction. This just updates CallKit's display timer and notifies JS.
    @objc dynamic func callConnected(_ call: CAPPluginCall) {
        guard let uuidString = call.getString("uuid"),
              let uuid = UUID(uuidString: uuidString) else {
            call.reject("Invalid UUID")
            return
        }

        print("✅ CallKitVoip: PJSIP call connected — updating CallKit for UUID: \(uuid)")

        // Update CallKit to reflect the true connected time (starts the in-call timer
        // from when PJSIP actually connected rather than when Answer was tapped).
        // This does NOT re-trigger the answer flow — the CXCallUpdate here is intentionally
        // empty; we only use it to report the updated connection timestamp.
        let update = CXCallUpdate()
        provider?.reportCall(with: uuid, updated: update)

        configureAudioSession()
        notifyEvent(eventName: "callConnected", uuid: uuid)

        call.resolve()
    }

    // Called from JS when user ends the call from your UI
    @objc dynamic func endCall(_ call: CAPPluginCall) {
        print("✅ CallKitVoip: Initiating end call — UUID: \(String(describing: call.getString("uuid")))")
        guard let uuidString = call.getString("uuid"),
              let uuid = UUID(uuidString: uuidString) else {
            print("❌ CallKitVoip: End call — Invalid UUID")
            call.reject("Invalid UUID")
            return
        }

        print("✅ CallKitVoip: End call — UUID: \(uuid)")

        notifyEvent(eventName: "callEnded", uuid: uuid)
        endCallInternal(uuid: uuid)
        call.resolve()
    }

    // MARK: - Internal Call Management

    private func notifyEvent(eventName: String, uuid: UUID) {
        if let config = connectionIdRegistry[uuid] {
            notifyListeners(eventName, data: [
                "callId": config.callId,
                "media": config.media,
                "duration": config.duration,
                "bookingId": config.bookingId,
                "type": config.type,
                "call_type": config.call_type,
                "channel_id": config.channel_id,
                "uuid": uuid.uuidString
            ])
        }
    }

    private func incomingCall(
        callId: String,
        media: String,
        duration: String,
        bookingId: String,
        type: String,
        call_type: String,
        channel_id: String,
        pushCompletion: (() -> Void)?
    ) {
        let uuid = UUID()

        connectionIdRegistry[uuid] = CallConfig(
            callId: callId,
            media: media,
            duration: duration,
            bookingId: bookingId,
            type: type,
            call_type: call_type,
            channel_id: channel_id
        )

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: "Booking #\(bookingId)")
        update.hasVideo = media == "video"
        update.supportsDTMF = false
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.localizedCallerName = "Call #\(bookingId)"

        print("📱 CallKitVoip: Reporting incoming call to CallKit — UUID: \(uuid)")

        provider?.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                print("❌ CallKitVoip: Error reporting call: \(error.localizedDescription)")
                self.connectionIdRegistry.removeValue(forKey: uuid)
            } else {
                print("✅ CallKitVoip: Successfully reported incoming call")
                self.startTimeoutTimer(for: uuid)
            }

            // ✅ Call PushKit completion HERE — after reportNewIncomingCall finishes.
            // This is critical for iOS 16: calling it before this point (e.g. a 2s safety
            // net in AppDelegate) signals iOS the app is done and causes suspension,
            // resulting in a cold start when the user taps Answer.
            pushCompletion?()
        }
    }

    private func endCallInternal(uuid: UUID) {
        cancelTimeoutTimer(for: uuid)
        answeredCalls.remove(uuid)
        connectionIdRegistry.removeValue(forKey: uuid)
        provider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
    }

    private func endAllCalls() {
        print("📱 CallKitVoip: Ending all active calls")

        for (uuid, _) in connectionIdRegistry {
            cancelTimeoutTimer(for: uuid)
            answeredCalls.remove(uuid)
            provider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            notifyEvent(eventName: "callCancelled", uuid: uuid)
        }

        connectionIdRegistry.removeAll()
        answeredCalls.removeAll()
        timeoutTimers.removeAll()

        print("📱 CallKitVoip: All calls ended and cleaned up")
    }

    private func startTimeoutTimer(for uuid: UUID) {
        cancelTimeoutTimer(for: uuid)

        let timer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: false) { [weak self] _ in
            guard let self = self else { return }

            if self.connectionIdRegistry[uuid] != nil {
                print("⏰ CallKitVoip: Timeout — auto-rejecting call after 30 seconds: \(uuid)")
                self.notifyRejectToBackend(uuid: uuid)
                self.endCallInternal(uuid: uuid)
                self.notifyEvent(eventName: "callEnded", uuid: uuid)
            }
        }

        timeoutTimers[uuid] = timer
    }

    private func cancelTimeoutTimer(for uuid: UUID) {
        timeoutTimers[uuid]?.invalidate()
        timeoutTimers.removeValue(forKey: uuid)
    }

    private func notifyRejectToBackend(uuid: UUID) {
        guard let config = connectionIdRegistry[uuid] else { return }

        let baseUrl = UserDefaults.standard.string(forKey: kRejectConfigBaseUrl)
        let pathTemplate = UserDefaults.standard.string(forKey: kRejectConfigPath) ?? "/api/voip/{channel_id}/drop"
        let authToken = UserDefaults.standard.string(forKey: kRejectConfigAuthToken)
        let headersJson = UserDefaults.standard.string(forKey: kRejectConfigHeaders)

        print("🔍 CallKitVoip: Reject API config - baseUrl: \(baseUrl ?? "nil"), pathTemplate: \(pathTemplate ?? "nil"), authToken: \(authToken ?? "nil"), headersJson: \(headersJson ?? "nil")")

        guard let base = baseUrl else {
            print("⚠️ CallKitVoip: Reject API not configured (baseUrl missing)")
            return
        }

        var pathAllowed = CharacterSet.urlPathAllowed
        pathAllowed.remove(charactersIn: "/")
        let channelId = config.channel_id.addingPercentEncoding(withAllowedCharacters: pathAllowed) ?? config.channel_id
        let path = pathTemplate.replacingOccurrences(of: "{channel_id}", with: channelId)
        let baseNorm = base.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let pathNorm = path.hasPrefix("/") ? path : "/" + path
        guard let url = URL(string: baseNorm + pathNorm) else {
            print("⚠️ CallKitVoip: Reject API URL invalid")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        if let token = authToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let json = headersJson, let data = json.data(using: .utf8), let extra = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
            for (k, v) in extra { request.setValue(v, forHTTPHeaderField: k) }
        }

        // Log for BE dev: full path and headers (copy-paste friendly)
        let fullPath = url.absoluteString
        let allHeaders = request.allHTTPHeaderFields ?? [:]
        let headersLog = allHeaders.map { "  \($0.key): \($0.value)" }.joined(separator: "\n")
        print("""
        📤 CallKitVoip: Reject API
        ---
        1. Final full path:
        \(fullPath)
        --
        2. Final headers:
        \(headersLog.isEmpty ? "  (none)" : headersLog)
        ---
        """)

        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                print("""
                ❌ CallKitVoip: Reject API request failed — copy for BE dev
                ---
                3. Complete error:
                \(String(describing: error))
                localizedDescription: \(error.localizedDescription)
                ---
                """)
                return
            }
            let httpResponse = response as? HTTPURLResponse
            let code = httpResponse?.statusCode ?? 0
            let responseBody = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            if code >= 200 && code < 300 {
                print("✅ CallKitVoip: Reject reported to backend")
            } else {
                print("""
                ⚠️ CallKitVoip: Reject API error — copy for BE dev
                ---
                3. Complete error response:
                Status: \(code)
                \(httpResponse.map { "Headers: \($0.allHeaderFields)" } ?? "Headers: (none)")
                Body:
                \(responseBody.isEmpty ? "(empty)" : responseBody)
                ---
                """)
            }
        }
        task.resume()
    }

    private func configureAudioSession() {
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
            try audioSession.setActive(true)
            print("✅ CallKitVoip: Audio session configured")
        } catch {
            print("❌ CallKitVoip: Failed to configure audio session: \(error)")
        }
    }
}

// MARK: - CXProviderDelegate
extension CallKitVoipPlugin: CXProviderDelegate {

    public func providerDidReset(_ provider: CXProvider) {
        print("⚠️ CallKitVoip: Provider reset — cleaning up all calls")
        for (_, timer) in timeoutTimers {
            timer.invalidate()
        }
        timeoutTimers.removeAll()
        connectionIdRegistry.removeAll()
        answeredCalls.removeAll()
    }

    // Called when user taps Answer on CallKit UI
    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        print("✅ CallKitVoip: User answered call: \(action.callUUID)")

        guard connectionIdRegistry[action.callUUID] != nil else {
            print("❌ CallKitVoip: No call config found for UUID: \(action.callUUID)")
            action.fail()
            return
        }

        cancelTimeoutTimer(for: action.callUUID)
        answeredCalls.insert(action.callUUID)

        let uuid = action.callUUID

        // ✅ Fulfill immediately — this is required on iOS 16 to keep the app alive
        // and prevent cold-start relaunch. Do NOT delay this waiting for PJSIP.
        // The CallKit in-call timer will start now, but callConnected() will issue
        // a reportCall(updated:) to sync the display when PJSIP actually connects.
        action.fulfill()

        func configureAndNotify() {
            configureAudioSession()
            // Notify JS to initiate PJSIP answer — JS calls callConnected() when ready
            notifyEvent(eventName: "callAnswered", uuid: uuid)
            print("📱 CallKitVoip: callAnswered sent to JS — waiting for callConnected()")
        }

        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            configureAndNotify()
        case .denied:
            configureAndNotify()
            print("⚠️ CallKitVoip: Microphone permission denied")
        case .undetermined:
            session.requestRecordPermission { _ in
                DispatchQueue.main.async { configureAndNotify() }
            }
        @unknown default:
            configureAndNotify()
        }
    }

    // Called when user declines or ends via CallKit UI
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        print("⚠️ CallKitVoip: CXEndCallAction received for: \(action.callUUID)")

        cancelTimeoutTimer(for: action.callUUID)

        if !answeredCalls.contains(action.callUUID) {
            notifyRejectToBackend(uuid: action.callUUID)
        }

        notifyEvent(eventName: "callEnded", uuid: action.callUUID)

        answeredCalls.remove(action.callUUID)
        connectionIdRegistry.removeValue(forKey: action.callUUID)

        action.fulfill()
    }

    // Audio session active — WebRTC/PJSIP audio can start
    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        print("✅ CallKitVoip: Audio session activated")
    }

    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        print("⚠️ CallKitVoip: Audio session deactivated")
    }
}

// MARK: - CallConfig
extension CallKitVoipPlugin {
    struct CallConfig {
        let callId: String
        let media: String
        let duration: String
        let bookingId: String
        let type: String
        let call_type: String
        let channel_id: String
    }
}