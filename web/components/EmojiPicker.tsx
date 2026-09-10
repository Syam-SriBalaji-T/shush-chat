"use client";

import { useState } from "react";

/** The five that cover almost everything, and the rest behind a plus. */
export const QUICK = ["👍", "❤️", "😂", "😮", "😢"];
const REST = ["🙏", "🔥", "🎉"];

export const EmojiPicker = ({
  chosen,
  onPick,
}: {
  chosen?: string | null;
  onPick: (emoji: string) => void;
}) => {
  const [showAll, setShowAll] = useState(false);
  const emojis = showAll ? [...QUICK, ...REST] : QUICK;

  return (
    <div
      data-testid="emojiPicker"
      className="flex items-center gap-1 rounded-full border px-2 py-1.5 shadow-lg"
      style={{ borderColor: "var(--color-line)", backgroundColor: "var(--color-surface-2)" }}
      onClick={(event) => event.stopPropagation()}
    >
      {emojis.map((emoji) => (
        <button
          key={emoji}
          type="button"
          data-testid="emojiOption"
          aria-pressed={chosen === emoji}
          onClick={() => onPick(emoji)}
          className="grid h-8 w-8 place-items-center rounded-full text-lg transition hover:scale-125 aria-[pressed=true]:bg-[var(--color-surface-3)]"
        >
          {emoji}
        </button>
      ))}
      {!showAll && (
        <button
          type="button"
          data-testid="moreEmoji"
          title="More"
          onClick={() => setShowAll(true)}
          className="grid h-8 w-8 place-items-center rounded-full text-lg transition hover:bg-[var(--color-surface-3)]"
          style={{ color: "var(--color-muted)" }}
        >
          +
        </button>
      )}
    </div>
  );
};
