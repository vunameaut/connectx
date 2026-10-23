import { describe, it, expect, beforeEach, vi } from 'vitest';
import { CallManager } from '../src/features/calls/call-manager.js';
import { screenCaptureService } from '../src/services/screen-capture.js';
import { appStore } from '../src/state/use-app-store.js';
import type { SignalingMessage } from '@connectx/shared-types';

describe('CallManager & Screen Sharing Lifecycle Integration Tests', () => {
  let manager: CallManager;
  let sentMessages: SignalingMessage[];

  beforeEach(() => {
    appStore.getState().endCall();
    screenCaptureService.stopScreenCapture();
    sentMessages = [];

    manager = new CallManager();
    manager.setTransport({
      send: (msg) => sentMessages.push(msg),
    });
  });

  it('TC-4.1: Initiate call sends call.invite and enters ringing state', () => {
    const call = manager.initiateCall('call-123', 'alice-id', 'bob-id', 'video');

    expect(call.id).toBe('call-123');
    expect(call.status).toBe('ringing');
    expect(sentMessages.length).toBe(1);
    expect(sentMessages[0]?.type).toBe('call.invite');
    expect(sentMessages[0]?.targetId).toBe('bob-id');
    expect(appStore.getState().activeCall?.id).toBe('call-123');
  });

  it('TC-4.2: Accept call transitions status to active and sends call.accept', () => {
    manager.acceptCall('call-123', 'bob-id', 'alice-id');

    expect(manager.getActiveCall()?.status).toBe('active');
    expect(sentMessages.length).toBe(1);
    expect(sentMessages[0]?.type).toBe('call.accept');
    expect(sentMessages[0]?.targetId).toBe('alice-id');
  });

  it('TC-4.3: Reject call sends call.reject and cleans up state', () => {
    manager.initiateCall('call-123', 'alice-id', 'bob-id', 'voice');
    expect(manager.getActiveCall()).not.toBeNull();

    manager.rejectCall('call-123', 'bob-id', 'alice-id', 'busy');

    expect(manager.getActiveCall()).toBeNull();
    expect(sentMessages.some((m) => m.type === 'call.reject')).toBe(true);
    expect(appStore.getState().activeCall).toBeNull();
  });

  it('TC-5.1: Screen share requires active call session', async () => {
    await expect(manager.startScreenShare()).rejects.toThrow('NO_ACTIVE_CALL');
  });

  it('TC-5.2: Screen share during active call requests OS consent and notifies remote peer', async () => {
    // 1. Establish active call
    manager.acceptCall('call-456', 'bob-id', 'alice-id');

    // 2. Start screen share
    const started = await manager.startScreenShare();
    expect(started).toBe(true);
    expect(manager.isSharing()).toBe(true);
    expect(await screenCaptureService.isScreenCaptureActive()).toBe(true);
    expect(appStore.getState().isScreenSharing).toBe(true);

    const shareStartMsg = sentMessages.find((m) => m.type === 'screen_share.started');
    expect(shareStartMsg).toBeDefined();
    expect((shareStartMsg?.payload as { streamId: string }).streamId).toContain('screen-call-456');

    // 3. Stop screen share
    await manager.stopScreenShare('user_stopped');
    expect(manager.isSharing()).toBe(false);
    expect(await screenCaptureService.isScreenCaptureActive()).toBe(false);
    expect(appStore.getState().isScreenSharing).toBe(false);

    const shareStopMsg = sentMessages.find((m) => m.type === 'screen_share.stopped');
    expect(shareStopMsg).toBeDefined();
  });

  it('TC-5.3: Ending call automatically terminates active screen share and capture resources', async () => {
    manager.acceptCall('call-789', 'bob-id', 'alice-id');
    await manager.startScreenShare();
    expect(await screenCaptureService.isScreenCaptureActive()).toBe(true);

    // End call
    await manager.endCall();

    expect(manager.getActiveCall()).toBeNull();
    expect(manager.isSharing()).toBe(false);
    expect(await screenCaptureService.isScreenCaptureActive()).toBe(false);
    expect(sentMessages.some((m) => m.type === 'call.end')).toBe(true);
  });
});
