import Fastify, { type FastifyInstance, type FastifyRequest, type FastifyReply } from 'fastify';
import cors from '@fastify/cors';
import { z } from 'zod';
import { config } from './config.js';
import { firebaseVerifier } from './services/firebase-verifier.js';
import { supabaseBridge } from './services/supabase-bridge.js';
import { cloudinarySigner } from './services/cloudinary-signer.js';
import { turnService } from './services/turn-service.js';
import { profileService } from './services/profile-service.js';
import { messagingService } from './services/messaging-service.js';

export async function buildServer(): Promise<FastifyInstance> {
  const server = Fastify({
    logger: config.NODE_ENV !== 'test',
  });

  await server.register(cors, {
    origin: '*',
  });

  // Global Error Handler
  server.setErrorHandler((error, request, reply) => {
    server.log.error(error);
    const message = error instanceof Error ? error.message : 'INTERNAL_SERVER_ERROR';
    reply.status(500).send({ error: 'INTERNAL_ERROR', message });
  });

  // Health check
  server.get('/health', async () => {
    return {
      status: 'ok',
      service: 'connectx-api',
      environment: config.NODE_ENV,
      firebaseStatus: firebaseVerifier.getStatus(),
      timestamp: new Date().toISOString(),
    };
  });

  // App Version & In-App Auto-Update Endpoint (Mandatory Force Update)
  server.get('/api/v1/app/version', async () => {
    return {
      versionCode: 3,
      versionName: '1.0.2',
      minSupportedVersion: '1.0.2',
      updateRequired: true,
      forceUpdate: true,
      releaseNotes: 'Bản cập nhật bắt buộc v1.0.2: Hoàn thiện luồng kết bạn 2 chiều (gửi lời mời & đồng ý), đồng bộ tin nhắn hai chiều thời gian thực, gửi ảnh thật từ thư viện máy, đầy đủ icon đa phương tiện và chọn người trước khi gọi.',
      downloadUrl: 'https://github.com/vunameaut/connectx/releases/download/v1.0.2/ConnectX-v1.0.2.apk',
      publishedAt: new Date().toISOString(),
    };
  });

  // 1. Auth Bridge: Exchange Firebase ID Token for Supabase Custom JWT
  const exchangeTokenSchema = z.object({
    idToken: z.string().min(1, 'idToken is required'),
  });

  server.post('/api/v1/auth/exchange-token', async (request: FastifyRequest, reply: FastifyReply) => {
    const parseResult = exchangeTokenSchema.safeParse(request.body);
    if (!parseResult.success) {
      return reply.status(400).send({
        error: 'VALIDATION_ERROR',
        message: parseResult.error.errors[0]?.message || 'Invalid request body',
      });
    }

    try {
      const verified = await firebaseVerifier.verifyToken(parseResult.data.idToken);
      const session = await supabaseBridge.exchangeFirebaseToken(verified);
      return reply.status(200).send(session);
    } catch (err: unknown) {
      const errorMsg = err instanceof Error ? err.message : 'VERIFICATION_FAILED';
      if (errorMsg.includes('INVALID_TOKEN') || errorMsg.includes('TOKEN_EXPIRED') || errorMsg.includes('FIREBASE_NOT_CONFIGURED')) {
        return reply.status(401).send({ error: 'UNAUTHORIZED', message: errorMsg });
      }
      return reply.status(500).send({ error: 'AUTH_EXCHANGE_FAILED', message: errorMsg });
    }
  });

  // Middleware helper to authenticate Supabase Custom JWT
  const authenticate = async (request: FastifyRequest, reply: FastifyReply) => {
    const authHeader = request.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      reply.status(401).send({ error: 'UNAUTHORIZED', message: 'Missing or malformed Authorization header' });
      return null;
    }
    const token = authHeader.slice(7);
    try {
      const decoded = supabaseBridge.verifySupabaseJwt(token);
      return decoded;
    } catch (err) {
      reply.status(401).send({ error: 'UNAUTHORIZED', message: 'Invalid or expired Supabase session token' });
      return null;
    }
  };

  // 2. Profile & Settings
  server.get('/api/v1/profiles/me', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const profile = supabaseBridge.getProfileById(user.sub);
    if (!profile) {
      return reply.status(404).send({ error: 'PROFILE_NOT_FOUND', message: 'Profile record does not exist' });
    }

    return reply.status(200).send({ profile });
  });

  const updateProfileSchema = z.object({
    displayName: z.string().min(1).max(64).optional(),
    bio: z.string().max(256).optional(),
    avatarAssetId: z.string().nullable().optional(),
  });

  server.patch('/api/v1/profiles/me', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const parseResult = updateProfileSchema.safeParse(request.body);
    if (!parseResult.success) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: parseResult.error.errors[0]?.message });
    }

    try {
      const updated = await profileService.updateProfile(user.sub, parseResult.data);
      return reply.status(200).send({ profile: updated });
    } catch (err) {
      return reply.status(400).send({ error: 'UPDATE_FAILED', message: (err as Error).message });
    }
  });

  // 3. User Search (Strict data minimization: NO email or phone exposed)
  server.get('/api/v1/users/search', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const q = (request.query as { q?: string }).q || '';
    const results = await profileService.searchUsers(q, user.sub);
    return reply.status(200).send({ users: results });
  });

  // 4. Contacts
  server.post('/api/v1/contacts', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const body = request.body as { contactId?: string };
    if (!body.contactId) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'contactId is required' });
    }

    try {
      const contact = await profileService.addContact(user.sub, body.contactId);
      return reply.status(201).send({ contact });
    } catch (err) {
      return reply.status(400).send({ error: 'CONTACT_ERROR', message: (err as Error).message });
    }
  });

  server.get('/api/v1/contacts', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const contacts = await profileService.getContacts(user.sub);
    return reply.status(200).send({ contacts });
  });

  server.post('/api/v1/contacts/block', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const body = request.body as { userId?: string };
    if (!body.userId) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'userId is required' });
    }

    try {
      await profileService.blockUser(user.sub, body.userId);
      return reply.status(200).send({ success: true });
    } catch (err) {
      return reply.status(400).send({ error: 'BLOCK_ERROR', message: (err as Error).message });
    }
  });

  // 5. Conversations
  const createConversationSchema = z.object({
    kind: z.enum(['direct', 'group']),
    participantIds: z.array(z.string().uuid()),
    title: z.string().optional(),
  });

  server.post('/api/v1/conversations', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const parseResult = createConversationSchema.safeParse(request.body);
    if (!parseResult.success) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: parseResult.error.errors[0]?.message });
    }

    try {
      const conv = await messagingService.createConversation({
        creatorId: user.sub,
        kind: parseResult.data.kind,
        participantIds: parseResult.data.participantIds,
        title: parseResult.data.title,
      });
      return reply.status(201).send({ conversation: conv });
    } catch (err) {
      return reply.status(400).send({ error: 'CONVERSATION_CREATE_FAILED', message: (err as Error).message });
    }
  });

  server.get('/api/v1/conversations', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const list = await messagingService.listUserConversations(user.sub);
    return reply.status(200).send({ conversations: list });
  });

  // 6. Messages
  server.get('/api/v1/conversations/:id/messages', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const { id } = request.params as { id: string };
    const query = request.query as { limit?: string; before?: string };
    const limit = query.limit ? Number(query.limit) : 50;

    try {
      const messages = await messagingService.getMessages(id, user.sub, limit, query.before);
      return reply.status(200).send({ messages });
    } catch (err) {
      const msg = (err as Error).message;
      if (msg.includes('FORBIDDEN')) {
        return reply.status(403).send({ error: 'FORBIDDEN', message: msg });
      }
      return reply.status(400).send({ error: 'FETCH_MESSAGES_FAILED', message: msg });
    }
  });

  const sendMessageSchema = z.object({
    messageType: z.enum(['text', 'image', 'video', 'file']),
    textBody: z.string().optional(),
    idempotencyKey: z.string().optional(),
    replyToMessageId: z.string().uuid().optional(),
    attachment: z.object({
      assetId: z.string(),
      resourceType: z.enum(['image', 'video', 'raw']),
      mimeType: z.string().optional(),
      sizeBytes: z.number().optional(),
      width: z.number().optional(),
      height: z.number().optional(),
      durationMs: z.number().optional(),
    }).optional(),
  });

  server.post('/api/v1/conversations/:id/messages', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const { id } = request.params as { id: string };
    const parseResult = sendMessageSchema.safeParse(request.body);
    if (!parseResult.success) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: parseResult.error.errors[0]?.message });
    }

    try {
      const result = await messagingService.sendMessage({
        conversationId: id,
        senderId: user.sub,
        ...parseResult.data,
      });
      return reply.status(201).send(result);
    } catch (err) {
      const msg = (err as Error).message;
      if (msg.includes('FORBIDDEN')) {
        return reply.status(403).send({ error: 'FORBIDDEN', message: msg });
      }
      return reply.status(400).send({ error: 'SEND_MESSAGE_FAILED', message: msg });
    }
  });

  server.patch('/api/v1/messages/:id', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const { id } = request.params as { id: string };
    const body = request.body as { textBody?: string };
    if (!body.textBody) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'textBody is required' });
    }

    try {
      const updated = await messagingService.editMessage(id, user.sub, body.textBody);
      return reply.status(200).send({ message: updated });
    } catch (err) {
      const msg = (err as Error).message;
      if (msg.includes('FORBIDDEN')) return reply.status(403).send({ error: 'FORBIDDEN', message: msg });
      return reply.status(400).send({ error: 'EDIT_FAILED', message: msg });
    }
  });

  server.delete('/api/v1/messages/:id', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const { id } = request.params as { id: string };
    try {
      const deleted = await messagingService.softDeleteMessage(id, user.sub);
      return reply.status(200).send({ message: deleted });
    } catch (err) {
      const msg = (err as Error).message;
      if (msg.includes('FORBIDDEN')) return reply.status(403).send({ error: 'FORBIDDEN', message: msg });
      return reply.status(400).send({ error: 'DELETE_FAILED', message: msg });
    }
  });

  // 7. Media: Sign Cloudinary Upload
  const signUploadSchema = z.object({
    conversationId: z.string().uuid(),
    resourceType: z.enum(['image', 'video', 'raw']),
    mimeType: z.string().min(1),
    sizeBytes: z.number().positive(),
  });

  server.post('/api/v1/media/sign-upload', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const parseResult = signUploadSchema.safeParse(request.body);
    if (!parseResult.success) {
      return reply.status(400).send({
        error: 'VALIDATION_ERROR',
        message: parseResult.error.errors[0]?.message || 'Invalid parameters',
      });
    }

    try {
      const ticket = cloudinarySigner.signUpload(parseResult.data);
      return reply.status(200).send(ticket);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'SIGNING_FAILED';
      return reply.status(400).send({ error: 'SIGNING_FAILED', message: msg });
    }
  });

  // 8. Calls: Get Ephemeral ICE / TURN Servers
  server.get('/api/v1/calls/ice-servers', async (request: FastifyRequest, reply: FastifyReply) => {
    const user = await authenticate(request, reply);
    if (!user) return;

    const iceServers = turnService.generateEphemeralIceServers(user.sub);
    return reply.status(200).send(iceServers);
  });

  // In-Memory Sync Store for Cross-Device Real-Time Messaging & Friend Requests
  interface SyncMessage {
    id: string;
    conversationId: string;
    senderId: string;
    senderName: string;
    text: string;
    timestamp: string;
    isMe?: boolean;
    mediaUrl?: string | null;
    messageType?: string;
  }

  interface SyncFriendRequest {
    id: string;
    senderId: string;
    senderName: string;
    senderEmail: string;
    receiverEmail: string;
    timestamp: string;
    status: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  }

  const syncMessagesStore = new Map<string, SyncMessage[]>();
  const syncFriendRequestsStore: SyncFriendRequest[] = [];

  // Friend Requests Sync Endpoints
  server.get('/api/v1/sync/friend-requests', async (request: FastifyRequest, reply: FastifyReply) => {
    const { email } = request.query as { email?: string };
    if (!email) {
      return reply.status(200).send({ requests: syncFriendRequestsStore });
    }
    const filtered = syncFriendRequestsStore.filter(
      (r) => r.receiverEmail.toLowerCase() === email.toLowerCase() || r.senderEmail.toLowerCase() === email.toLowerCase()
    );
    return reply.status(200).send({ requests: filtered });
  });

  server.post('/api/v1/sync/friend-requests', async (request: FastifyRequest, reply: FastifyReply) => {
    const body = request.body as Partial<SyncFriendRequest>;
    if (!body.senderEmail || !body.receiverEmail) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'Missing senderEmail or receiverEmail' });
    }

    const newReq: SyncFriendRequest = {
      id: body.id || `fr_${Date.now()}`,
      senderId: body.senderId || 'sender_id',
      senderName: body.senderName || body.senderEmail.split('@')[0] || 'User',
      senderEmail: body.senderEmail,
      receiverEmail: body.receiverEmail,
      timestamp: body.timestamp || new Date().toISOString(),
      status: 'PENDING',
    };

    const existingIdx = syncFriendRequestsStore.findIndex(
      (r) =>
        r.senderEmail.toLowerCase() === newReq.senderEmail.toLowerCase() &&
        r.receiverEmail.toLowerCase() === newReq.receiverEmail.toLowerCase() &&
        r.status === 'PENDING'
    );

    if (existingIdx >= 0) {
      syncFriendRequestsStore[existingIdx] = newReq;
    } else {
      syncFriendRequestsStore.unshift(newReq);
    }

    return reply.status(201).send({ success: true, request: newReq });
  });

  server.post('/api/v1/sync/friend-requests/:id/respond', async (request: FastifyRequest, reply: FastifyReply) => {
    const { id } = request.params as { id: string };
    const { status } = request.body as { status: 'ACCEPTED' | 'REJECTED' };
    const req = syncFriendRequestsStore.find((r) => r.id === id);
    if (!req) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Friend request not found' });
    }
    req.status = status;
    return reply.status(200).send({ success: true, request: req });
  });

  // Messages Real-Time Sync Endpoints
  server.get('/api/v1/sync/messages/:conversationId', async (request: FastifyRequest, reply: FastifyReply) => {
    const { conversationId } = request.params as { conversationId: string };
    const list = syncMessagesStore.get(conversationId) || [];
    return reply.status(200).send({ messages: list });
  });

  server.post('/api/v1/sync/messages/:conversationId', async (request: FastifyRequest, reply: FastifyReply) => {
    const { conversationId } = request.params as { conversationId: string };
    const msg = request.body as SyncMessage;
    if (!msg || !msg.id || !msg.senderId) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'Invalid message payload' });
    }

    const currentList = syncMessagesStore.get(conversationId) || [];
    if (!currentList.some((m) => m.id === msg.id)) {
      currentList.push(msg);
      syncMessagesStore.set(conversationId, currentList);
    }
    return reply.status(201).send({ success: true, message: msg });
  });

  return server;
}

// Standalone runner
if (process.env.NODE_ENV !== 'test') {
  const start = async () => {
    const server = await buildServer();
    try {
      await server.listen({ port: config.API_PORT, host: config.API_HOST });
      console.log(`ConnectX API listening on http://${config.API_HOST}:${config.API_PORT}`);
    } catch (err) {
      server.log.error(err);
      process.exit(1);
    }
  };
  start();
}
