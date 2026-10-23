-- =============================================================================
-- ConnectX Initial Schema & Row Level Security (RLS) Policies
-- Migration: 20261023000000_initial_schema.sql
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Profiles Table
CREATE TABLE public.profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid TEXT UNIQUE NOT NULL,
    display_name TEXT NOT NULL CHECK (char_length(display_name) >= 1 AND char_length(display_name) <= 64),
    avatar_asset_id TEXT NULL,
    bio TEXT NULL CHECK (char_length(bio) <= 256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_profiles_firebase_uid ON public.profiles(firebase_uid);

-- 2. User Settings Table
CREATE TABLE public.user_settings (
    user_id UUID PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    allow_messages_from TEXT NOT NULL DEFAULT 'everyone' CHECK (allow_messages_from IN ('everyone', 'contacts_only')),
    show_presence BOOLEAN NOT NULL DEFAULT true,
    read_receipts_enabled BOOLEAN NOT NULL DEFAULT true,
    notification_preferences JSONB NOT NULL DEFAULT '{"messages": true, "calls": true, "preview_text": false}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. Contacts Table
CREATE TABLE public.contacts (
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'accepted' CHECK (status IN ('pending', 'accepted', 'blocked')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, contact_id),
    CONSTRAINT chk_contacts_no_self CHECK (owner_id <> contact_id)
);

CREATE INDEX idx_contacts_owner_id ON public.contacts(owner_id);
CREATE INDEX idx_contacts_contact_id ON public.contacts(contact_id);

-- 4. Conversations Table
CREATE TABLE public.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind TEXT NOT NULL CHECK (kind IN ('direct', 'group')),
    title TEXT NULL,
    created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversations_created_at ON public.conversations(created_at DESC);

-- 5. Conversation Members Table
CREATE TABLE public.conversation_members (
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'admin', 'member')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_message_id UUID NULL,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conv_members_user_id ON public.conversation_members(user_id);

-- 6. Messages Table
CREATE TABLE public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    message_type TEXT NOT NULL CHECK (message_type IN ('text', 'image', 'video', 'file', 'system')),
    text_body TEXT NULL,
    idempotency_key TEXT NULL UNIQUE,
    reply_to_message_id UUID NULL REFERENCES public.messages(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at TIMESTAMPTZ NULL,
    deleted_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_messages_conversation_chronological ON public.messages(conversation_id, created_at DESC);
CREATE INDEX idx_messages_sender ON public.messages(sender_id);

-- 7. Message Attachments Table
CREATE TABLE public.message_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    provider TEXT NOT NULL DEFAULT 'cloudinary',
    asset_id TEXT NOT NULL,
    resource_type TEXT NOT NULL CHECK (resource_type IN ('image', 'video', 'raw')),
    mime_type TEXT NULL,
    size_bytes BIGINT NULL,
    width INT NULL,
    height INT NULL,
    duration_ms BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_attachments_message_id ON public.message_attachments(message_id);

-- 8. Calls Table
CREATE TABLE public.calls (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NULL REFERENCES public.conversations(id) ON DELETE SET NULL,
    initiated_by UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    call_type TEXT NOT NULL CHECK (call_type IN ('voice', 'video')),
    status TEXT NOT NULL CHECK (status IN ('initiated', 'ringing', 'active', 'ended', 'missed', 'rejected', 'busy')),
    started_at TIMESTAMPTZ NULL,
    ended_at TIMESTAMPTZ NULL,
    end_reason TEXT NULL
);

CREATE INDEX idx_calls_initiated_by ON public.calls(initiated_by);

-- 9. Call Participants Table
CREATE TABLE public.call_participants (
    call_id UUID NOT NULL REFERENCES public.calls(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NULL,
    left_at TIMESTAMPTZ NULL,
    role TEXT NOT NULL DEFAULT 'callee' CHECK (role IN ('caller', 'callee')),
    PRIMARY KEY (call_id, user_id)
);

-- 10. Devices Table (Push Notification Tokens)
CREATE TABLE public.devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    push_token TEXT NOT NULL,
    platform TEXT NOT NULL CHECK (platform IN ('android', 'ios')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_devices_user_id ON public.devices(user_id);

-- 11. Blocks Table
CREATE TABLE public.blocks (
    blocker_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT chk_blocks_no_self CHECK (blocker_id <> blocked_id)
);

-- 12. Reports Table
CREATE TABLE public.reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reported_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    details TEXT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'reviewed', 'dismissed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- ROW LEVEL SECURITY (RLS) POLICIES
-- Security rule: Enable RLS on every table. Default to deny.
-- =============================================================================

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.call_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;

-- Helper Function: Check conversation membership
CREATE OR REPLACE FUNCTION public.is_conversation_member(conv_id UUID, uid UUID)
RETURNS BOOLEAN
LANGUAGE sql
SECURITY DEFINER
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = conv_id AND user_id = uid
    );
$$;

-- RLS: profiles
CREATE POLICY "Profiles are viewable by authenticated users"
    ON public.profiles FOR SELECT
    TO authenticated
    USING (true);

CREATE POLICY "Users can update own profile"
    ON public.profiles FOR UPDATE
    TO authenticated
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

-- RLS: user_settings
CREATE POLICY "Users can view and manage their own settings"
    ON public.user_settings FOR ALL
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- RLS: contacts
CREATE POLICY "Users can view and manage their own contacts"
    ON public.contacts FOR ALL
    TO authenticated
    USING (auth.uid() = owner_id)
    WITH CHECK (auth.uid() = owner_id);

-- RLS: conversations
CREATE POLICY "Users can view conversations they belong to"
    ON public.conversations FOR SELECT
    TO authenticated
    USING (public.is_conversation_member(id, auth.uid()));

CREATE POLICY "Authenticated users can create conversations"
    ON public.conversations FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = created_by);

-- RLS: conversation_members
CREATE POLICY "Members can view membership list"
    ON public.conversation_members FOR SELECT
    TO authenticated
    USING (public.is_conversation_member(conversation_id, auth.uid()));

CREATE POLICY "Members can update their own last_read_message_id"
    ON public.conversation_members FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- RLS: messages
CREATE POLICY "Members can view messages in their conversations"
    ON public.messages FOR SELECT
    TO authenticated
    USING (public.is_conversation_member(conversation_id, auth.uid()));

CREATE POLICY "Members can insert messages into their conversations"
    ON public.messages FOR INSERT
    TO authenticated
    WITH CHECK (
        auth.uid() = sender_id AND
        public.is_conversation_member(conversation_id, auth.uid())
    );

CREATE POLICY "Senders can soft-delete or edit own messages"
    ON public.messages FOR UPDATE
    TO authenticated
    USING (auth.uid() = sender_id)
    WITH CHECK (auth.uid() = sender_id);

-- RLS: message_attachments
CREATE POLICY "Members can view attachments in their messages"
    ON public.message_attachments FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.messages m
            WHERE m.id = message_id AND public.is_conversation_member(m.conversation_id, auth.uid())
        )
    );

-- RLS: calls & call_participants
CREATE POLICY "Participants can view their calls"
    ON public.calls FOR SELECT
    TO authenticated
    USING (
        initiated_by = auth.uid() OR
        EXISTS (SELECT 1 FROM public.call_participants cp WHERE cp.call_id = id AND cp.user_id = auth.uid())
    );

CREATE POLICY "Initiators can create calls"
    ON public.calls FOR INSERT
    TO authenticated
    WITH CHECK (initiated_by = auth.uid());

CREATE POLICY "Participants can view call participants"
    ON public.call_participants FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.call_participants cp2
            WHERE cp2.call_id = call_id AND cp2.user_id = auth.uid()
        )
    );

-- RLS: devices
CREATE POLICY "Users can manage their registered push devices"
    ON public.devices FOR ALL
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- RLS: blocks
CREATE POLICY "Users can manage their blocked list"
    ON public.blocks FOR ALL
    TO authenticated
    USING (auth.uid() = blocker_id)
    WITH CHECK (auth.uid() = blocker_id);

-- RLS: reports
CREATE POLICY "Users can submit abuse reports"
    ON public.reports FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = reporter_id);
