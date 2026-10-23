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
    const username = `${expiryTimestamp}:${userId}`;

    const hmac = crypto.createHmac('sha1', config.TURN_SECRET);
    hmac.update(username);
    const credential = hmac.digest('base64');

    const iceServers: RtcIceServer[] = [
      {
        urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'],
      },
      {
        urls: config.TURN_SERVERS.split(','),
        username,
        credential,
      },
    ];

    return {
      iceServers,
      expiresInSeconds: ttlSeconds,
    };
  }
}

export const turnService = new TurnService();
