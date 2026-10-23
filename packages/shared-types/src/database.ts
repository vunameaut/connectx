export type UUID = string;

export interface ProfileRow {
  id: UUID;
  firebase_uid: string;
  display_name: string;
  avatar_asset_id: string | null;
  bio: string | null;
  created_at: string;
  updated_at: string;
}

export interface UserSettingsRow {
  user_id: UUID;
  allow_messages_from: 'everyone' | 'contacts_only';
  show_presence: boolean;
  read_receipts_enabled: boolean;
  notification_preferences: {
    messages: boolean;
    calls: boolean;
    preview_text: boolean;
  };
  updated_at: string;
}

export interface ContactRow {
  owner_id: UUID;
  contact_id: UUID;
  status: 'pending' | 'accepted' | 'blocked';
  created_at: string;
}

export interface ConversationRow {
  id: UUID;
  kind: 'direct' | 'group';
  title: string | null;
  created_by: UUID | null;
  created_at: string;
  updated_at: string;
}

export interface ConversationMemberRow {
  conversation_id: UUID;
  user_id: UUID;
  role: 'owner' | 'admin' | 'member';
  joined_at: string;
  last_read_message_id: UUID | null;
}

export interface MessageRow {
  id: UUID;
  conversation_id: UUID;
  sender_id: UUID;
  message_type: 'text' | 'image' | 'video' | 'file' | 'system';
  text_body: string | null;
  idempotency_key: string | null;
  reply_to_message_id: UUID | null;
  created_at: string;
  edited_at: string | null;
  deleted_at: string | null;
}

export interface MessageAttachmentRow {
  id: UUID;
  message_id: UUID;
  provider: 'cloudinary';
  asset_id: string;
  resource_type: 'image' | 'video' | 'raw';
  mime_type: string | null;
  size_bytes: number | null;
  width: number | null;
  height: number | null;
  duration_ms: number | null;
  created_at: string;
}

export interface CallRow {
  id: UUID;
  conversation_id: UUID | null;
  initiated_by: UUID;
  call_type: 'voice' | 'video';
  status: 'initiated' | 'ringing' | 'active' | 'ended' | 'missed' | 'rejected' | 'busy';
  started_at: string | null;
  ended_at: string | null;
  end_reason: string | null;
}

export interface CallParticipantRow {
  call_id: UUID;
  user_id: UUID;
  joined_at: string | null;
  left_at: string | null;
  role: 'caller' | 'callee';
}

export interface DeviceRow {
  id: UUID;
  user_id: UUID;
  push_token: string;
  platform: 'android' | 'ios';
  updated_at: string;
}
