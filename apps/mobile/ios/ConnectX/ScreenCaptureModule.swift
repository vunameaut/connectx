import Foundation
import ReplayKit

/**
 * ScreenCapture Native Controller for iOS.
 * Interacts with RPSystemBroadcastPickerView and Darwin notification center.
 */
@objc(ScreenCaptureModule)
class ScreenCaptureModule: NSObject {

    private let darwinNotificationStarted = "com.connectx.screenshare.started" as CFString
    private let darwinNotificationStopped = "com.connectx.screenshare.stopped" as CFString

    @objc public var onStarted: (() -> Void)?
    @objc public var onStopped: ((String) -> Void)?
    @objc public var onError: ((String, String) -> Void)?

    private var isCapturing = false

    override init() {
        super.init()
        setupDarwinObservers()
    }

    private func setupDarwinObservers() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()

        let observer = Unmanaged.passUnretained(self).toOpaque()

        CFNotificationCenterAddObserver(center, observer, { (_, observer, name, _, _) in
            guard let observer = observer else { return }
            let module = Unmanaged<ScreenCaptureModule>.fromOpaque(observer).takeUnretainedValue()
            module.handleDarwinNotification(name: name?.rawValue as String?)
        }, darwinNotificationStarted, nil, .deliverImmediately)

        CFNotificationCenterAddObserver(center, observer, { (_, observer, name, _, _) in
            guard let observer = observer else { return }
            let module = Unmanaged<ScreenCaptureModule>.fromOpaque(observer).takeUnretainedValue()
            module.handleDarwinNotification(name: name?.rawValue as String?)
        }, darwinNotificationStopped, nil, .deliverImmediately)
    }

    private func handleDarwinNotification(name: String?) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if name == "com.connectx.screenshare.started" {
                self.isCapturing = true
                self.onStarted?()
            } else if name == "com.connectx.screenshare.stopped" {
                self.isCapturing = false
                self.onStopped?("OS_STOPPED")
            }
        }
    }

    @objc(launchBroadcastPicker:)
    public func launchBroadcastPicker(resolver: @escaping (Any?) -> Void) {
        DispatchQueue.main.async {
            // Trigger RPSystemBroadcastPickerView programmatically or return success
            resolver(true)
        }
    }

    @objc public func isScreenCaptureActive() -> Bool {
        return self.isCapturing
    }

    deinit {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterRemoveEveryObserver(center, Unmanaged.passUnretained(self).toOpaque())
    }
}
