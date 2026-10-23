export interface VerifiedFirebaseToken {
  uid: string;
  email?: string;
  name?: string;
  phoneNumber?: string;
}

export class FirebaseVerifier {
  private isConfigured: boolean;

  constructor() {
    this.isConfigured = Boolean(
      process.env.FIREBASE_SERVICE_ACCOUNT_PATH ||
      process.env.FIREBASE_CONFIGURED === 'true'
    );
  }

  public getStatus(): { isConfigured: boolean } {
    return { isConfigured: this.isConfigured };
  }

  /**
   * Verifies a Firebase ID token.
   * Throws an error if the token is forged, expired, or invalid.
   */
  public async verifyToken(idToken: string): Promise<VerifiedFirebaseToken> {
    if (!idToken || typeof idToken !== 'string') {
      throw new Error('ID_TOKEN_REQUIRED');
    }

    // In unit test or development test sandbox: support test tokens
    if (process.env.NODE_ENV === 'test') {
      if (idToken.startsWith('test-token-valid-')) {
        const uid = idToken.replace('test-token-valid-', '');
        return {
          uid,
          email: `${uid}@test.connectx.app`,
          name: `User ${uid.toUpperCase()}`,
        };
      }
      if (idToken === 'test-token-invalid') {
        throw new Error('INVALID_TOKEN');
      }
      if (idToken === 'test-token-expired') {
        throw new Error('TOKEN_EXPIRED');
      }
    }

    if (!this.isConfigured) {
      throw new Error('FIREBASE_NOT_CONFIGURED: Please provide FIREBASE_SERVICE_ACCOUNT_PATH in .env');
    }

    // In production with Firebase Admin SDK:
    // const decoded = await admin.auth().verifyIdToken(idToken);
    // return { uid: decoded.uid, email: decoded.email, name: decoded.name };
    throw new Error('FIREBASE_VERIFICATION_PENDING_RUNTIME');
  }
}

export const firebaseVerifier = new FirebaseVerifier();
