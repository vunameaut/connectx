import { createStore } from 'zustand/vanilla';
import type { UserProfileDto, MessageRow, ConversationRow, CallRow } from '@connectx/shared-types';

export interface AuthState {
  isAuthenticated: boolean;
  accessToken: string | null;
  profile: UserProfileDto | null;
  setSession: (token: string, profile: UserProfileDto) => void;
  clearSession: () => void;
}

export interface MessagingState {
  activeConversationId: string | null;
  conversations: ConversationRow[];
  messagesByConversation: Record<string, MessageRow[]>;
  setActiveConversation: (id: string | null) => void;
  setConversations: (conversations: ConversationRow[]) => void;
  addMessage: (message: MessageRow) => void;
  updateMessageStatus: (idempotencyKey: string, updated: Partial<MessageRow>) => void;
}

export interface CallState {
  activeCall: CallRow | null;
  isAudioMuted: boolean;
  isVideoMuted: boolean;
  isScreenSharing: boolean;
  remoteParticipantId: string | null;
  startCall: (call: CallRow, remoteId: string) => void;
  setAudioMuted: (muted: boolean) => void;
  setVideoMuted: (muted: boolean) => void;
  setScreenSharing: (sharing: boolean) => void;
  endCall: () => void;
}

export type AppStore = AuthState & MessagingState & CallState;

export const appStore = createStore<AppStore>((set) => ({
  // Auth Slice
  isAuthenticated: false,
  accessToken: null,
  profile: null,
  setSession: (token, profile) =>
    set({
      isAuthenticated: true,
      accessToken: token,
      profile,
    }),
  clearSession: () =>
    set({
      isAuthenticated: false,
      accessToken: null,
      profile: null,
      activeConversationId: null,
      activeCall: null,
    }),

  // Messaging Slice
  activeConversationId: null,
  conversations: [],
  messagesByConversation: {},
  setActiveConversation: (id) => set({ activeConversationId: id }),
  setConversations: (conversations) => set({ conversations }),
  addMessage: (message) =>
    set((state) => {
      const convId = message.conversation_id;
      const current = state.messagesByConversation[convId] || [];
      // Idempotency check: don't add duplicate message
      if (current.some((m) => m.id === message.id || (message.idempotency_key && m.idempotency_key === message.idempotency_key))) {
        return state;
      }
      return {
        messagesByConversation: {
          ...state.messagesByConversation,
          [convId]: [...current, message],
        },
      };
    }),
  updateMessageStatus: (idempotencyKey, updated) =>
    set((state) => {
      const updatedMessagesByConv: Record<string, MessageRow[]> = {};
      for (const [convId, list] of Object.entries(state.messagesByConversation)) {
        updatedMessagesByConv[convId] = list.map((msg) =>
          msg.idempotency_key === idempotencyKey ? { ...msg, ...updated } : msg
        );
      }
      return { messagesByConversation: updatedMessagesByConv };
    }),

  // Call Slice
  activeCall: null,
  isAudioMuted: false,
  isVideoMuted: false,
  isScreenSharing: false,
  remoteParticipantId: null,
  startCall: (call, remoteId) =>
    set({
      activeCall: call,
      remoteParticipantId: remoteId,
      isAudioMuted: false,
      isVideoMuted: false,
      isScreenSharing: false,
    }),
  setAudioMuted: (muted) => set({ isAudioMuted: muted }),
  setVideoMuted: (muted) => set({ isVideoMuted: muted }),
  setScreenSharing: (sharing) => set({ isScreenSharing: sharing }),
  endCall: () =>
    set({
      activeCall: null,
      remoteParticipantId: null,
      isAudioMuted: false,
      isVideoMuted: false,
      isScreenSharing: false,
    }),
}));
