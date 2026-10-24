import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildServer } from '../src/server.js';
import { supabaseBridge } from '../src/services/supabase-bridge.js';

describe('Auth Bridge & Token Exchange Tests', () => {
  let app: FastifyInstance;

  beforeAll(async () => {
    process.env.NODE_ENV = 'test';
    app = await buildServer();
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it('TC-0.3.1: Rejects requests missing idToken', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: {},
    });

    expect(res.statusCode).toBe(400);
    const body = JSON.parse(res.payload);
    expect(body.error).toBe('VALIDATION_ERROR');
  });

  it('TC-0.3.2: Rejects invalid or forged Firebase ID tokens', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-invalid' },
    });

    expect(res.statusCode).toBe(401);
    const body = JSON.parse(res.payload);
    expect(body.error).toBe('UNAUTHORIZED');
  });

  it('TC-0.3.3: Successfully exchanges valid Firebase token for a signed Supabase JWT', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-valid-alice123' },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.tokenType).toBe('Bearer');
    expect(body.accessToken).toBeDefined();
    expect(body.expiresIn).toBe(3600);
    expect(body.profile).toBeDefined();
    expect(body.profile.firebaseUid).toBe('alice123');
    expect(body.profile.id).toBeDefined();

    // Verify minted token has correct Supabase claims
    const decoded = supabaseBridge.verifySupabaseJwt(body.accessToken);
    expect(decoded.sub).toBe(body.profile.id);
    expect(decoded.role).toBe('authenticated');
    expect(decoded.aud).toBe('authenticated');
    expect(decoded.firebase_uid).toBe('alice123');
  });

  it('TC-0.3.4: Rejects protected profile endpoint when token is missing or forged', async () => {
    const resNoAuth = await app.inject({
      method: 'GET',
      url: '/api/v1/profiles/me',
    });
    expect(resNoAuth.statusCode).toBe(401);

    const resBadAuth = await app.inject({
      method: 'GET',
      url: '/api/v1/profiles/me',
      headers: { authorization: 'Bearer invalid.forged.jwt' },
    });
    expect(resBadAuth.statusCode).toBe(401);
  });

  it('TC-0.3.5: Returns mandatory app version metadata and force update flags', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/app/version',
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.forceUpdate).toBe(true);
    expect(body.updateRequired).toBe(true);
    expect(body.versionName).toBeDefined();
    expect(body.minSupportedVersion).toBeDefined();
    expect(body.downloadUrl).toBeDefined();
  });
});

