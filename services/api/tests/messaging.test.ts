import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildServer } from '../src/server.js';

describe('Messaging & Conversation RLS API Tests', () => {
  let app: FastifyInstance;
  let tokenAlice: string;
  let tokenBob: string;
  let tokenEve: string;
  let aliceId: string;
  let bobId: string;
  let eveId: string;
  let directConvId: string;
  let sentMessageId: string;

  beforeAll(async () => {
    process.env.NODE_ENV = 'test';
    app = await buildServer();
    await app.ready();

    // Exchange tokens for Alice, Bob, and Eve
    const resA = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-valid-alice-msg' },
    });
    const bodyA = JSON.parse(resA.payload);
    tokenAlice = bodyA.accessToken;
    aliceId = bodyA.profile.id;

    const resB = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-valid-bob-msg' },
    });
    const bodyB = JSON.parse(resB.payload);
    tokenBob = bodyB.accessToken;
    bobId = bodyB.profile.id;

    const resE = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-valid-eve-msg' },
    });
    const bodyE = JSON.parse(resE.payload);
    tokenEve = bodyE.accessToken;
    eveId = bodyE.profile.id;
  });

  afterAll(async () => {
    await app.close();
  });

  it('TC-3.1: Create 1:1 conversation between Alice and Bob', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/conversations',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        kind: 'direct',
        participantIds: [bobId],
      },
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.payload);
    directConvId = body.conversation.id;
    expect(directConvId).toBeDefined();
    expect(body.conversation.kind).toBe('direct');
  });

  it('TC-3.2: Alice sends message with idempotency key', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/api/v1/conversations/${directConvId}/messages`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        messageType: 'text',
        textBody: 'Hello Bob! How are you?',
        idempotencyKey: 'idem-key-001',
      },
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.payload);
    expect(body.message.id).toBeDefined();
    expect(body.message.text_body).toBe('Hello Bob! How are you?');
    expect(body.message.sender_id).toBe(aliceId);
    sentMessageId = body.message.id;
  });

  it('TC-3.3: Idempotency deduplication: resending with same key returns identical message', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/api/v1/conversations/${directConvId}/messages`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        messageType: 'text',
        textBody: 'Hello Bob! How are you?',
        idempotencyKey: 'idem-key-001',
      },
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.payload);
    expect(body.message.id).toBe(sentMessageId); // Must match original
  });

  it('TC-3.4: Bob can fetch and read messages in the conversation', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/api/v1/conversations/${directConvId}/messages?limit=10`,
      headers: { authorization: `Bearer ${tokenBob}` },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.messages.length).toBe(1);
    expect(body.messages[0].id).toBe(sentMessageId);
  });

  it('TC-3.5: RLS Privacy: Eve (non-member) is forbidden from reading messages (403)', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/api/v1/conversations/${directConvId}/messages`,
      headers: { authorization: `Bearer ${tokenEve}` },
    });

    expect(res.statusCode).toBe(403);
    const body = JSON.parse(res.payload);
    expect(body.error).toBe('FORBIDDEN');
  });

  it('TC-3.6: RLS Privacy: Eve is forbidden from inserting messages into Alice & Bob conversation (403)', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/api/v1/conversations/${directConvId}/messages`,
      headers: { authorization: `Bearer ${tokenEve}` },
      payload: {
        messageType: 'text',
        textBody: 'Sneaky message from Eve',
      },
    });

    expect(res.statusCode).toBe(403);
  });

  it('TC-3.7: Alice edits own message within 15 minutes', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/v1/messages/${sentMessageId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { textBody: 'Hello Bob! How are you doing today?' },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.message.text_body).toBe('Hello Bob! How are you doing today?');
    expect(body.message.edited_at).toBeDefined();
  });

  it('TC-3.8: Bob is forbidden from editing Alice message (403)', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/v1/messages/${sentMessageId}`,
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: { textBody: 'Tampered by Bob' },
    });

    expect(res.statusCode).toBe(403);
  });

  it('TC-3.9: Alice sends message with Cloudinary attachment', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/api/v1/conversations/${directConvId}/messages`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        messageType: 'image',
        textBody: 'Check out this screenshot!',
        attachment: {
          assetId: 'cloudinary-asset-img-001',
          resourceType: 'image',
          mimeType: 'image/png',
          sizeBytes: 524288,
          width: 1080,
          height: 1920,
        },
      },
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.payload);
    expect(body.message.message_type).toBe('image');
    expect(body.attachment).toBeDefined();
    expect(body.attachment.asset_id).toBe('cloudinary-asset-img-001');
    expect(body.attachment.provider).toBe('cloudinary');
  });

  it('TC-3.10: Alice soft-deletes message', async () => {
    const res = await app.inject({
      method: 'DELETE',
      url: `/api/v1/messages/${sentMessageId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.message.deleted_at).toBeDefined();
    expect(body.message.text_body).toBe('This message was deleted');
  });
});
