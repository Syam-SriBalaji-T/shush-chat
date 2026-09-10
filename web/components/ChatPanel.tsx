"use client";

import { useRef, useState } from "react";
import type { ChatItem, Message } from "@/lib/types";
import type { Peer } from "@/lib/useShush";
import { Avatar } from "./Avatar";
import { MessageList } from "./MessageList";

export const ChatPanel = ({
  peer,
  items,
  meId,
  typing,
  isFriendConversation,
  replyingTo,
  quotedFor,
  onOpenPeer,
  onSend,
  onChooseImage,
  onTyping,
  onAskToKeep,
  onLeave,
  onReply,
  onCancelReply,
  onReact,
  onDeleteForEveryone,
  onHideForMe,
}: {
  peer: Peer;
  items: ChatItem[];
  meId: string | undefined;
  typing: boolean;
  isFriendConversation: boolean;
  replyingTo: Message | null;
  quotedFor: (seq: number | null | undefined) => Message | null;
  onOpenPeer: () => void;
  onSend: (body: string) => void;
  onChooseImage: (file: File) => void;
  onTyping: () => void;
  onAskToKeep: () => void;
  onLeave: () => void;
  onReply: (message: Message) => void;
  onCancelReply: () => void;
  onReact: (message: Message, emoji: string | null) => void;
  onDeleteForEveryone: (message: Message) => void;
  onHideForMe: (message: Message) => void;
}) => {
  const [draft, setDraft] = useState("");
  const file = useRef<HTMLInputElement>(null);
  const composer = useRef<HTMLInputElement>(null);

  const submit = () => {
    onSend(draft);
    setDraft("");
  };

  return (
    <div id="chat" className="flex min-h-0 flex-1 flex-col">
      <div
        className="flex items-center gap-3 border-b py-3.5"
        style={{ borderColor: "var(--color-line-soft)", paddingLeft: 22, paddingRight: 22 }}
      >
        <button id="chatAvatar" type="button" title="View profile" onClick={onOpenPeer}>
          <Avatar id={peer.userId} name={peer.name} />
        </button>
        <button
          id="chatWho"
          type="button"
          title="View profile"
          onClick={onOpenPeer}
          className="min-w-0 text-left"
        >
          <h2 id="chatHeading" className="m-0 text-[15px] font-semibold">
            {peer.heading}
          </h2>
          <div id="chatSub" className="text-xs" style={{ color: "var(--color-faint)" }}>
            {peer.sub}
          </div>
        </button>
        <span className="flex-1" />
        {/* Already friends means there is nothing left to ask for, and nothing to walk out of. */}
        {!isFriendConversation && (
          <>
            <button id="addFriend" type="button" className="btn-ghost" onClick={onAskToKeep}>
              Add friend
            </button>
            <button id="leave" type="button" className="btn-ghost" onClick={onLeave}>
              Leave
            </button>
          </>
        )}
      </div>

      <MessageList
        items={items}
        meId={meId}
        quotedFor={quotedFor}
        onReply={(message) => {
          onReply(message);
          composer.current?.focus();
        }}
        onReact={onReact}
        onDeleteForEveryone={onDeleteForEveryone}
        onHideForMe={onHideForMe}
      />

      <p
        id="typingIndicator"
        className="mx-5 mb-2 min-h-4 text-xs"
        style={{ color: "var(--color-muted)" }}
      >
        {typing ? "They are typing…" : ""}
      </p>

      {replyingTo && (
        <div
          id="replyBar"
          className="mx-5 mb-2 flex items-center gap-3 rounded-xl border-l-[3px] px-3 py-2"
          style={{
            borderLeftColor: "var(--color-brand)",
            backgroundColor: "var(--color-surface-2)",
          }}
        >
          <div className="min-w-0 flex-1">
            <div className="text-[11px] font-semibold" style={{ color: "var(--color-brand)" }}>
              Replying to {replyingTo.senderId === meId ? "yourself" : "them"}
            </div>
            <div className="truncate text-[13px]" style={{ color: "var(--color-muted)" }}>
              {replyingTo.kind === "image" ? "Photo" : replyingTo.body}
            </div>
          </div>
          <button
            id="cancelReply"
            type="button"
            aria-label="Cancel reply"
            className="btn-ghost px-2.5 py-1 text-xs"
            onClick={onCancelReply}
          >
            ✕
          </button>
        </div>
      )}

      <div
        className="flex items-center gap-2.5 border-t px-5 py-3.5"
        style={{ borderColor: "var(--color-line-soft)" }}
      >
        <button
          id="attach"
          type="button"
          className="btn-ghost grid h-10 w-10 place-items-center rounded-full p-0"
          title="Attach an image"
          aria-label="Attach an image"
          onClick={() => file.current?.click()}
        >
          {/* A paperclip. "Image" as a word took the space of a control and read as a label. */}
          <svg
            viewBox="0 0 24 24"
            className="h-5 w-5"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M21.4 11.05 12.25 20.2a5.5 5.5 0 0 1-7.78-7.78l9.19-9.19a3.67 3.67 0 0 1 5.19 5.19l-9.2 9.19a1.83 1.83 0 0 1-2.59-2.59l8.49-8.48" />
          </svg>
        </button>
        <input
          id="imageInput"
          ref={file}
          type="file"
          accept="image/jpeg,image/png,image/webp,image/gif"
          hidden
          onChange={(event) => {
            const chosen = event.target.files?.[0];
            if (chosen) onChooseImage(chosen);
            event.target.value = "";
          }}
        />
        <input
          id="composer"
          ref={composer}
          type="text"
          className="field flex-1"
          placeholder="Say something"
          autoComplete="off"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            onTyping();
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter") submit();
            if (event.key === "Escape") onCancelReply();
          }}
        />
        <button id="send" type="button" className="btn-primary" onClick={submit}>
          Send
        </button>
      </div>
    </div>
  );
};
