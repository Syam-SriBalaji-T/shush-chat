"use client";

import { useEffect } from "react";
import { Avatar } from "./Avatar";

export type ProfileTarget = {
  userId: string | null;
  name: string | null;
  sub: string;
  mine: boolean;
  friend: boolean;
};

export const ProfileDialog = ({
  target,
  onClose,
  onRemoveFriend,
}: {
  target: ProfileTarget | null;
  onClose: () => void;
  onRemoveFriend: (userId: string) => Promise<void>;
}) => {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (!target) return null;

  return (
    <div
      id="profileBackdrop"
      className="fixed inset-0 z-50 grid place-items-center p-5 backdrop-blur-[3px]"
      style={{ backgroundColor: "rgb(4 5 9 / 0.62)" }}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="profileName"
        className="panel rise w-full max-w-90 p-6 text-center"
        style={{ maxWidth: 360 }}
      >
        <div className="mb-3.5 flex justify-center">
          <Avatar id={target.userId} name={target.name} size={68} />
        </div>
        <h3 id="profileName" className="m-0 mb-1 text-[19px] font-semibold">
          {target.name ?? "Someone"}
        </h3>
        <p className="mb-4.5 text-[13px]" style={{ color: "var(--color-faint)" }}>
          {target.sub}
        </p>
        <div className="flex flex-col gap-2">
          {target.friend && target.userId && (
            <button
              id="removeFriend"
              type="button"
              className="btn-ghost"
              style={{ color: "var(--color-danger)", borderColor: "rgb(251 113 133 / 0.4)" }}
              onClick={async () => {
                await onRemoveFriend(target.userId!);
                onClose();
              }}
            >
              Remove friend
            </button>
          )}
          <button id="closeProfile" type="button" className="btn-ghost" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
