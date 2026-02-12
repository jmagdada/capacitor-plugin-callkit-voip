import Foundation
import Capacitor
import UIKit
import CallKit
import PushKit
import AVFoundation 

@objc(CallKitVoipPlugin)
public class CallKitVoipPlugin: CAPPlugin {

    private var provider: CXProvider?
    private let voipRegistry = PKPushRegistry(queue: nil)
    private var connectionIdRegistry: [UUID: CallConfig] = [:]
    private var cachedVoipToken: String?
    
    // Track which calls were answered (to differentiate from declined)
    private var answeredCalls: Set<UUID> = []
    
    // Track timeout timers for auto-reject
    private var timeoutTimers: [UUID: Timer] = [:]

    @objc dynamic func register(_ call: CAPPluginCall) {
        print("📱 CallKitVoip: Starting registration...")
        voipRegistry.delegate = self
        voipRegistry.desiredPushTypes = [.voIP]
        
        let config = CXProviderConfiguration()
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportsVideo = true
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = false  // Set to true if you want call history
        
        if let iconImage = UIImage(named: "AppIcon") {
            config.iconTemplateImageData = iconImage.pngData()
        }

        // Add ringtone if you have one in your bundle
        // config.ringtoneSound = "ringtone.caf"
        
        provider = CXProvider(configuration: config)
        provider?.setDelegate(self, queue: DispatchQueue.main)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            if let token = self?.cachedVoipToken {
                print("📱 CallKitVoip: Emitting cached token: \(token)")
                self?.notifyListeners("registration", data: ["value": token])
            }
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
    
    // Called from JS when call UI is ready and connected
    @objc dynamic func callConnected(_ call: CAPPluginCall) {
        guard let uuidString = call.getString("uuid"),
              let uuid = UUID(uuidString: uuidString) else {
            call.reject("Invalid UUID")
            return
        }
        
        print("✅ Call connected, configuring audio session")
        configureAudioSession()
        call.resolve()
    }
    
    // Called from JS when user ends the call from your UI
    @objc dynamic func endCall(_ call: CAPPluginCall) {
        guard let uuidString = call.getString("uuid"),
              let uuid = UUID(uuidString: uuidString) else {
            call.reject("Invalid UUID")
            return
        }
        
        endCallInternal(uuid: uuid)
        call.resolve()
    }

    private func notifyEvent(eventName: String, uuid: UUID) {
        if let config = connectionIdRegistry[uuid] {
            notifyListeners(eventName, data: [
                "callId": config.callId,
                "media": config.media,
                "duration": config.duration,
                "bookingId": config.bookingId,
                "uuid": uuid.uuidString
            ])
        }
    }

    private func incomingCall(
        callId: String,
        media: String,
        duration: String,
        bookingId: String
    ) {
        let uuid = UUID()
        
        // Store call configuration BEFORE reporting to CallKit
        connectionIdRegistry[uuid] = CallConfig(
            callId: callId,
            media: media,
            duration: duration,
            bookingId: bookingId
        )
        
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: "Booking #\(bookingId)")
        update.hasVideo = media == "video"
        update.supportsDTMF = false
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.localizedCallerName = "Call #\(bookingId)"
        
        print("📱 Reporting incoming call: \(uuid)")
        
        // CRITICAL: Report to CallKit - this MUST be called
        provider?.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                print("❌ Error reporting call: \(error.localizedDescription)")
                self.connectionIdRegistry.removeValue(forKey: uuid)
            } else {
                print("✅ Successfully reported incoming call")
                self.startTimeoutTimer(for: uuid)
            }
        }
    }

    private func endCallInternal(uuid: UUID) {
        cancelTimeoutTimer(for: uuid)
        answeredCalls.remove(uuid)
        connectionIdRegistry.removeValue(forKey: uuid)
        
        provider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
    }
    
    private func endAllCalls() {
        print("📱 Ending all active calls due to cancellation")
        
        for (uuid, _) in connectionIdRegistry {
            cancelTimeoutTimer(for: uuid)
            answeredCalls.remove(uuid)
            provider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            notifyEvent(eventName: "callCancelled", uuid: uuid)
        }
        
        connectionIdRegistry.removeAll()
        answeredCalls.removeAll()
        timeoutTimers.removeAll()
        
        print("📱 All calls ended and cleaned up")
    }
    
    private func startTimeoutTimer(for uuid: UUID) {
        cancelTimeoutTimer(for: uuid)
        
        let timer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            
            if self.connectionIdRegistry[uuid] != nil {
                print("⏰ Timeout: Auto-rejecting call after 30 seconds - \(uuid)")
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
            print("✅ Audio session configured")
        } catch {
            print("❌ Failed to configure audio session: \(error)")
        }
    }
}

// MARK: CallKit Delegate
extension CallKitVoipPlugin: CXProviderDelegate {

    public func providerDidReset(_ provider: CXProvider) {
        print("⚠️ Provider reset - cleaning up all calls")
        for (_, timer) in timeoutTimers {
            timer.invalidate()
        }
        timeoutTimers.removeAll()
        connectionIdRegistry.removeAll()
        answeredCalls.removeAll()
    }
    
    // Called when user answers the call
    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        print("✅ User answered call: \(action.callUUID)")
        
        guard connectionIdRegistry[action.callUUID] != nil else {
            print("❌ No call config found for UUID: \(action.callUUID)")
            action.fail()
            return
        }
        
        cancelTimeoutTimer(for: action.callUUID)
        
        // Mark as answered
        answeredCalls.insert(action.callUUID)
        
        // This tells iOS "we're handling this call now"
        action.fulfill(withDateConnected: Date())
        
        // Now configure audio session
        configureAudioSession()
        
        // Notify your JavaScript side that call was answered
        notifyEvent(eventName: "callAnswered", uuid: action.callUUID)
        
        print("📱 Call answered event sent to JS")
    }
    
    // Called when user declines or call ends
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        print("⚠️ Call ended: \(action.callUUID)")
        
        cancelTimeoutTimer(for: action.callUUID)
        
        let wasAnswered = answeredCalls.contains(action.callUUID)
        
        // Only notify if user explicitly declined (wasn't answered)
        if !wasAnswered {
            notifyEvent(eventName: "callEnded", uuid: action.callUUID)
        }
        
        answeredCalls.remove(action.callUUID)
        connectionIdRegistry.removeValue(forKey: action.callUUID)
        
        action.fulfill()
    }
    
    // CRITICAL: This method is key for the handoff
    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        print("✅ Audio session activated - app should now be in foreground")
        
        // The audio session is now active
        // Your WebRTC or call audio should start here
        // At this point, the app MUST be unlocked and in foreground
    }
    
    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        print("⚠️ Audio session deactivated")
    }
}

// MARK: PushKit Delegate
extension CallKitVoipPlugin: PKPushRegistryDelegate {

    public func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        print("📱 VoIP token received")
        let parts = pushCredentials.token.map { String(format: "%02.2hhx", $0) }
        let token = parts.joined()
        cachedVoipToken = token
        
        DispatchQueue.main.async { [weak self] in
            self?.notifyListeners("registration", data: ["value": token])
        }
    }

    public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        
        print("📱 VoIP push received")
        print("📱 Payload: \(payload.dictionaryPayload)")
        
        guard let custom = payload.dictionaryPayload["custom"] as? [String: Any],
              let aData = custom["a"] as? [String: Any] else {
            print("❌ Invalid payload structure")
            completion()
            return
        }
        
        if let callType = aData["type"] as? String, callType == "call_cancelled" {
            print("📱 Call cancellation received - ending all active calls")
            endAllCalls()
            completion()
            return
        }
        
        guard let callId = aData["callId"] as? String else {
            print("❌ Invalid payload structure - missing callId")
            completion()
            return
        }

        let media = (aData["media"] as? String) ?? "voice"
        let duration = (aData["duration"] as? String) ?? "0"
        let bookingId = (aData["bookingId"] as? String) ?? 
                        (aData["bookingId"] as? Int).map { String($0) } ?? ""
        
        print("📱 Processing call - ID: \(callId), Media: \(media)")
        
        self.incomingCall(
            callId: callId,
            media: media,
            duration: duration,
            bookingId: bookingId
        )
        
        completion()
    }
    
    public func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        print("⚠️ VoIP token invalidated")
        cachedVoipToken = nil
        notifyListeners("tokenInvalidated", data: [:])
    }
}

extension CallKitVoipPlugin {
    struct CallConfig {
        let callId: String
        let media: String
        let duration: String
        let bookingId: String
    }
}