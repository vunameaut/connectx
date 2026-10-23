import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildServer } from '../src/server.js';

describe('Media Signing & TURN Credentials Tests', () => {
  let app: FastifyInstance;
  let validToken: string;

  beforeAll(async () => {
    process.env.NODE_ENV = 'test';
    app = await buildServer();
    await app.ready();

    // Obtain valid token for test user
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-valid-bob456' },
    });
    validToken = JSON.parse(res.payload).accessToken;
  });

  afterAll(async () => {
    await app.close();
  });

  it('TC-0.3.5: Generates valid Cloudinary signed upload ticket for permitted image', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/media/sign-upload',
      headers: { authorization: `Bearer ${validToken}` },
      payload: {
        conversationId: '12345678-1234-1234-1234-123456789abc',
        resourceType: 'image',
        mimeType: 'image/jpeg',
        sizeBytes: 1024 * 1024, // 1MB
      },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.cloudName).toBe('connectx-cloud');
    expect(body.signature).toBeDefined();
    expect(body.timestamp).toBeDefined();
    expect(body.folder).toContain('connectx/conversations/12345678-1234-1234-1234-123456789abc');
  });

  it('TC-0.3.6: Rejects file exceeding size limit', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/media/sign-upload',
      headers: { authorization: `Bearer ${validToken}` },
      payload: {
        conversationId: '12345678-1234-1234-1234-123456789abc',
        resourceType: 'image',
        mimeType: 'image/jpeg',
        sizeBytes: 15 * 1024 * 1024, // 15MB exceeds 10MB limit
      },
    });

    expect(res.statusCode).toBe(400);
    const body = JSON.parse(res.payload);
    expect(body.error).toBe('SIGNING_FAILED');
    expect(body.message).toContain('FILE_TOO_LARGE');
  });

  it('TC-0.3.7: Returns ephemeral TURN/STUN credentials', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/calls/ice-servers',
      headers: { authorization: `Bearer ${validToken}` },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.iceServers).toBeInstanceOf(Array);
    expect(body.iceServers.length).toBeGreaterThan(0);
  });
});
