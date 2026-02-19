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

    // Pending answer action — fulfilled in callConnected() when VoIP is actually connected
    private var pendingAnswerAction: CXAnswerCallAction?

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

        guard let custom = payload["custom"] as? [String: Any],
              let aData = custom["a"] as? [String: Any] else {
            print("❌ CallKitVoip: Invalid payload structure")
            return
        }

        if let callType = aData["call_type"] as? String, callType == "call_cancelled" {
            print("📱 CallKitVoip: Call cancellation received — ending all active calls")
            endAllCalls()
            return
        }

        guard let callId = aData["callId"] as? String else {
            print("❌ CallKitVoip: Invalid payload — missing callId")
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

        incomingCall(callId: callId, media: media, duration: duration, bookingId: bookingId, type: type, call_type: callType, channel_id: channelId)
    }

    @objc private func handleTokenInvalidated() {
        print("⚠️ CallKitVoip: VoIP token invalidated")
        cachedVoipToken = nil
        notifyListeners("tokenInvalidated", data: [:])
    }

    // MARK: - JS-Callable Methods

    // Called from JS to initialize — CXProvider is already set up in load(),
    // so this only needs to emit the cached token if available.
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

    // Called from JS when call UI is ready and VoIP is actually connected
    @objc dynamic func callConnected(_ call: CAPPluginCall) {
        guard let uuidString = call.getString("uuid"),
              let uuid = UUID(uuidString: uuidString) else {
            call.reject("Invalid UUID")
            return
        }

        print("✅ CallKitVoip: Call connected — configuring audio session for UUID: \(uuid)")

        // Tell CallKit the call is connected now (after actual VoIP connection)
        if let action = pendingAnswerAction, action.callUUID == uuid {
            action.fulfill(withDateConnected: Date())
            pendingAnswerAction = nil
            print("📱 CallKitVoip: CallKit notified — call connected at \(Date())")
        }

        configureAudioSession()

        // Notify JS that the call is now connected (VoIP established)
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

        // Notify JS that the call was ended (before cleaning up registry)
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
        channel_id: String
    ) {
        let uuid = UUID()

        // Store call configuration BEFORE reporting to CallKit
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
        }
    }

    private func endCallInternal(uuid: UUID) {
        cancelTimeoutTimer(for: uuid)
        if let pending = pendingAnswerAction, pending.callUUID == uuid {
            pending.fulfill(withDateConnected: Date())
            pendingAnswerAction = nil
        }
        answeredCalls.remove(uuid)
        connectionIdRegistry.removeValue(forKey: uuid)

        provider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
    }

    private func endAllCalls() {
        print("📱 CallKitVoip: Ending all active calls")
        if let pending = pendingAnswerAction {
            pending.fulfill(withDateConnected: Date())
            pendingAnswerAction = nil
        }

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
        if let pending = pendingAnswerAction {
            pending.fulfill(withDateConnected: Date())
            pendingAnswerAction = nil
        }
        for (_, timer) in timeoutTimers {
            timer.invalidate()
        }
        timeoutTimers.removeAll()
        connectionIdRegistry.removeAll()
        answeredCalls.removeAll()
    }

    // Called when user answers the call
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

        // Store action; we will fulfill(withDateConnected:) in callConnected() when VoIP is actually connected
        pendingAnswerAction = action

        func configureAndNotify() {
            configureAudioSession()
            notifyEvent(eventName: "callAnswered", uuid: uuid)
            print("📱 CallKitVoip: callAnswered event sent to JS — CallKit will be told connected when callConnected() is called")
        }

        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            configureAndNotify()
        case .denied:
            configureAndNotify()
            print("⚠️ CallKitVoip: Microphone permission denied — prompt user to enable in Settings")
        case .undetermined:
            session.requestRecordPermission { _ in
                DispatchQueue.main.async { configureAndNotify() }
            }
        @unknown default:
            configureAndNotify()
        }
    }

    // Called when user declines or call ends via CallKit UI
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        print("⚠️ CallKitVoip: CXEndCallAction received for: \(action.callUUID)")

        cancelTimeoutTimer(for: action.callUUID)

        // If user ended before callConnected(), fulfill the pending answer so CallKit cleans up
        if let pending = pendingAnswerAction, pending.callUUID == action.callUUID {
            pending.fulfill(withDateConnected: Date())
            pendingAnswerAction = nil
        }

        // Always notify JS so it can end the PJSIP call (whether user declined or ended from CallKit UI)
        notifyEvent(eventName: "callEnded", uuid: action.callUUID)

        answeredCalls.remove(action.callUUID)
        connectionIdRegistry.removeValue(forKey: action.callUUID)

        action.fulfill()
    }

    // Audio session is now active — WebRTC/audio can start
    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        print("✅ CallKitVoip: Audio session activated — app is in foreground")
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