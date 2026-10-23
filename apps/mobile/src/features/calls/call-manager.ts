import { screenCaptureService } from '../../services/screen-capture.js';
import { appStore } from '../../state/use-app-store.js';
import type {
  CallRow,
  SignalingMessage,
  SignalingEventType,
  ScreenShareStartedPayload,
  ScreenShareStoppedPayload,
} from '@connectx/shared-types';

export type SignalingTransport = {
  send: (message: SignalingMessage) => void;
};

export class CallManager {
  private activeCall: CallRow | null = null;
  private transport: SignalingTransport | null = null;
  private timeoutTimer: NodeJS.Timeout | null = null;
  private isScreenSharing = false;

  public setTransport(transport: SignalingTransport): void {
    this.transport = transport;
  }

  public getActiveCall(): CallRow | null {
    return this.activeCall;
  }

  public isSharing(): boolean {
    return this.isScreenSharing;
  }

  /**
   * Initiate an outgoing call.
   */
  public initiateCall(callId: string, callerId: string, calleeId: string, callType: 'voice' | 'video'): CallRow {
    const call: CallRow = {
      id: callId,
      conversation_id: null,
      initiated_by: callerId,
      call_type: callType,
      status: 'ringing',
      started_at: new Date().toISOString(),
      ended_at: null,
      end_reason: null,
    };

    this.activeCall = call;
    appStore.getState().startCall(call, calleeId);

    this.transport?.send({
      type: 'call.invite',
      callId,
      senderId: callerId,
      targetId: calleeId,
      payload: { callType, callerName: appStore.getState().profile?.displayName || 'User' },
      timestamp: new Date().toISOString(),
    });

    // Start 30-second ringing timeout
    this.timeoutTimer = setTimeout(() => {
      this.handleTimeout();
    }, 30000);

    return call;
  }

  /**
   * Accept an incoming call.
   */
  public acceptCall(callId: string, myUserId: string, callerId: string): void {
    if (this.timeoutTimer) {
      clearTimeout(this.timeoutTimer);
      this.timeoutTimer = null;
    }

    const call: CallRow = {
      id: callId,
      conversation_id: null,
      initiated_by: callerId,
      call_type: 'video',
      status: 'active',
      started_at: new Date().toISOString(),
      ended_at: null,
      end_reason: null,
    };

    this.activeCall = call;
    appStore.getState().startCall(call, callerId);

    this.transport?.send({
      type: 'call.accept',
      callId,
      senderId: myUserId,
      targetId: callerId,
      payload: { acceptedAt: new Date().toISOString() },
      timestamp: new Date().toISOString(),
    });
  }

  /**
   * Reject an incoming call.
   */
  public rejectCall(callId: string, myUserId: string, callerId: string, reason: 'busy' | 'declined' = 'declined'): void {
    if (this.timeoutTimer) {
      clearTimeout(this.timeoutTimer);
      this.timeoutTimer = null;
    }

    this.transport?.send({
      type: 'call.reject',
      callId,
      senderId: myUserId,
      targetId: callerId,
      payload: { reason },
      timestamp: new Date().toISOString(),
    });

    this.cleanup();
  }

  /**
   * Start screen sharing during active call.
   */
  public async startScreenShare(): Promise<boolean> {
    if (!this.activeCall || this.activeCall.status !== 'active') {
      throw new Error('NO_ACTIVE_CALL: Screen share requires an active call session');
    }

    const consentGranted = await screenCaptureService.requestScreenCapture({
      width: 1080,
      height: 1920,
      frameRate: 15,
      bitrateBps: 1_500_000,
    });

    if (!consentGranted) {
      return false;
    }

    this.isScreenSharing = true;
    appStore.getState().setScreenSharing(true);

    const payload: ScreenShareStartedPayload = {
      streamId: `screen-${this.activeCall.id}`,
      width: 1080,
      height: 1920,
      frameRate: 15,
    };

    this.transport?.send({
      type: 'screen_share.started',
      callId: this.activeCall.id,
      senderId: this.activeCall.initiated_by,
      payload,
      timestamp: new Date().toISOString(),
    });

    return true;
  }

  /**
   * Stop screen sharing.
   */
  public async stopScreenShare(reason: 'user_stopped' | 'os_stopped' = 'user_stopped'): Promise<void> {
    if (!this.isScreenSharing) return;

    await screenCaptureService.stopScreenCapture();
    this.isScreenSharing = false;
    appStore.getState().setScreenSharing(false);

    if (this.activeCall) {
      const payload: ScreenShareStoppedPayload = { reason };
      this.transport?.send({
        type: 'screen_share.stopped',
        callId: this.activeCall.id,
        senderId: this.activeCall.initiated_by,
        payload,
        timestamp: new Date().toISOString(),
      });
    }
  }

  /**
   * End the call and terminate all media tracks and capture services.
   */
  public async endCall(): Promise<void> {
    if (this.timeoutTimer) {
      clearTimeout(this.timeoutTimer);
      this.timeoutTimer = null;
    }

    if (this.isScreenSharing) {
      await this.stopScreenShare('user_stopped');
    }

    if (this.activeCall) {
      this.transport?.send({
        type: 'call.end',
        callId: this.activeCall.id,
        senderId: this.activeCall.initiated_by,
        payload: { reason: 'completed' },
        timestamp: new Date().toISOString(),
      });
    }

    this.cleanup();
  }

  private handleTimeout(): void {
    if (this.activeCall && this.activeCall.status === 'ringing') {
      this.transport?.send({
        type: 'call.end',
        callId: this.activeCall.id,
        senderId: this.activeCall.initiated_by,
        payload: { reason: 'timeout' },
        timestamp: new Date().toISOString(),
      });
      this.cleanup();
    }
  }

  private cleanup(): void {
    this.activeCall = null;
    this.isScreenSharing = false;
    appStore.getState().endCall();
  }
}

export const callManager = new CallManager();
