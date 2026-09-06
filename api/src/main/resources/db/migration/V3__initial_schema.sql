-- The full schema from plan.md 2.2. Tables for features that arrive in later phases are
-- created now so the schema is settled in one place; Hibernate validates only what is mapped.

create extension if not exists citext;

create table users (
    id            uuid        primary key,
    display_name  text        not null,
    is_anonymous  boolean     not null default true,
    email         citext,
    password_hash text,
    created_at    timestamptz not null,
    last_seen_at  timestamptz not null,
    deleted_at    timestamptz,
    constraint users_display_name_key unique (display_name),
    constraint users_email_key unique (email)
);

create table device_tokens (
    token_hash   text        primary key,
    user_id      uuid        not null references users (id) on delete cascade,
    created_at   timestamptz not null,
    last_used_at timestamptz not null
);

create index device_tokens_user_idx on device_tokens (user_id);

create table interests (
    id         smallint primary key,
    slug       text     not null,
    label      text     not null,
    popularity int      not null default 0,
    constraint interests_slug_key unique (slug)
);

create index interests_popularity_idx on interests (popularity desc);

create table user_interests (
    user_id      uuid        not null references users (id) on delete cascade,
    interest_id  smallint    not null references interests (id),
    last_used_at timestamptz not null,
    primary key (user_id, interest_id)
);

create table conversations (
    id          uuid        primary key,
    kind        text        not null,
    state       text        not null,
    matched_on  smallint[],
    -- Sole source of per-conversation message sequence numbers. Bumped inside the same
    -- transaction as the insert, so a gap can only mean a rolled-back write.
    last_seq    bigint      not null default 0,
    created_at  timestamptz not null,
    ended_at    timestamptz,
    purge_after timestamptz,
    constraint conversations_kind_check check (kind in ('stranger', 'friend')),
    constraint conversations_state_check check (state in ('active', 'ended', 'kept'))
);

create index conversations_purge_after_idx on conversations (purge_after) where purge_after is not null;

create table conversation_participants (
    conversation_id uuid        not null references conversations (id) on delete cascade,
    user_id         uuid        not null references users (id) on delete cascade,
    read_cursor_seq bigint      not null default 0,
    unread_count    int         not null default 0,
    left_at         timestamptz,
    primary key (conversation_id, user_id)
);

create index conversation_participants_user_idx on conversation_participants (user_id);

create table messages (
    id              uuid        primary key,
    conversation_id uuid        not null references conversations (id) on delete cascade,
    sender_id       uuid        not null references users (id),
    seq             bigint      not null,
    kind            text        not null,
    body            text,
    media_key       text,
    -- Idempotency key supplied by the client and stable across its retries. Dedup is this
    -- constraint, not application logic.
    client_msg_id   uuid        not null,
    created_at      timestamptz not null,
    constraint messages_kind_check check (kind in ('text', 'image', 'system')),
    constraint messages_conversation_seq_key unique (conversation_id, seq),
    constraint messages_conversation_sender_client_msg_id_key unique (conversation_id, sender_id, client_msg_id)
);

create table friend_requests (
    id              uuid        primary key,
    conversation_id uuid        not null references conversations (id) on delete cascade,
    from_user_id    uuid        not null references users (id) on delete cascade,
    to_user_id      uuid        not null references users (id) on delete cascade,
    status          text        not null,
    created_at      timestamptz not null,
    expires_at      timestamptz not null,
    constraint friend_requests_status_check check (status in ('pending', 'accepted', 'expired')),
    constraint friend_requests_conversation_from_user_key unique (conversation_id, from_user_id)
);

create index friend_requests_to_user_status_idx on friend_requests (to_user_id, status);
create index friend_requests_expires_at_idx on friend_requests (expires_at) where status = 'pending';

create table friendships (
    user_a_id       uuid        not null references users (id) on delete cascade,
    user_b_id       uuid        not null references users (id) on delete cascade,
    conversation_id uuid        not null references conversations (id),
    created_at      timestamptz not null,
    primary key (user_a_id, user_b_id),
    -- One row per pair, not two. The ordering is what makes that enforceable.
    constraint friendships_ordered_check check (user_a_id < user_b_id)
);

create index friendships_user_b_idx on friendships (user_b_id);

create table blocks (
    blocker_id uuid        not null references users (id) on delete cascade,
    blocked_id uuid        not null references users (id) on delete cascade,
    created_at timestamptz not null,
    primary key (blocker_id, blocked_id)
);

create table reports (
    id              uuid        primary key,
    reporter_id     uuid        not null references users (id) on delete cascade,
    reported_id     uuid        not null references users (id) on delete cascade,
    conversation_id uuid        references conversations (id) on delete set null,
    reason          text        not null,
    created_at      timestamptz not null
);

create table media_objects (
    key             text        primary key,
    uploader_id     uuid        not null references users (id) on delete cascade,
    conversation_id uuid        not null references conversations (id) on delete cascade,
    status          text        not null,
    mime            text        not null,
    size_bytes      bigint,
    created_at      timestamptz not null,
    expires_at      timestamptz not null,
    constraint media_objects_status_check check (status in ('pending', 'confirmed'))
);

create index media_objects_expires_at_idx on media_objects (expires_at);
create index media_objects_status_created_at_idx on media_objects (status, created_at);

create table invite_links (
    code       text        primary key,
    owner_id   uuid        not null references users (id) on delete cascade,
    created_at timestamptz not null,
    expires_at timestamptz not null
);

create index invite_links_owner_idx on invite_links (owner_id);
