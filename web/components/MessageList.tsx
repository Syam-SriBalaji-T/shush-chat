"use client";

import { useEffect, useRef } from "react";
import type { ChatItem, Message } from "@/lib/types";
import { MessageBubble } from "./MessageBubble";

/**
 * Date separators and system events share one pill on purpose: both are the room talking
 * rather than a person, and a reader should not have to tell them apart.
 */
const Pill = ({ children, muted }: { children: React.ReactNode; muted?: boolean }) => (
  <div
    className={`self-center rounded-full border px-3.5 py-1 text-center ${
      muted ? "text-[11.5px] font-semibold tracking-wide uppercase" : "text-[12.5px]"
    }`}
    style={{
      color: "var(--color-muted)",
      backgroundColor: "var(--color-surface-2)",
      borderColor: "var(--color-line-soft)",
      margin: muted ? "12px 0 6px" : "7px 0",
    }}
  >
    {children}
  </div>
);

export const MessageList = ({
  items,
  meId,
  quotedFor,
  onReply,
  onReact,
  onDeleteForEveryone,
  onHideForMe,
}: {
  items: ChatItem[];
  meId: string | undefined;
  quotedFor: (seq: number | null | undefined) => Message | null;
  onReply: (message: Message) => void;
  onReact: (message: Message, emoji: string | null) => void;
  onDeleteForEveryone: (message: Message) => void;
  onHideForMe: (message: Message) => void;
}) => {
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottom.current?.scrollIntoView({ block: "end" });
  }, [items]);

  return (
    <div id="messages" className="flex min-h-0 flex-1 flex-col gap-[3px] overflow-y-auto p-6">
      {items.map((item, index) => {
        if (item.kind === "day") {
          return (
            <Pill key={item.id} muted>
              {item.label}
            </Pill>
          );
        }
        if (item.kind === "event") {
          return <Pill key={item.id}>{item.text}</Pill>;
        }

        const { message, delivery } = item;
        return (
          <MessageBubble
            key={message.clientMsgId ?? `${message.seq}-${index}`}
            message={message}
            delivery={delivery}
            mine={message.senderId === meId}
            meId={meId}
            quoted={quotedFor(message.replyToSeq)}
            onReply={() => onReply(message)}
            onReact={(emoji) => onReact(message, emoji)}
            onDeleteForEveryone={() => onDeleteForEveryone(message)}
            onHideForMe={() => onHideForMe(message)}
          />
        );
      })}
      <div ref={bottom} />
    </div>
  );
};
