-- =============================================================================
-- ConnectX Development Seed Data
-- =============================================================================

-- Seed Demo Users
INSERT INTO public.profiles (id, firebase_uid, display_name, bio)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'fb_alice_seed', 'Alice Cooper', 'ConnectX Mobile Engineer'),
    ('22222222-2222-2222-2222-222222222222', 'fb_bob_seed', 'Bob Miller', 'Product & Design Lead'),
    ('33333333-3333-3333-3333-333333333333', 'fb_carol_seed', 'Carol Danvers', 'Security & Infrastructure')
ON CONFLICT (firebase_uid) DO NOTHING;

-- Seed User Settings
INSERT INTO public.user_settings (user_id, allow_messages_from, show_presence, read_receipts_enabled, notification_preferences)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'everyone', true, true, '{"messages": true, "calls": true, "preview_text": false}'::jsonb),
    ('22222222-2222-2222-2222-222222222222', 'everyone', true, true, '{"messages": true, "calls": true, "preview_text": true}'::jsonb),
    ('33333333-3333-3333-3333-333333333333', 'contacts_only', false, false, '{"messages": true, "calls": false, "preview_text": false}'::jsonb)
ON CONFLICT (user_id) DO NOTHING;

-- Seed Contacts
INSERT INTO public.contacts (owner_id, contact_id, status)
VALUES
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'accepted'),
    ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'accepted')
ON CONFLICT (owner_id, contact_id) DO NOTHING;

-- Seed Direct Conversation
INSERT INTO public.conversations (id, kind, created_by)
VALUES
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'direct', '11111111-1111-1111-1111-111111111111')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.conversation_members (conversation_id, user_id, role)
VALUES
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'member'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222', 'member')
ON CONFLICT (conversation_id, user_id) DO NOTHING;

-- Seed Sample Messages
INSERT INTO public.messages (id, conversation_id, sender_id, message_type, text_body)
VALUES
    ('mmmmmmmm-1111-1111-1111-111111111111', 'cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'text', 'Hi Bob, did you review the screen sharing document?'),
    ('mmmmmmmm-2222-2222-2222-222222222222', 'cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222', 'text', 'Yes! The Android 14 ForegroundService and iOS ReplayKit boundaries look great.')
ON CONFLICT (id) DO NOTHING;
