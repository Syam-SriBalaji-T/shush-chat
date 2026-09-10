-- Replying, reacting, and the two different things people mean by "delete".

-- What this message is a reply to, as a seq rather than an id. seq is already the stable
-- per-conversation identity every client holds, it is what history pages on, and it makes the
-- quoted message findable without a join when it is on screen already.
alter table messages add column reply_to_seq bigint;

-- Deleted for everyone. The row stays: seq is dense by design and removing one would leave a
-- gap that the ordering harness reads as a lost message. The body goes, because "deleted" has
-- to mean the text is gone rather than merely hidden by a client that could choose not to.
alter table messages add column deleted_at timestamptz;

alter table messages
    add constraint messages_reply_to_seq_check check (reply_to_seq is null or reply_to_seq > 0);

-- Finding a reply's target, and rendering a conversation, both walk (conversation_id, seq);
-- that index already exists. This one answers "what replied to this", which the delete path
-- needs so a quote does not outlive the message it quotes.
create index messages_conversation_reply_to_idx on messages (conversation_id, reply_to_seq)
    where reply_to_seq is not null;

-- One reaction per person per message, which is what the primary key says. WhatsApp's rule,
-- and it keeps the tally a simple group-by rather than a set union.
create table message_reactions (
    message_id uuid        not null references messages (id) on delete cascade,
    user_id    uuid        not null references users (id) on delete cascade,
    emoji      text        not null,
    created_at timestamptz not null,
    primary key (message_id, user_id)
);

create index message_reactions_message_idx on message_reactions (message_id);

-- "Delete for me". A row here hides one message from one person and says nothing to anybody
-- else. Deliberately a separate table from messages.deleted_at: the two are different claims,
-- one about the message and one about a reader, and collapsing them would make "they deleted
-- it" and "I hid it" indistinguishable.
create table message_hides (
    message_id uuid        not null references messages (id) on delete cascade,
    user_id    uuid        not null references users (id) on delete cascade,
    created_at timestamptz not null,
    primary key (message_id, user_id)
);
