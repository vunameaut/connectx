export type SignalingEventType =
  | 'call.invite'
  | 'call.ringing'
  | 'call.accept'
  | 'call.reject'
  | 'call.cancel'
  | 'call.end'
  | 'webrtc.offer'
  | 'webrtc.answer'
  | 'webrtc.ice_candidate'
  | 'screen_share.started'
  | 'screen_share.stopped'
  | 'participant.mute_changed'
  | 'participant.camera_changed';

export interface SignalingMessage<T = unknown> {
  type: SignalingEventType;
  callId: string;
  senderId: string;
  targetId?: string;
  payload: T;
  timestamp: string;
}

export interface CallInvitePayload {
  conversationId?: string;
  callType: 'voice' | 'video';
  callerName: string;
  callerAvatarUrl?: string;
}

export interface CallAcceptPayload {
  acceptedAt: string;
}

export interface CallRejectPayload {
  reason: 'busy' | 'declined' | 'timeout';
}

export interface CallEndPayload {
  reason: 'completed' | 'canceled' | 'rejected' | 'failed' | 'timeout';
  durationSeconds?: number;
}

export interface WebRtcOfferPayload {
  sdp: string;
  type: 'offer';
}

export interface WebRtcAnswerPayload {
  sdp: string;
  type: 'answer';
}

export interface WebRtcIceCandidatePayload {
  candidate: string;
  sdpMid?: string | null;
  sdpMLineIndex?: number | null;
}

export interface ScreenShareStartedPayload {
  streamId: string;
  width: number;
  height: number;
  frameRate: number;
}

export interface ScreenShareStoppedPayload {
  reason: 'user_stopped' | 'os_stopped' | 'call_ended';
}

export interface ParticipantMuteChangedPayload {
  audioMuted: boolean;
  videoMuted: boolean;
}
