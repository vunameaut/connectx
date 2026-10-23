import type { DeviceRow, UserSettingsRow } from '@connectx/shared-types';

export interface PushPayload {
  title: string;
  body: string;
  data: Record<string, string>;
  ttlSeconds?: number;
  priority?: 'high' | 'normal';
}

export interface FcmMessagePayload {
  token: string;
  notification?: {
    title: string;
    body: string;
  };
  data?: Record<string, string>;
  android?: {
    priority: 'high' | 'normal';
    ttl: string;
  };
  apns?: {
    headers: Record<string, string>;
    payload: {
      aps: {
        alert?: {
          title: string;
          body: string;
        };
        sound?: string;
        'content-available'?: number;
      };
    };
  };
}

export class PushDispatcher {
  /**
   * Formats a push notification for a new message, respecting user privacy settings.
   */
  public formatMessagePush(
    device: DeviceRow,
    senderName: string,
    messageBody: string,
    conversationId: string,
    recipientSettings?: Partial<UserSettingsRow>
  ): FcmMessagePayload {
    const showPreview = recipientSettings?.notification_preferences?.preview_text ?? false;
    const bodyText = showPreview ? messageBody : 'New message received';

    const payload: FcmMessagePayload = {
      token: device.push_token,
      notification: {
        title: senderName,
        body: bodyText,
      },
      data: {
        type: 'NEW_MESSAGE',
        conversationId,
      },
      android: {
        priority: 'high',
        ttl: '86400s', // 24 hours
      },
      apns: {
        headers: {
          'apns-priority': '10',
        },
        payload: {
          aps: {
            alert: {
              title: senderName,
              body: bodyText,
            },
            sound: 'default',
          },
        },
      },
    };

    return payload;
  }

  /**
   * Formats a high-priority incoming call push notification with 30s TTL.
   */
  public formatIncomingCallPush(
    device: DeviceRow,
    callId: string,
    callerName: string,
    callType: 'voice' | 'video'
  ): FcmMessagePayload {
    const payload: FcmMessagePayload = {
      token: device.push_token,
      data: {
        type: 'INCOMING_CALL',
        callId,
        callerName,
        callType,
      },
      android: {
        priority: 'high',
        ttl: '30s', // Expires after 30s so stale call prompts don't appear
      },
      apns: {
        headers: {
          'apns-priority': '10',
          'apns-push-type': 'voip',
          'apns-expiration': String(Math.floor(Date.now() / 1000) + 30),
        },
        payload: {
          aps: {
            'content-available': 1,
            sound: 'ringtone.aiff',
          },
        },
      },
    };

    return payload;
  }

  /**
   * Formats a call cancellation push to dismiss ongoing ringing on device.
   */
  public formatCallCanceledPush(device: DeviceRow, callId: string): FcmMessagePayload {
    return {
      token: device.push_token,
      data: {
        type: 'CALL_CANCELED',
        callId,
      },
      android: {
        priority: 'high',
        ttl: '10s',
      },
      apns: {
        headers: {
          'apns-priority': '10',
          'apns-push-type': 'background',
        },
        payload: {
          aps: {
            'content-available': 1,
          },
        },
      },
    };
  }
}

export const pushDispatcher = new PushDispatcher();
