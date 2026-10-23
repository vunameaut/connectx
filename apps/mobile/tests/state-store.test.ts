import { describe, it, expect, beforeEach } from 'vitest';
import { appStore } from '../src/state/use-app-store.js';
import type { UserProfileDto, MessageRow, CallRow } from '@connectx/shared-types';

describe('Zustand App Store Slices Tests', () => {
  beforeEach(() => {
    appStore.getState().clearSession();
  });

  it('TC-1.5: Auth slice correctly sets and clears session', () => {
    const profile: UserProfileDto = {
      id: 'profile-uuid-1',
      firebaseUid: 'fb-uid-1',
      displayName: 'Alice',
      avatarAssetId: null,
      bio: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };

    appStore.getState().setSession('valid-access-token', profile);
    expect(appStore.getState().isAuthenticated).toBe(true);
    expect(appStore.getState().accessToken).toBe('valid-access-token');
    expect(appStore.getState().profile?.displayName).toBe('Alice');

    appStore.getState().clearSession();
    expect(appStore.getState().isAuthenticated).toBe(false);
    expect(appStore.getState().accessToken).toBeNull();
    expect(appStore.getState().profile).toBeNull();
  });

  it('TC-1.6: Messaging slice prevents duplicate messages via idempotency key', () => {
    const convId = 'conv-uuid-100';
    const message1: MessageRow = {
      id: 'msg-uuid-1',
      conversation_id: convId,
      sender_id: 'user-uuid-1',
      message_type: 'text',
      text_body: 'Hello World',
      idempotency_key: 'idem-key-12345',
      reply_to_message_id: null,
      created_at: new Date().toISOString(),
      edited_at: null,
      deleted_at: null,
    };

    appStore.getState().addMessage(message1);
    expect(appStore.getState().messagesByConversation[convId]?.length).toBe(1);

    // Try adding identical message again with same idempotency key
    appStore.getState().addMessage(message1);
    expect(appStore.getState().messagesByConversation[convId]?.length).toBe(1);
  });

  it('TC-1.7: Call slice manages active call, mute, and screen sharing state', () => {
    const call: CallRow = {
      id: 'call-uuid-1',
      conversation_id: null,
      initiated_by: 'caller-uuid',
      call_type: 'video',
      status: 'active',
      started_at: new Date().toISOString(),
      ended_at: null,
      end_reason: null,
    };

    appStore.getState().startCall(call, 'callee-uuid');
    expect(appStore.getState().activeCall?.id).toBe('call-uuid-1');
    expect(appStore.getState().remoteParticipantId).toBe('callee-uuid');
    expect(appStore.getState().isScreenSharing).toBe(false);

    appStore.getState().setScreenSharing(true);
    expect(appStore.getState().isScreenSharing).toBe(true);

    appStore.getState().setAudioMuted(true);
    expect(appStore.getState().isAudioMuted).toBe(true);

    appStore.getState().endCall();
    expect(appStore.getState().activeCall).toBeNull();
    expect(appStore.getState().isScreenSharing).toBe(false);
  });
});
