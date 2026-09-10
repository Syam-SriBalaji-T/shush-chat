"use client";

import { useRef, useState } from "react";
import { mediaUrl } from "@/lib/api";
import { clockTime } from "@/lib/time";
import type { Delivery, Message } from "@/lib/types";
import { EmojiPicker } from "./EmojiPicker";
import { Ticks } from "./Ticks";

/** Far enough that it cannot be a scroll, close enough to be one flick of a thumb. */
const SWIPE_TO_REPLY = 60;
const LONG_PRESS_MS = 450;

const Quoted = ({ message, mine }: { message: Message; mine: boolean }) => (
  <div
    data-testid="quote"
    className="mb-1.5 truncate rounded-lg border-l-[3px] px-2.5 py-1.5 text-[13px]"
    style={{
      borderLeftColor: mine ? "rgb(255 255 255 / 0.7)" : "var(--color-brand)",
      backgroundColor: mine ? "rgb(255 255 255 / 0.14)" : "var(--color-surface-3)",
    }}
  >
    <span className="opacity-80">
      {message.deleted
        ? "This message was deleted"
        : message.kind === "image"
          ? "Photo"
          : message.body}
    </span>
  </div>
);

export const MessageBubble = ({
  message,
  delivery,
  mine,
  quoted,
  meId,
  onReply,
  onReact,
  onDeleteForEveryone,
  onHideForMe,
}: {
  message: Message;
  delivery: Delivery;
  mine: boolean;
  quoted: Message | null;
  meId: string | undefined;
  onReply: () => void;
  onReact: (emoji: string | null) => void;
  onDeleteForEveryone: () => void;
  onHideForMe: () => void;
}) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [offset, setOffset] = useState(0);

  const startX = useRef(0);
  const dragging = useRef(false);
  const longPress = useRef<ReturnType<typeof setTimeout> | null>(null);
  const moved = useRef(false);

  const myReaction = (message.reactions ?? []).find((r) => r.userId === meId)?.emoji ?? null;

  const cancelLongPress = () => {
    if (longPress.current) {
      clearTimeout(longPress.current);
      longPress.current = null;
    }
  };

  /**
   * Swipe to reply, in the direction the bubble sits: your own messages pull left, theirs pull
   * right. Anything else is a scroll, so a vertical-ish drag cancels rather than fights it.
   */
  const onPointerDown = (event: React.PointerEvent) => {
    if (message.deleted) return;
    startX.current = event.clientX;
    dragging.current = true;
    moved.current = false;
    cancelLongPress();
    longPress.current = setTimeout(() => {
      if (!moved.current) setPickerOpen(true);
    }, LONG_PRESS_MS);
  };

  const onPointerMove = (event: React.PointerEvent) => {
    if (!dragging.current) return;
    const dx = event.clientX - startX.current;
    if (Math.abs(dx) > 6) {
      moved.current = true;
      cancelLongPress();
    }
    // Clamped, and only in the one direction that means "reply" for this side.
    const allowed = mine ? Math.min(0, dx) : Math.max(0, dx);
    setOffset(Math.max(-90, Math.min(90, allowed)));
  };

  const endDrag = () => {
    cancelLongPress();
    if (!dragging.current) return;
    dragging.current = false;
    if (Math.abs(offset) >= SWIPE_TO_REPLY) {
      onReply();
    }
    setOffset(0);
  };

  const act = (run: () => void) => () => {
    setMenuOpen(false);
    setPickerOpen(false);
    run();
  };

  const copy = () => {
    void navigator.clipboard?.writeText(message.body ?? "").catch(() => {
      // Clipboard needs a secure context and permission. Failing silently is right here:
      // there is nothing useful to say and nothing broken in the conversation itself.
    });
  };

  return (
    <div
      data-testid="messageRow"
      className="group relative flex w-full"
      style={{ justifyContent: mine ? "flex-end" : "flex-start" }}
    >
      <div
        data-testid="message"
        data-seq={message.seq ?? ""}
        data-mine={mine}
        data-deleted={Boolean(message.deleted)}
        className="rise relative max-w-[74%] px-3.5 py-2.5"
        style={{
          transform: `translateX(${offset}px)`,
          transition: dragging.current ? "none" : "transform .18s ease",
          touchAction: "pan-y",
          color: mine && !message.deleted ? "#fff" : "var(--color-body)",
          background: message.deleted
            ? "var(--color-surface-2)"
            : mine
              ? "linear-gradient(135deg, #6d4dfb, var(--color-brand-2))"
              : "var(--color-surface-2)",
          border: `1px solid ${mine && !message.deleted ? "transparent" : "var(--color-line-soft)"}`,
          borderRadius: mine ? "16px 16px 5px 16px" : "16px 16px 16px 5px",
          overflowWrap: "anywhere",
        }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onPointerLeave={endDrag}
        onDoubleClick={() => {
          if (!message.deleted) onReact("❤️");
        }}
        onContextMenu={(event) => {
          event.preventDefault();
          if (!message.deleted) setMenuOpen(true);
        }}
      >
        {quoted && <Quoted message={quoted} mine={mine} />}

        {message.deleted ? (
          <div className="flex items-center gap-1.5 text-[14px] italic opacity-60">
            <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="9" />
              <path d="M6 6l12 12" />
            </svg>
            This message was deleted
          </div>
        ) : message.kind === "image" && message.mediaKey ? (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img
            alt="shared image"
            src={mediaUrl(message.mediaKey)}
            draggable={false}
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
          {mine && !message.deleted && <Ticks state={delivery} />}
        </div>

        {(message.reactions ?? []).length > 0 && (
          <button
            type="button"
            data-testid="reactions"
            onClick={() => setPickerOpen(true)}
            className="absolute -bottom-3 flex cursor-pointer items-center gap-0.5 rounded-full border px-1.5 py-0.5 text-[12px] shadow"
            style={{
              [mine ? "right" : "left"]: 8,
              borderColor: "var(--color-line)",
              backgroundColor: "var(--color-surface-3)",
              color: "var(--color-body)",
            }}
          >
            {[...new Set((message.reactions ?? []).map((r) => r.emoji))].map((emoji) => (
              <span key={emoji}>{emoji}</span>
            ))}
            {(message.reactions ?? []).length > 1 && (
              <span className="ml-0.5 text-[10px] opacity-70">
                {(message.reactions ?? []).length}
              </span>
            )}
          </button>
        )}
      </div>

      {/* The desktop way in. On a phone it is a long press, which opens the picker directly. */}
      {!message.deleted && (
        <button
          type="button"
          data-testid="messageMenuButton"
          aria-label="Message actions"
          onClick={() => setMenuOpen((open) => !open)}
          className="self-center opacity-0 transition group-hover:opacity-60 hover:!opacity-100"
          style={{ order: mine ? -1 : 1, padding: "0 6px", color: "var(--color-muted)" }}
        >
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor">
            <circle cx="12" cy="5" r="1.8" />
            <circle cx="12" cy="12" r="1.8" />
            <circle cx="12" cy="19" r="1.8" />
          </svg>
        </button>
      )}

      {(menuOpen || pickerOpen) && (
        <div
          className="fixed inset-0 z-40"
          onClick={() => {
            setMenuOpen(false);
            setPickerOpen(false);
          }}
        />
      )}

      {pickerOpen && (
        <div className="absolute -top-11 z-50" style={{ [mine ? "right" : "left"]: 12 }}>
          <EmojiPicker chosen={myReaction} onPick={(emoji) => act(() => onReact(emoji))()} />
        </div>
      )}

      {menuOpen && (
        <div
          data-testid="messageMenu"
          className="absolute top-8 z-50 flex min-w-44 flex-col overflow-hidden rounded-xl border py-1 shadow-xl"
          style={{
            [mine ? "right" : "left"]: 12,
            borderColor: "var(--color-line)",
            backgroundColor: "var(--color-surface-2)",
          }}
          onClick={(event) => event.stopPropagation()}
        >
          <MenuItem testId="menuReply" label="Reply" onClick={act(onReply)} />
          <MenuItem testId="menuCopy" label="Copy" onClick={act(copy)} />
          <MenuItem
            testId="menuReact"
            label="React"
            onClick={() => {
              setMenuOpen(false);
              setPickerOpen(true);
            }}
          />
          {/* Yours deletes for both; theirs only for you. Two different acts, so two labels. */}
          <MenuItem
            testId="menuDelete"
            label={mine ? "Delete for everyone" : "Delete for me"}
            danger
            onClick={act(mine ? onDeleteForEveryone : onHideForMe)}
          />
        </div>
      )}
    </div>
  );
};

const MenuItem = ({
  label,
  onClick,
  danger,
  testId,
}: {
  label: string;
  onClick: () => void;
  danger?: boolean;
  testId: string;
}) => (
  <button
    type="button"
    data-testid={testId}
    onClick={onClick}
    className="px-3.5 py-2 text-left text-[14px] transition hover:bg-[var(--color-surface-3)]"
    style={{ color: danger ? "var(--color-danger)" : "var(--color-body)" }}
  >
    {label}
  </button>
);
