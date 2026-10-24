import crypto from 'node:crypto';
import { config } from '../config.js';

export interface RtcIceServer {
  urls: string | string[];
  username?: string;
  credential?: string;
}

export interface IceServersResponse {
  iceServers: RtcIceServer[];
  expiresInSeconds: number;
}

export class TurnService {
  /**
   * Generates Coturn time-limited ephemeral credentials (HMAC-SHA1).
   * Coturn username format: `<timestamp>:<userId>`
   * Coturn password format: `Base64(HMAC-SHA1(turnSecret, username))`
   */
  public generateEphemeralIceServers(userId: string): IceServersResponse {
    const ttlSeconds = 24 * 3600; // 24 hours
    const expiryTimestamp = Math.floor(Date.now() / 1000) + ttlSeconds;
    const hasStaticCredentials = Boolean(config.TURN_USERNAME && config.TURN_CREDENTIAL);

    let finalUsername = config.TURN_USERNAME;
    let finalCredential = config.TURN_CREDENTIAL;

    const generatedUsername = `${expiryTimestamp}:${userId}`;

    if (!hasStaticCredentials) {
      const hmac = crypto.createHmac('sha1', config.TURN_SECRET);
      hmac.update(generatedUsername);
      finalCredential = hmac.digest('base64');
      finalUsername = generatedUsername;
    }

    const turnUrls = config.TURN_SERVERS.split(',').map((u) => u.trim());

    const iceServers: RtcIceServer[] = [
      {
        urls: ['stun:stun.relay.metered.ca:80', 'stun:stun.l.google.com:19302'],
      },
      {
        urls: turnUrls,
        username: finalUsername,
        credential: finalCredential,
      },
    ];

    return {
      iceServers,
      expiresInSeconds: ttlSeconds,
    };
  }
}

export const turnService = new TurnService();
