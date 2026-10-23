import jwt from 'jsonwebtoken';
import { randomUUID } from 'node:crypto';
import type {
  UserProfileDto,
  SupabaseCustomJwtPayload,
  ExchangeTokenResponse,
} from '@connectx/shared-types';
import { config } from '../config.js';
import type { VerifiedFirebaseToken } from './firebase-verifier.js';

export class SupabaseBridge {
  // In-memory registry fallback for dev/testing when direct DB connection is not initialized
  private profilesByFirebaseUid: Map<string, UserProfileDto> = new Map();
  private profilesById: Map<string, UserProfileDto> = new Map();

  constructor() {
    // Seed default mock profiles in test mode
    if (config.NODE_ENV === 'test') {
      const testUserAId = 'a0000000-0000-0000-0000-000000000001';
      const testUserA: UserProfileDto = {
        id: testUserAId,
        firebaseUid: 'user-a',
        displayName: 'Alice Cooper',
        avatarAssetId: null,
        bio: 'Hello from Alice',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      this.profilesByFirebaseUid.set('user-a', testUserA);
      this.profilesById.set(testUserAId, testUserA);
    }
  }

  /**
   * Resolves an existing profile or provisions a new profile for a verified Firebase identity.
   */
  public async resolveOrCreateProfile(verified: VerifiedFirebaseToken): Promise<UserProfileDto> {
    const existing = this.profilesByFirebaseUid.get(verified.uid);
    if (existing) {
      return existing;
    }

    const newId = randomUUID();
    const newProfile: UserProfileDto = {
      id: newId,
      firebaseUid: verified.uid,
      displayName: verified.name || (verified.email ? verified.email.split('@')[0] : `User_${verified.uid.slice(0, 6)}`),
      avatarAssetId: null,
      bio: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };

    this.profilesByFirebaseUid.set(verified.uid, newProfile);
    this.profilesById.set(newId, newProfile);
    return newProfile;
  }

  /**
   * Generates a Supabase-compatible JWT for client authentication against PostgREST and Realtime.
   */
  public mintCustomSupabaseJwt(profile: UserProfileDto, email?: string): { token: string; expiresIn: number } {
    const expiresIn = 3600; // 1 hour
    const now = Math.floor(Date.now() / 1000);

    const payload: SupabaseCustomJwtPayload = {
      sub: profile.id, // Supabase auth.uid() uses the 'sub' claim
      firebase_uid: profile.firebaseUid,
      role: 'authenticated',
      aud: 'authenticated',
      email: email,
      iss: 'connectx-auth-bridge',
      iat: now,
      exp: now + expiresIn,
      app_metadata: {
        provider: 'firebase',
      },
      user_metadata: {
        display_name: profile.displayName,
      },
    };

    const token = jwt.sign(payload, config.SUPABASE_JWT_SECRET, {
      algorithm: 'HS256',
    });

    return { token, expiresIn };
  }

  /**
   * Complete token exchange pipeline.
   */
  public async exchangeFirebaseToken(verified: VerifiedFirebaseToken): Promise<ExchangeTokenResponse> {
    const profile = await this.resolveOrCreateProfile(verified);
    const { token, expiresIn } = this.mintCustomSupabaseJwt(profile, verified.email);

    return {
      accessToken: token,
      tokenType: 'Bearer',
      expiresIn,
      profile,
    };
  }

  /**
   * Verifies and decodes a Supabase custom JWT.
   */
  public verifySupabaseJwt(token: string): SupabaseCustomJwtPayload {
    const decoded = jwt.verify(token, config.SUPABASE_JWT_SECRET, {
      algorithms: ['HS256'],
      audience: 'authenticated',
    }) as SupabaseCustomJwtPayload;

    return decoded;
  }

  public getProfileById(id: string): UserProfileDto | null {
    return this.profilesById.get(id) || null;
  }
}

export const supabaseBridge = new SupabaseBridge();
