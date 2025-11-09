import Foundation
import NitroModules
import OSLog
import UIKit

class CallManager: HybridCallManagerSpec {
    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "CallManager")

    public override init() {
        super.init()
        logger.info("CallManager hybrid module init")
        CallEngine.shared.initialize()
        logger.info("CallManager initialized")
    }

    public func endCall(callId: String) throws {
        logger.info("endCall ▶ js → native: \(callId)")
        CallEngine.shared.endCall(callId: callId)
    }

    public func endAllCalls() throws {
        logger.info("endAllCalls ▶ js → native")
        CallEngine.shared.endAllCalls()
    }

    public func silenceRingtone() throws {
        logger.info("silenceRingtone ▶ js → native")
        //CallEngine.shared.stopRingtone()
    }

    public func getAudioDevices() throws -> AudioRoutesInfo {
        logger.info("getAudioDevices ▶ js → native")
        return CallEngine.shared.getAudioDevices()
    }

    public func setAudioRoute(route: String) throws {
        logger.info("setAudioRoute ▶ js → native: \(route)")
        CallEngine.shared.setAudioRoute(route: route)
    }

    public func keepScreenAwake(keepAwake: Bool) throws {
        logger.info("keepScreenAwake ▶ js → native: \(keepAwake)")
        CallEngine.shared.setIdleTimerDisabled(shouldDisable: keepAwake)
    }

    public func addCallListener(listener: @escaping (CallEventType, String) -> Void) throws -> Void {
        logger.info("addCallListener ▶ js → native")
        let wrapped: (CallEventType, String) -> Void = { event, payload in
            DispatchQueue.main.async {
                listener(event, payload)
            }
        }
        CallEngine.shared.setEventHandler(wrapped)
    }

    public func removeCallListener() throws -> Void {
        logger.info("removeCallListener ▶ js → native")
        CallEngine.shared.setEventHandler(nil)
    }

    public func registerVoIPTokenListener(listener: @escaping (String) -> Void) throws -> Void {
        logger.info("registerVoIPTokenListener ▶ js → native")
        let wrapped: (String) -> Void = { token in
            DispatchQueue.main.async {
                listener(token)
            }
        }
        VoIPTokenManager.shared.registerTokenListener(wrapped)
    }

    public func removeVoipTokenListener() throws -> Void {
        logger.info("removeVoipTokenListener ▶ js → native")
        VoIPTokenManager.shared.unregisterTokenListener()
    }

    public func startOutgoingCall(callId: String, callType: String, targetName: String, metadata: String?) throws -> Void {
        logger.info("startOutgoingCall ▶ js → native: \(callId), type=\(callType)")
        CallEngine.shared.startOutgoingCall(callId: callId, callType: callType, targetName: targetName, metadata: metadata)
    }

    public func reportIncomingCall(callId: String, callType: String, targetName: String, metadata: String?, token: String?, rejectEndpoint: String?) throws -> Void {
        logger.info("reportIncomingCall ▶ js → native: \(callId), type=\(callType)")
        CallEngine.shared.reportIncomingCall(callId: callId, callType: callType, displayName: targetName, pictureUrl: nil, metadata: metadata, completion: nil)
    }

    public func startCall(callId: String, callType: String, targetName: String, metadata: String?) throws -> Void {
        logger.info("startCall ▶ js → native: \(callId), type=\(callType)")
        CallEngine.shared.startCall(callId: callId, callType: callType, targetName: targetName, metadata: metadata)
    }

    public func callAnswered(callId: String) throws -> Void {
        logger.info("callAnswered ▶ js → native: \(callId)")
        CallEngine.shared.connectOutgoingCall(callId: callId)
    }

    public func setOnHold(callId: String, onHold: Bool) throws -> Void {
        logger.info("setOnHold ▶ js → native: \(callId), onHold=\(onHold)")
        CallEngine.shared.setOnHold(callId: callId, onHold: onHold)
    }

    public func setMuted(callId: String, muted: Bool) throws -> Void {
        logger.info("setMuted ▶ js → native: \(callId), muted=\(muted)")
        CallEngine.shared.setMuted(callId: callId, muted: muted)
    }

    public func updateDisplayCallInformation(callId: String, callerName: String) throws -> Void {
        logger.info("updateDisplayCallInformation ▶ js → native: \(callId), name=\(callerName)")
        CallEngine.shared.updateDisplayCallInformation(callId: callId, callerName: callerName)
    }

    public func hasActiveCall() throws -> Bool {
        logger.info("hasActiveCall ▶ js → native")
        return CallEngine.shared.hasActiveCalls()
    }

    public func requestOverlayPermissionAndroid() throws -> Bool {
        // Platform-specific; return true on iOS as stub
        return true
    }

    public func hasOverlayPermissionAndroid() throws -> Bool {
        // Platform-specific; return true on iOS as stub
        return true
    }
}
