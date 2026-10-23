export interface ExchangeTokenRequest {
  idToken: string;
}

export interface UserProfileDto {
  id: string;
  firebaseUid: string;
  displayName: string;
  avatarAssetId: string | null;
  bio: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UserSettingsDto {
  userId: string;
  allowMessagesFrom: 'everyone' | 'contacts_only';
  showPresence: boolean;
  readReceiptsEnabled: boolean;
  notificationPreferences: {
    messages: boolean;
    calls: boolean;
    previewText: boolean;
  };
  updatedAt: string;
}

export interface ExchangeTokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  profile: UserProfileDto;
  settings?: UserSettingsDto;
}

export interface SupabaseCustomJwtPayload {
  sub: string; // The Profile UUID
  firebase_uid: string;
  role: 'authenticated';
  aud: 'authenticated';
  email?: string;
  phone_number?: string;
  iss: string;
  iat: number;
  exp: number;
  app_metadata?: Record<string, unknown>;
  user_metadata?: Record<string, unknown>;
}
