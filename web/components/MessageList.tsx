"use client";

import { useEffect, useRef } from "react";
import { mediaUrl } from "@/lib/api";
import { clockTime } from "@/lib/time";
import type { ChatItem } from "@/lib/types";
import { Ticks } from "./Ticks";

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

export const MessageList = ({ items, meId }: { items: ChatItem[]; meId: string | undefined }) => {
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottom.current?.scrollIntoView({ block: "end" });
  }, [items]);

  return (
    <div
      id="messages"
      className="flex min-h-0 flex-1 flex-col gap-[3px] overflow-y-auto p-6"
    >
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
        const mine = message.senderId === meId;
        return (
          <div
            key={message.clientMsgId ?? `${message.seq}-${index}`}
            data-testid="message"
            data-seq={message.seq ?? ""}
            data-mine={mine}
            className="rise max-w-[74%] px-3.5 py-2.5"
            style={{
              alignSelf: mine ? "flex-end" : "flex-start",
              color: mine ? "#fff" : "var(--color-body)",
              background: mine
                ? "linear-gradient(135deg, #6d4dfb, var(--color-brand-2))"
                : "var(--color-surface-2)",
              border: `1px solid ${mine ? "transparent" : "var(--color-line-soft)"}`,
              borderRadius: mine ? "16px 16px 5px 16px" : "16px 16px 16px 5px",
              overflowWrap: "anywhere",
            }}
          >
            {message.kind === "image" && message.mediaKey ? (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img
                alt="shared image"
                src={mediaUrl(message.mediaKey)}
                className="my-0.5 block max-w-[260px] rounded-[10px]"
              />
            ) : (
              <div>{message.body}</div>
            )}
            <div
              className="mt-0.5 flex items-center gap-1 text-[10.5px] whitespace-nowrap opacity-65"
              style={{ justifyContent: mine ? "flex-end" : "flex-start" }}
            >
              <span>{clockTime(message.createdAt)}</span>
              {mine && <Ticks state={delivery} />}
            </div>
          </div>
        );
      })}
      <div ref={bottom} />
    </div>
  );
};
