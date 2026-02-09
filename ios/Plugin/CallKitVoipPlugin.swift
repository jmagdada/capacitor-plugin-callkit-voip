import Foundation
import Capacitor
import UIKit
import CallKit
import PushKit

/**
 *  CallKit Voip Plugin provides native PushKit functionality with apple CallKit to capacitor
 */
@objc(CallKitVoipPlugin)
public class CallKitVoipPlugin: CAPPlugin {

    private var provider: CXProvider?
    private let voipRegistry            = PKPushRegistry(queue: nil)
    private var connectionIdRegistry : [UUID: CallConfig] = [:]
    private var handoffConnectionIds: Set<UUID> = []
    private var cachedVoipToken: String?

    @objc dynamic func register(_ call: CAPPluginCall) {
        print("📱 CallKitVoip: Starting registration...")
        voipRegistry.delegate = self
        voipRegistry.desiredPushTypes = [.voIP]
        print("📱 CallKitVoip: PushKit delegate set, desiredPushTypes = [.voIP]")
        
        let config = CXProviderConfiguration(localizedName: "Secure Call")
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportsVideo = true
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = true
        provider = CXProvider(configuration: config)
        provider?.setDelegate(self, queue: DispatchQueue.main)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            if let token = self?.cachedVoipToken {
                print("📱 CallKitVoip: Emitting cached token via delayed check: \(token)")
                self?.notifyListeners("registration", data: ["value": token])
            } else {
                print("⚠️ CallKitVoip: No token available after registration (delegate may not have been called)")
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

    public func notifyEvent(eventName: String, uuid: UUID){
        if let config = connectionIdRegistry[uuid] {
            notifyListeners(eventName, data: [
                "callId": config.callId,
                "media": config.media,
                "duration": config.duration,
                "bookingId": config.bookingId
            ])
        }
    }

    public func incomingCall(
      callId: String,
      media: String,
      duration: String,
      bookingId: String
    ) {
        let update                      = CXCallUpdate()
        update.remoteHandle             = CXHandle(type: .generic, value: bookingId)
        update.hasVideo                 = media == "video"
        update.supportsDTMF             = false
        update.supportsHolding          = true
        update.supportsGrouping         = false
        update.supportsUngrouping       = false
        update.localizedCallerName = "Call #\(bookingId)"
        let uuid = UUID()
      
        connectionIdRegistry[uuid] = .init(
          callId: callId,
          media: media,
          duration: duration,
          bookingId: bookingId
        )
        self.provider?.reportNewIncomingCall(with: uuid, update: update, completion: { (_) in })
    }

    public func endCall(uuid: UUID) {
        connectionIdRegistry.removeValue(forKey: uuid)
        let controller = CXCallController()
        let transaction = CXTransaction(action: CXEndCallAction(call: uuid))
        controller.request(transaction, completion: { error in
            if let error = error {
                print("❌ Error ending call: \(error.localizedDescription)")
            }
        })
    }
}

// MARK: CallKit events handler

extension CallKitVoipPlugin: CXProviderDelegate {

    public func providerDidReset(_ provider: CXProvider) {
        connectionIdRegistry.removeAll()
        handoffConnectionIds.removeAll()
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard connectionIdRegistry[action.callUUID] != nil else {
            action.fail()
            return
        }
        handoffConnectionIds.insert(action.callUUID)
        notifyEvent(eventName: "callAnswered", uuid: action.callUUID)
        action.fulfill()
        endCall(uuid: action.callUUID)
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let isHandoff = handoffConnectionIds.remove(action.callUUID) != nil
        if !isHandoff {
            notifyEvent(eventName: "callEnded", uuid: action.callUUID)
        }
        connectionIdRegistry.removeValue(forKey: action.callUUID)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        // Report connection started
        print("CXStartCallAction represents initiating an outgoing call")
        notifyEvent(eventName: "callStarted", uuid: action.callUUID)
        action.fulfill()
    }
}

// MARK: PushKit events handler
extension CallKitVoipPlugin: PKPushRegistryDelegate {

    public func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        print("📱 CallKitVoip: pushRegistry didUpdate delegate method called")
        let parts = pushCredentials.token.map { String(format: "%02.2hhx", $0) }
        let token = parts.joined()
        print("📱 CallKitVoip: VoIP token received: \(token)")
        cachedVoipToken = token
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            print("📱 CallKitVoip: Emitting registration event for token: \(token)")
            self.notifyListeners("registration", data: ["value": token])
            print("📱 CallKitVoip: Registration event emitted successfully")
        }
    }

    public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {    
        guard let custom = payload.dictionaryPayload["custom"] as? [String: Any],
            let aData = custom["a"] as? [String: Any],
            let callId = aData["callId"] as? String else {
            print("❌ Failed to extract call data from payload")
            completion()
            return
        }

        let media = (aData["media"] as? String) ?? "voice"
        let duration = (aData["duration"] as? String) ?? "0"
        let bookingId = (aData["bookingId"] as? String) ?? (aData["bookingId"] as? Int).map { String($0) } ?? ""
        
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
