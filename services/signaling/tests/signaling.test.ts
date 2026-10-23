import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { WebSocket } from 'ws';
import jwt from 'jsonwebtoken';
import { SignalingServer } from '../src/signaling-server.js';
import type { SignalingMessage } from '@connectx/shared-types';

const JWT_SECRET = 'development-supabase-jwt-secret-at-least-32-chars!';

function createTestToken(userId: string): string {
  return jwt.sign(
    {
      sub: userId,
      role: 'authenticated',
      aud: 'authenticated',
      firebase_uid: `fb-${userId}`,
      iss: 'connectx-auth-bridge',
      iat: Math.floor(Date.now() / 1000),
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
    JWT_SECRET,
    { algorithm: 'HS256' }
  );
}

describe('Signaling Server Tests', () => {
  let server: SignalingServer;
  const TEST_PORT = 4055;

  beforeAll(async () => {
    process.env.NODE_ENV = 'test';
    server = new SignalingServer();
    server.start(TEST_PORT, '127.0.0.1');
  });

  afterAll(async () => {
    await server.stop();
  });

  it('TC-0.4.1: Rejects WebSocket connection without auth token', async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/ws`);

    const closeCode = await new Promise<number>((resolve) => {
      ws.on('close', (code) => resolve(code));
    });

    expect(closeCode).toBe(4001);
  });

  it('TC-0.4.2: Rejects WebSocket connection with invalid/forged JWT', async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/ws?token=forged.invalid.token`);

    const closeCode = await new Promise<number>((resolve) => {
      ws.on('close', (code) => resolve(code));
    });

    expect(closeCode).toBe(4001);
  });

  it('TC-0.4.3: Successfully connects and completes Call Invite -> Accept -> Screen Share flow', async () => {
    const userA = 'user-uuid-alice-1111';
    const userB = 'user-uuid-bob-2222';
    const tokenA = createTestToken(userA);
    const tokenB = createTestToken(userB);

    const wsA = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/ws?token=${tokenA}`);
    const wsB = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/ws?token=${tokenB}`);

    await Promise.all([
      new Promise((resolve) => wsA.on('open', resolve)),
      new Promise((resolve) => wsB.on('open', resolve)),
    ]);

    expect(server.getConnectedClientsCount()).toBe(2);

    const callId = 'call-uuid-9999-8888';

    // 1. User A invites User B
    const bReceivedInvite = new Promise<SignalingMessage>((resolve) => {
      wsB.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'call.invite') resolve(msg);
      });
    });

    wsA.send(JSON.stringify({
      type: 'call.invite',
      callId,
      targetId: userB,
      payload: { callType: 'video', callerName: 'Alice' },
    }));

    const inviteMsg = await bReceivedInvite;
    expect(inviteMsg.callId).toBe(callId);
    expect(inviteMsg.senderId).toBe(userA);

    // 2. User B accepts the call
    const aReceivedAccept = new Promise<SignalingMessage>((resolve) => {
      wsA.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'call.accept') resolve(msg);
      });
    });

    wsB.send(JSON.stringify({
      type: 'call.accept',
      callId,
      payload: { acceptedAt: new Date().toISOString() },
    }));

    const acceptMsg = await aReceivedAccept;
    expect(acceptMsg.callId).toBe(callId);

    // 3. User A starts screen sharing
    const bReceivedScreenStart = new Promise<SignalingMessage>((resolve) => {
      wsB.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'screen_share.started') resolve(msg);
      });
    });

    wsA.send(JSON.stringify({
      type: 'screen_share.started',
      callId,
      payload: { streamId: 'screen-stream-1', width: 1080, height: 1920, frameRate: 15 },
    }));

    const screenStartMsg = await bReceivedScreenStart;
    expect(screenStartMsg.type).toBe('screen_share.started');
    expect(screenStartMsg.senderId).toBe(userA);

    // 4. User A stops screen sharing
    const bReceivedScreenStop = new Promise<SignalingMessage>((resolve) => {
      wsB.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'screen_share.stopped') resolve(msg);
      });
    });

    wsA.send(JSON.stringify({
      type: 'screen_share.stopped',
      callId,
      payload: { reason: 'user_stopped' },
    }));

    const screenStopMsg = await bReceivedScreenStop;
    expect(screenStopMsg.type).toBe('screen_share.stopped');

    // 5. Clean teardown
    wsA.close();
    wsB.close();
  });
});
