import { describe, it, expect, beforeEach } from 'vitest';
import { logger, redactSensitiveData } from '../src/utils/logger.js';

describe('Structured Redacted Logger Tests', () => {
  beforeEach(() => {
    logger.clear();
  });

  it('TC-1.1: Automatically redacts JWT tokens', () => {
    const rawJwt = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozsmrnsecret';
    const sanitized = redactSensitiveData(rawJwt);
    expect(sanitized).toBe('[REDACTED_JWT]');
  });

  it('TC-1.2: Automatically redacts sensitive object keys (passwords, tokens, credentials, secrets)', () => {
    const sensitivePayload = {
      user: 'alice',
      password: 'my-super-secret-password',
      accessToken: 'secret-token-1234',
      apiKey: 'xyz-api-key',
      metadata: {
        authorization: 'Bearer token',
        nestedData: 'safe data',
      },
    };

    const sanitized = redactSensitiveData(sensitivePayload) as Record<string, unknown>;
    expect(sanitized.user).toBe('alice');
    expect(sanitized.password).toBe('[REDACTED]');
    expect(sanitized.accessToken).toBe('[REDACTED]');
    expect(sanitized.apiKey).toBe('[REDACTED]');
    const nested = sanitized.metadata as Record<string, unknown>;
    expect(nested.authorization).toBe('[REDACTED]');
    expect(nested.nestedData).toBe('safe data');
  });

  it('TC-1.3: Redacts raw WebRTC SDP payloads', () => {
    const sdpBlob = 'v=0\r\no=- 20518 0 IN IP4 203.0.113.1\r\ns=-\r\nt=0 0\r\na=fingerprint:sha-256 ...';
    const sanitized = redactSensitiveData(sdpBlob);
    expect(sanitized).toBe('[REDACTED_SDP_PAYLOAD]');
  });

  it('TC-1.4: Logger stores entries up to capacity and maintains level tagging', () => {
    logger.info('AUTH', 'User login attempt', { user: 'bob', password: 'secretpassword' });
    const entries = logger.getRecentLogs();

    expect(entries.length).toBe(1);
    expect(entries[0]?.tag).toBe('AUTH');
    expect(entries[0]?.level).toBe('info');
    expect(entries[0]?.metadata?.password).toBe('[REDACTED]');
  });
});
