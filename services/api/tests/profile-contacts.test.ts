import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildServer } from '../src/server.js';

describe('Profile & Contacts API Tests', () => {
  let app: FastifyInstance;
  let tokenAlice: string;
  let tokenBob: string;
  let aliceId: string;
  let bobId: string;

  beforeAll(async () => {
    process.env.NODE_ENV = 'test';
    app = await buildServer();
    await app.ready();

    // Register/exchange token for Alice
    const resA = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-valid-alice-prof' },
    });
    const bodyA = JSON.parse(resA.payload);
    tokenAlice = bodyA.accessToken;
    aliceId = bodyA.profile.id;

    // Register/exchange token for Bob
    const resB = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/exchange-token',
      payload: { idToken: 'test-token-valid-bob-prof' },
    });
    const bodyB = JSON.parse(resB.payload);
    tokenBob = bodyB.accessToken;
    bobId = bodyB.profile.id;
  });

  afterAll(async () => {
    await app.close();
  });

  it('TC-2.1: Update profile displayName and bio', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/v1/profiles/me',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        displayName: 'Alice In Wonderland',
        bio: 'Exploring rabbit holes',
      },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.profile.displayName).toBe('Alice In Wonderland');
    expect(body.profile.bio).toBe('Exploring rabbit holes');
  });

  it('TC-2.2: Rejects invalid profile displayName length', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/v1/profiles/me',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { displayName: '' },
    });
    expect(res.statusCode).toBe(400);
  });

  it('TC-2.3: User search enforces data minimization (no email/phone in response)', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/users/search?q=Wonderland',
      headers: { authorization: `Bearer ${tokenBob}` },
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.payload);
    expect(body.users.length).toBeGreaterThan(0);
    const aliceFound = body.users.find((u: { id: string }) => u.id === aliceId);
    expect(aliceFound).toBeDefined();
    expect(aliceFound.displayName).toBe('Alice In Wonderland');
    // Security check: Private fields must NOT be returned
    expect(aliceFound.email).toBeUndefined();
    expect(aliceFound.phoneNumber).toBeUndefined();
    expect(aliceFound.firebaseUid).toBeUndefined();
  });

  it('TC-2.4: Add contact flow', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/contacts',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { contactId: bobId },
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.payload);
    expect(body.contact.owner_id).toBe(aliceId);
    expect(body.contact.contact_id).toBe(bobId);
    expect(body.contact.status).toBe('accepted');

    const resList = await app.inject({
      method: 'GET',
      url: '/api/v1/contacts',
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    expect(resList.statusCode).toBe(200);
    const listBody = JSON.parse(resList.payload);
    expect(listBody.contacts.some((c: { contact_id: string }) => c.contact_id === bobId)).toBe(true);
  });

  it('TC-2.5: Block contact and verify search exclusion', async () => {
    const resBlock = await app.inject({
      method: 'POST',
      url: '/api/v1/contacts/block',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { userId: bobId },
    });
    expect(resBlock.statusCode).toBe(200);

    // Searching from Bob should now exclude Alice
    const resSearch = await app.inject({
      method: 'GET',
      url: '/api/v1/users/search?q=Wonderland',
      headers: { authorization: `Bearer ${tokenBob}` },
    });
    const searchBody = JSON.parse(resSearch.payload);
    expect(searchBody.users.some((u: { id: string }) => u.id === aliceId)).toBe(false);
  });
});
