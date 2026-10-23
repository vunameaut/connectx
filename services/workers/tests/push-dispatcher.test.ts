import { describe, it, expect } from 'vitest';
import { PushDispatcher } from '../src/push-dispatcher.js';
import type { DeviceRow } from '@connectx/shared-types';

describe('Push Dispatcher Tests', () => {
  const dispatcher = new PushDispatcher();

  const mockAndroidDevice: DeviceRow = {
    id: 'device-1',
    user_id: 'user-1',
    push_token: 'fcm-token-android-12345',
    platform: 'android',
    updated_at: new Date().toISOString(),
  };

  const mockIosDevice: DeviceRow = {
    id: 'device-2',
    user_id: 'user-2',
    push_token: 'apns-token-ios-67890',
    platform: 'ios',
    updated_at: new Date().toISOString(),
  };

  it('TC-6.1: Respects privacy settings: masks message preview by default', () => {
    const fcm = dispatcher.formatMessagePush(
      mockAndroidDevice,
      'Alice',
      'Confidential banking password is 1234',
      'conv-101',
      { notification_preferences: { messages: true, calls: true, preview_text: false } }
    );

    expect(fcm.notification?.title).toBe('Alice');
    expect(fcm.notification?.body).toBe('New message received');
    expect(fcm.notification?.body).not.toContain('banking');
  });

  it('TC-6.2: Displays preview text when user explicitly opts in', () => {
    const fcm = dispatcher.formatMessagePush(
      mockIosDevice,
      'Alice',
      'See you at 5pm!',
      'conv-101',
      { notification_preferences: { messages: true, calls: true, preview_text: true } }
    );

    expect(fcm.notification?.body).toBe('See you at 5pm!');
  });

  it('TC-6.3: Formats incoming call push with 30s TTL and VoIP priority', () => {
    const callPush = dispatcher.formatIncomingCallPush(
      mockIosDevice,
      'call-uuid-999',
      'Alice',
      'video'
    );

    expect(callPush.data?.type).toBe('INCOMING_CALL');
    expect(callPush.data?.callId).toBe('call-uuid-999');
    expect(callPush.android?.ttl).toBe('30s');
    expect(callPush.apns?.headers['apns-push-type']).toBe('voip');
  });

  it('TC-6.4: Formats call canceled push with short TTL', () => {
    const cancelPush = dispatcher.formatCallCanceledPush(mockAndroidDevice, 'call-uuid-999');

    expect(cancelPush.data?.type).toBe('CALL_CANCELED');
    expect(cancelPush.android?.ttl).toBe('10s');
  });
});
