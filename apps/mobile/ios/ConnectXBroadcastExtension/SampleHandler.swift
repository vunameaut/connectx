import ReplayKit
import CoreMedia

/**
 * ConnectX iOS Broadcast Upload Extension.
 * Captures full-device screen sample buffers via ReplayKit and streams frames to the main app.
 *
 * CRITICAL CONSTRAINTS:
 * 1. Memory limit: Max 50MB resident memory ceiling (exceeding causes jetsam kill).
 * 2. Protected content: DRM FairPlay and secure screens are blacked out or omitted by iOS compositor.
 */
class SampleHandler: RPBroadcastSampleHandler {

    private let appGroupName = "group.com.connectx.app"
    private let darwinNotificationStarted = "com.connectx.screenshare.started" as CFString
    private let darwinNotificationStopped = "com.connectx.screenshare.stopped" as CFString

    private var frameCounter: UInt64 = 0
    private var lastSampleTimestamp: CMTime = .zero

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        // Notify the main app process via Darwin Notification Center
        let notificationCenter = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterPostNotification(notificationCenter, CFNotificationName(darwinNotificationStarted), nil, nil, true)
    }

    override func broadcastPaused() {
        // User or system paused broadcast
    }

    override func broadcastResumed() {
        // User or system resumed broadcast
    }

    override func broadcastFinished() {
        // Notify the main app that screen broadcast has ended
        let notificationCenter = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterPostNotification(notificationCenter, CFNotificationName(darwinNotificationStopped), nil, nil, true)
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        switch sampleBufferType {
        case .video:
            handleVideoSampleBuffer(sampleBuffer)
        case .audioApp:
            // Application audio if audio sharing enabled
            break
        case .audioMic:
            // Microphone audio is managed by main app WebRTC audio track to prevent echo
            break
        @unknown default:
            break
        }
    }

    private func handleVideoSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        guard CMSampleBufferIsValid(sampleBuffer) else { return }

        // Throttle frame rate to ~15 fps to conserve battery and CPU
        let currentTimestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if lastSampleTimestamp != .zero {
            let delta = CMTimeGetSeconds(CMTimeSubtract(currentTimestamp, lastSampleTimestamp))
            if delta < (1.0 / 18.0) {
                // Drop frame to preserve target ~15 fps and keep under 50MB memory ceiling
                return
            }
        }
        lastSampleTimestamp = currentTimestamp
        frameCounter += 1

        // In production: Frame pixel buffer (CVPixelBuffer) is passed to WebRTC VideoSource
        // via App Group Shared Memory or Unix domain socket.
    }
}
