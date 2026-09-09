"use client";

import { useRef, useState } from "react";
import type { ChatItem } from "@/lib/types";
import { Avatar } from "./Avatar";
import { MessageList } from "./MessageList";
import type { Peer } from "@/lib/useShush";

export const ChatPanel = ({
  peer,
  items,
  meId,
  typing,
  isFriendConversation,
  onOpenPeer,
  onSend,
  onImage,
  onTyping,
  onAskToKeep,
  onLeave,
}: {
  peer: Peer;
  items: ChatItem[];
  meId: string | undefined;
  typing: boolean;
  isFriendConversation: boolean;
  onOpenPeer: () => void;
  onSend: (body: string) => void;
  onImage: (file: File) => void;
  onTyping: () => void;
  onAskToKeep: () => void;
  onLeave: () => void;
}) => {
  const [draft, setDraft] = useState("");
  const file = useRef<HTMLInputElement>(null);

  const submit = () => {
    onSend(draft);
    setDraft("");
  };

  return (
    <div id="chat" className="flex min-h-0 flex-1 flex-col">
      <div
        className="flex items-center gap-3 border-b px-5.5 py-3.5"
        style={{ borderColor: "var(--color-line-soft)", paddingLeft: 22, paddingRight: 22 }}
      >
        <button
          id="chatAvatar"
          type="button"
          title="View profile"
          onClick={onOpenPeer}
          className="cursor-pointer"
        >
          <Avatar id={peer.userId} name={peer.name} />
        </button>
        <button
          id="chatWho"
          type="button"
          title="View profile"
          onClick={onOpenPeer}
          className="min-w-0 cursor-pointer text-left"
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

      <MessageList items={items} meId={meId} />

      <p
        id="typingIndicator"
        className="mx-5 mb-2 min-h-4 text-xs"
        style={{ color: "var(--color-muted)" }}
      >
        {typing ? "They are typing…" : ""}
      </p>

      <div
        className="flex items-center gap-2.5 border-t px-5 py-3.5"
        style={{ borderColor: "var(--color-line-soft)" }}
      >
        <button
          id="attach"
          type="button"
          className="btn-ghost px-3"
          title="Send an image"
          onClick={() => file.current?.click()}
        >
          Image
        </button>
        <input
          id="imageInput"
          ref={file}
          type="file"
          accept="image/jpeg,image/png,image/webp,image/gif"
          hidden
          onChange={(event) => {
            const chosen = event.target.files?.[0];
            if (chosen) onImage(chosen);
            event.target.value = "";
          }}
        />
        <input
          id="composer"
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
          }}
        />
        <button id="send" type="button" className="btn-primary" onClick={submit}>
          Send
        </button>
      </div>
    </div>
  );
};
