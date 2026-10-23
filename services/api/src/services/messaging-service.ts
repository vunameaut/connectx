import { randomUUID } from 'node:crypto';
import type {
  ConversationRow,
  ConversationMemberRow,
  MessageRow,
  MessageAttachmentRow,
} from '@connectx/shared-types';

export interface CreateConversationParams {
  creatorId: string;
  kind: 'direct' | 'group';
  participantIds: string[];
  title?: string;
}

export interface SendMessageParams {
  conversationId: string;
  senderId: string;
  messageType: 'text' | 'image' | 'video' | 'file';
  textBody?: string;
  idempotencyKey?: string;
  replyToMessageId?: string;
  attachment?: {
    assetId: string;
    resourceType: 'image' | 'video' | 'raw';
    mimeType?: string;
    sizeBytes?: number;
    width?: number;
    height?: number;
    durationMs?: number;
  };
}

export class MessagingService {
  private conversations: Map<string, ConversationRow> = new Map();
  private members: Map<string, ConversationMemberRow[]> = new Map(); // convId -> members
  private messages: Map<string, MessageRow[]> = new Map(); // convId -> messages sorted chronological
  private attachments: Map<string, MessageAttachmentRow[]> = new Map(); // messageId -> attachments
  private idempotencyIndex: Map<string, MessageRow> = new Map(); // idemKey -> message

  public async createConversation(params: CreateConversationParams): Promise<ConversationRow> {
    const { creatorId, kind, participantIds, title } = params;
    const allMembers = Array.from(new Set([creatorId, ...participantIds]));

    if (kind === 'direct') {
      if (allMembers.length !== 2) {
        throw new Error('DIRECT_CONVERSATION_REQUIRES_TWO_MEMBERS');
      }
      // Check if direct conversation already exists between these 2 users
      for (const [convId, memberList] of this.members.entries()) {
        const conv = this.conversations.get(convId);
        if (conv?.kind === 'direct' && memberList.length === 2) {
          const uids = memberList.map((m) => m.user_id);
          if (uids.includes(allMembers[0]!) && uids.includes(allMembers[1]!)) {
            return conv;
          }
        }
      }
    } else if (kind === 'group') {
      if (!title || title.trim().length === 0) {
        throw new Error('GROUP_TITLE_REQUIRED');
      }
    }

    const convId = randomUUID();
    const now = new Date().toISOString();

    const conv: ConversationRow = {
      id: convId,
      kind,
      title: kind === 'group' ? title?.trim() || null : null,
      created_by: creatorId,
      created_at: now,
      updated_at: now,
    };

    const memberRows: ConversationMemberRow[] = allMembers.map((uid) => ({
      conversation_id: convId,
      user_id: uid,
      role: uid === creatorId && kind === 'group' ? 'owner' : 'member',
      joined_at: now,
      last_read_message_id: null,
    }));

    this.conversations.set(convId, conv);
    this.members.set(convId, memberRows);
    this.messages.set(convId, []);

    return conv;
  }

  public isMember(conversationId: string, userId: string): boolean {
    const members = this.members.get(conversationId) || [];
    return members.some((m) => m.user_id === userId);
  }

  public async listUserConversations(userId: string): Promise<ConversationRow[]> {
    const result: ConversationRow[] = [];
    for (const [convId, memberList] of this.members.entries()) {
      if (memberList.some((m) => m.user_id === userId)) {
        const conv = this.conversations.get(convId);
        if (conv) result.push(conv);
      }
    }
    return result;
  }

  public async sendMessage(params: SendMessageParams): Promise<{ message: MessageRow; attachment?: MessageAttachmentRow }> {
    const { conversationId, senderId, messageType, textBody, idempotencyKey, replyToMessageId, attachment } = params;

    if (!this.isMember(conversationId, senderId)) {
      throw new Error('FORBIDDEN_NOT_A_CONVERSATION_MEMBER');
    }

    // Deduplication check via idempotencyKey
    if (idempotencyKey && this.idempotencyIndex.has(idempotencyKey)) {
      const existing = this.idempotencyIndex.get(idempotencyKey)!;
      const existingAtt = this.attachments.get(existing.id)?.[0];
      return { message: existing, attachment: existingAtt };
    }

    if (messageType === 'text' && (!textBody || textBody.trim().length === 0)) {
      throw new Error('EMPTY_TEXT_MESSAGE');
    }

    const messageId = randomUUID();
    const now = new Date().toISOString();

    const message: MessageRow = {
      id: messageId,
      conversation_id: conversationId,
      sender_id: senderId,
      message_type: messageType,
      text_body: textBody?.trim() || null,
      idempotency_key: idempotencyKey || null,
      reply_to_message_id: replyToMessageId || null,
      created_at: now,
      edited_at: null,
      deleted_at: null,
    };

    let attachmentRow: MessageAttachmentRow | undefined;
    if (attachment) {
      attachmentRow = {
        id: randomUUID(),
        message_id: messageId,
        provider: 'cloudinary',
        asset_id: attachment.assetId,
        resource_type: attachment.resourceType,
        mime_type: attachment.mimeType || null,
        size_bytes: attachment.sizeBytes || null,
        width: attachment.width || null,
        height: attachment.height || null,
        duration_ms: attachment.durationMs || null,
        created_at: now,
      };
      this.attachments.set(messageId, [attachmentRow]);
    }

    const msgList = this.messages.get(conversationId) || [];
    msgList.push(message);
    this.messages.set(conversationId, msgList);

    if (idempotencyKey) {
      this.idempotencyIndex.set(idempotencyKey, message);
    }

    return { message, attachment: attachmentRow };
  }

  /**
   * Retrieves messages for a conversation with cursor pagination.
   */
  public async getMessages(conversationId: string, callerId: string, limit = 50, beforeTimestamp?: string): Promise<MessageRow[]> {
    if (!this.isMember(conversationId, callerId)) {
      throw new Error('FORBIDDEN_NOT_A_CONVERSATION_MEMBER');
    }

    const msgList = this.messages.get(conversationId) || [];
    let filtered = msgList;

    if (beforeTimestamp) {
      const beforeTime = new Date(beforeTimestamp).getTime();
      filtered = filtered.filter((m) => new Date(m.created_at).getTime() < beforeTime);
    }

    // Return most recent messages up to limit
    return filtered.slice(-limit);
  }

  public async editMessage(messageId: string, callerId: string, newText: string): Promise<MessageRow> {
    for (const msgList of this.messages.values()) {
      const msg = msgList.find((m) => m.id === messageId);
      if (msg) {
        if (msg.sender_id !== callerId) {
          throw new Error('FORBIDDEN_CANNOT_EDIT_OTHERS_MESSAGE');
        }
        if (msg.deleted_at) {
          throw new Error('CANNOT_EDIT_DELETED_MESSAGE');
        }

        // Limit edits to 15 minutes
        const diffMinutes = (Date.now() - new Date(msg.created_at).getTime()) / 60000;
        if (diffMinutes > 15) {
          throw new Error('EDIT_WINDOW_EXPIRED: Messages can only be edited within 15 minutes');
        }

        msg.text_body = newText.trim();
        msg.edited_at = new Date().toISOString();
        return msg;
      }
    }
    throw new Error('MESSAGE_NOT_FOUND');
  }

  public async softDeleteMessage(messageId: string, callerId: string): Promise<MessageRow> {
    for (const msgList of this.messages.values()) {
      const msg = msgList.find((m) => m.id === messageId);
      if (msg) {
        if (msg.sender_id !== callerId) {
          throw new Error('FORBIDDEN_CANNOT_DELETE_OTHERS_MESSAGE');
        }
        msg.deleted_at = new Date().toISOString();
        msg.text_body = 'This message was deleted';
        return msg;
      }
    }
    throw new Error('MESSAGE_NOT_FOUND');
  }
}

export const messagingService = new MessagingService();
