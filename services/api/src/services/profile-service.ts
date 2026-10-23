import type { UserProfileDto, ContactRow, UserSettingsDto } from '@connectx/shared-types';
import { supabaseBridge } from './supabase-bridge.js';

export class ProfileService {
  private contacts: Map<string, ContactRow[]> = new Map(); // ownerId -> contacts
  private blockedUsers: Map<string, Set<string>> = new Map(); // blockerId -> Set(blockedId)
  private settings: Map<string, UserSettingsDto> = new Map();

  public async updateProfile(userId: string, data: { displayName?: string; bio?: string; avatarAssetId?: string | null }): Promise<UserProfileDto> {
    const profile = supabaseBridge.getProfileById(userId);
    if (!profile) {
      throw new Error('PROFILE_NOT_FOUND');
    }

    if (data.displayName !== undefined) {
      if (data.displayName.trim().length === 0 || data.displayName.length > 64) {
        throw new Error('INVALID_DISPLAY_NAME: Must be between 1 and 64 characters');
      }
      profile.displayName = data.displayName.trim();
    }
    if (data.bio !== undefined) {
      if (data.bio && data.bio.length > 256) {
        throw new Error('BIO_TOO_LONG: Must be 256 characters or less');
      }
      profile.bio = data.bio;
    }
    if (data.avatarAssetId !== undefined) {
      profile.avatarAssetId = data.avatarAssetId;
    }

    profile.updatedAt = new Date().toISOString();
    return profile;
  }

  /**
   * Search users with data minimization.
   * NEVER returns email, phone numbers, or private metadata.
   */
  public async searchUsers(query: string, currentUserId: string): Promise<Pick<UserProfileDto, 'id' | 'displayName' | 'avatarAssetId' | 'bio'>[]> {
    if (!query || query.trim().length < 2) {
      return [];
    }

    const q = query.toLowerCase().trim();
    const results: Pick<UserProfileDto, 'id' | 'displayName' | 'avatarAssetId' | 'bio'>[] = [];

    // Query profiles in bridge
    // @ts-expect-error accessing internal registry for search in mock/dev mode
    const allProfiles = Array.from(supabaseBridge.profilesById.values()) as UserProfileDto[];

    for (const p of allProfiles) {
      if (p.id === currentUserId) continue; // Skip self

      const isBlocked = this.blockedUsers.get(currentUserId)?.has(p.id) ||
                        this.blockedUsers.get(p.id)?.has(currentUserId);
      if (isBlocked) continue;

      if (p.displayName.toLowerCase().includes(q)) {
        results.push({
          id: p.id,
          displayName: p.displayName,
          avatarAssetId: p.avatarAssetId,
          bio: p.bio,
        });
      }
    }

    return results;
  }

  public async addContact(ownerId: string, contactId: string): Promise<ContactRow> {
    if (ownerId === contactId) {
      throw new Error('CANNOT_ADD_SELF_AS_CONTACT');
    }

    const contactProfile = supabaseBridge.getProfileById(contactId);
    if (!contactProfile) {
      throw new Error('CONTACT_USER_NOT_FOUND');
    }

    const currentContacts = this.contacts.get(ownerId) || [];
    const existing = currentContacts.find((c) => c.contact_id === contactId);
    if (existing) {
      return existing;
    }

    const newContact: ContactRow = {
      owner_id: ownerId,
      contact_id: contactId,
      status: 'accepted',
      created_at: new Date().toISOString(),
    };

    currentContacts.push(newContact);
    this.contacts.set(ownerId, currentContacts);
    return newContact;
  }

  public async getContacts(ownerId: string): Promise<ContactRow[]> {
    return this.contacts.get(ownerId) || [];
  }

  public async blockUser(blockerId: string, blockedId: string): Promise<void> {
    if (blockerId === blockedId) {
      throw new Error('CANNOT_BLOCK_SELF');
    }
    const current = this.blockedUsers.get(blockerId) || new Set();
    current.add(blockedId);
    this.blockedUsers.set(blockerId, current);

    // Remove from contacts if present
    const contacts = this.contacts.get(blockerId) || [];
    this.contacts.set(blockerId, contacts.filter((c) => c.contact_id !== blockedId));
  }

  public async unblockUser(blockerId: string, blockedId: string): Promise<void> {
    const current = this.blockedUsers.get(blockerId);
    if (current) {
      current.delete(blockedId);
    }
  }

  public isBlocked(userA: string, userB: string): boolean {
    return Boolean(this.blockedUsers.get(userA)?.has(userB) || this.blockedUsers.get(userB)?.has(userA));
  }
}

export const profileService = new ProfileService();
