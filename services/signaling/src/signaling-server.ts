import { WebSocketServer, WebSocket } from 'ws';
import jwt from 'jsonwebtoken';
import dotenv from 'dotenv';
import type { IncomingMessage } from 'node:http';
import type {
  SignalingMessage,
  SignalingEventType,
  SupabaseCustomJwtPayload,
} from '@connectx/shared-types';

dotenv.config();

const JWT_SECRET = process.env.SUPABASE_JWT_SECRET || 'development-supabase-jwt-secret-at-least-32-chars!';

export interface AuthenticatedWebSocket extends WebSocket {
  userId: string;
  isAlive: boolean;
  activeCallId?: string;
}

export interface ActiveCallSession {
  callId: string;
  callerId: string;
  calleeId: string;
  callType: 'voice' | 'video';
  status: 'ringing' | 'active' | 'ended';
  screenSharingUserId?: string;
  startedAt: string;
}

export class SignalingServer {
  private wss: WebSocketServer | null = null;
  private clients: Map<string, AuthenticatedWebSocket> = new Map();
  private calls: Map<string, ActiveCallSession> = new Map();
  private pingInterval: NodeJS.Timeout | null = null;

  public start(port = 4001, host = '0.0.0.0'): WebSocketServer {
    this.wss = new WebSocketServer({ port, host });

    this.wss.on('connection', (ws: WebSocket, req: IncomingMessage) => {
      const authWs = ws as AuthenticatedWebSocket;
      authWs.isAlive = true;

      // Extract token from query: ws://host:port/ws?token=...
      const url = new URL(req.url || '', `http://${req.headers.host || 'localhost'}`);
      const token = url.searchParams.get('token');

      if (!token) {
        authWs.close(4001, 'UNAUTHORIZED: Missing auth token');
        return;
      }

      try {
        const decoded = jwt.verify(token, JWT_SECRET, {
          algorithms: ['HS256'],
          audience: 'authenticated',
        }) as SupabaseCustomJwtPayload;

        authWs.userId = decoded.sub;
        this.clients.set(authWs.userId, authWs);
      } catch (err) {
        authWs.close(4001, 'UNAUTHORIZED: Invalid token');
        return;
      }

      authWs.on('pong', () => {
        authWs.isAlive = true;
      });

      authWs.on('message', (data: Buffer | string) => {
        try {
          const raw = data.toString();
          const message = JSON.parse(raw) as SignalingMessage;
          this.handleSignalingMessage(authWs, message);
        } catch (err) {
          authWs.send(JSON.stringify({ error: 'INVALID_SIGNALING_PAYLOAD' }));
        }
      });

      authWs.on('close', () => {
        this.handleClientDisconnect(authWs);
      });
    });

    // Heartbeat check every 30 seconds
    this.pingInterval = setInterval(() => {
      this.wss?.clients.forEach((ws) => {
        const authWs = ws as AuthenticatedWebSocket;
        if (!authWs.isAlive) {
          authWs.terminate();
          return;
        }
        authWs.isAlive = false;
        authWs.ping();
      });
    }, 30000);

    return this.wss;
  }

  public stop(): Promise<void> {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
    return new Promise((resolve) => {
      if (this.wss) {
        this.wss.close(() => resolve());
      } else {
        resolve();
      }
    });
  }

  private handleSignalingMessage(sender: AuthenticatedWebSocket, message: SignalingMessage): void {
    const { type, callId, targetId } = message;

    // Sender identity must match authenticated session
    message.senderId = sender.userId;
    message.timestamp = new Date().toISOString();

    switch (type) {
      case 'call.invite': {
        const calleeId = targetId;
        if (!calleeId) {
          sender.send(JSON.stringify({ error: 'MISSING_TARGET_ID' }));
          return;
        }

        const calleeWs = this.clients.get(calleeId);
        if (!calleeWs) {
          sender.send(JSON.stringify({
            type: 'call.reject',
            callId,
            senderId: calleeId,
            payload: { reason: 'busy' },
            timestamp: new Date().toISOString(),
          }));
          return;
        }

        const newCall: ActiveCallSession = {
          callId,
          callerId: sender.userId,
          calleeId,
          callType: (message.payload as { callType?: 'voice' | 'video' })?.callType || 'video',
          status: 'ringing',
          startedAt: new Date().toISOString(),
        };
        this.calls.set(callId, newCall);
        sender.activeCallId = callId;
        calleeWs.activeCallId = callId;

        // Forward invitation to callee
        calleeWs.send(JSON.stringify(message));
        break;
      }

      case 'call.accept': {
        const call = this.calls.get(callId);
        if (call) {
          call.status = 'active';
          const callerWs = this.clients.get(call.callerId);
          callerWs?.send(JSON.stringify(message));
        }
        break;
      }

      case 'call.reject':
      case 'call.cancel':
      case 'call.end': {
        const call = this.calls.get(callId);
        if (call) {
          call.status = 'ended';
          const remoteId = call.callerId === sender.userId ? call.calleeId : call.callerId;
          const remoteWs = this.clients.get(remoteId);
          remoteWs?.send(JSON.stringify(message));
          this.calls.delete(callId);
          sender.activeCallId = undefined;
          if (remoteWs) remoteWs.activeCallId = undefined;
        }
        break;
      }

      case 'webrtc.offer':
      case 'webrtc.answer':
      case 'webrtc.ice_candidate': {
        const call = this.calls.get(callId);
        if (!call) return;
        const remoteId = call.callerId === sender.userId ? call.calleeId : call.callerId;
        const remoteWs = this.clients.get(remoteId);
        remoteWs?.send(JSON.stringify(message));
        break;
      }

      case 'screen_share.started': {
        const call = this.calls.get(callId);
        if (call) {
          call.screenSharingUserId = sender.userId;
          const remoteId = call.callerId === sender.userId ? call.calleeId : call.callerId;
          const remoteWs = this.clients.get(remoteId);
          remoteWs?.send(JSON.stringify(message));
        }
        break;
      }

      case 'screen_share.stopped': {
        const call = this.calls.get(callId);
        if (call && call.screenSharingUserId === sender.userId) {
          call.screenSharingUserId = undefined;
          const remoteId = call.callerId === sender.userId ? call.calleeId : call.callerId;
          const remoteWs = this.clients.get(remoteId);
          remoteWs?.send(JSON.stringify(message));
        }
        break;
      }

      case 'participant.mute_changed':
      case 'participant.camera_changed': {
        const call = this.calls.get(callId);
        if (!call) return;
        const remoteId = call.callerId === sender.userId ? call.calleeId : call.callerId;
        const remoteWs = this.clients.get(remoteId);
        remoteWs?.send(JSON.stringify(message));
        break;
      }
    }
  }

  private handleClientDisconnect(ws: AuthenticatedWebSocket): void {
    if (ws.userId) {
      this.clients.delete(ws.userId);
    }
    if (ws.activeCallId) {
      const call = this.calls.get(ws.activeCallId);
      if (call) {
        const remoteId = call.callerId === ws.userId ? call.calleeId : call.callerId;
        const remoteWs = this.clients.get(remoteId);
        remoteWs?.send(JSON.stringify({
          type: 'call.end',
          callId: ws.activeCallId,
          senderId: ws.userId,
          payload: { reason: 'failed' },
          timestamp: new Date().toISOString(),
        }));
        this.calls.delete(ws.activeCallId);
      }
    }
  }

  public getActiveCallsCount(): number {
    return this.calls.size;
  }

  public getConnectedClientsCount(): number {
    return this.clients.size;
  }
}

// Standalone runner if not imported
if (process.env.NODE_ENV !== 'test') {
  const server = new SignalingServer();
  const PORT = Number(process.env.SIGNALING_PORT) || 4001;
  const HOST = process.env.SIGNALING_HOST || '0.0.0.0';
  server.start(PORT, HOST);
  console.log(`ConnectX Signaling Server running on ws://${HOST}:${PORT}`);
}
