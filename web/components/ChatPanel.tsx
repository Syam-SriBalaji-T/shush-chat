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
  ended,
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
  onOpenImage,
  onFindSomeone,
  setupPanel,
}: {
  peer: Peer;
  items: ChatItem[];
  meId: string | undefined;
  typing: boolean;
  isFriendConversation: boolean;
  ended: boolean;
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
  onOpenImage: (mediaKey: string) => void;
  onFindSomeone: () => void;
  setupPanel: React.ReactNode;
}) => {
  const [draft, setDraft] = useState("");
  const file = useRef<HTMLInputElement>(null);
  const camera = useRef<HTMLInputElement>(null);
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
            {/* Still offered after somebody leaves: asking to keep them is the one thing a
                finished stranger conversation is still for. */}
            <button id="addFriend" type="button" className="btn-ghost" onClick={onAskToKeep}>
              Add friend
            </button>
            {!ended && (
              <button id="leave" type="button" className="btn-ghost" onClick={onLeave}>
                Leave
              </button>
            )}
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
        onOpenImage={onOpenImage}
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

      {ended ? (
        /* No composer, because there is nothing to send into. Straight to the next
           conversation instead of leaving somebody staring at a dead thread -- the card is
           here rather than a link to it, so finding the next person is one click. */
        <div
          id="endedPanel"
          className="border-t px-5 py-4"
          style={{ borderColor: "var(--color-line-soft)" }}
        >
          <div className="mb-3 flex flex-wrap items-center gap-3">
            <span className="text-[13px]" style={{ color: "var(--color-muted)" }}>
              This conversation is over.
            </span>
            <button
              id="findSomeoneNext"
              type="button"
              className="btn-primary"
              onClick={onFindSomeone}
            >
              Find someone new
            </button>
          </div>
          {setupPanel}
        </div>
      ) : (
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
        <button
          id="camera"
          type="button"
          className="btn-ghost grid h-10 w-10 place-items-center rounded-full p-0"
          title="Take a photo"
          aria-label="Take a photo"
          onClick={() => camera.current?.click()}
        >
          <svg
            viewBox="0 0 24 24"
            className="h-5 w-5"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M3 8.5A1.5 1.5 0 0 1 4.5 7h2.2l1.1-1.8A1.5 1.5 0 0 1 9.1 4.5h5.8a1.5 1.5 0 0 1 1.3.7L17.3 7h2.2A1.5 1.5 0 0 1 21 8.5v9A1.5 1.5 0 0 1 19.5 19h-15A1.5 1.5 0 0 1 3 17.5z" />
            <circle cx="12" cy="12.8" r="3.4" />
          </svg>
        </button>
        {/* capture="environment" hands straight to the phone camera; on a desktop the browser
            falls back to the ordinary file picker, so one control covers both. */}
        <input
          id="cameraInput"
          ref={camera}
          type="file"
          accept="image/*"
          capture="environment"
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
      )}
    </div>
  );
};
