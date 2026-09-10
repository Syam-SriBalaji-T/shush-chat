"use client";

import { useState } from "react";
import type { Interest } from "@/lib/types";

/**
 * Five is enough to choose from without turning the first screen into a wall of tags, and
 * anything already selected stays visible however the list is trimmed -- a hidden selection is
 * worse than a long list.
 */
const SHOWN = 5;

const PATIENCE = [
  { seconds: 5, label: "5 seconds" },
  { seconds: 10, label: "10 seconds" },
  { seconds: 0, label: "As long as it takes" },
];

export const SetupPanel = ({
  interests,
  selected,
  setSelected,
  myInterests,
  onAddMyInterest,
  onRemoveMyInterest,
  patience,
  setPatience,
  findStatus,
  onFind,
  bare = false,
}: {
  interests: { suggested: Interest[]; all: Interest[] };
  selected: number[];
  setSelected: (next: number[]) => void;
  myInterests: string[];
  onAddMyInterest: (label: string) => void;
  onRemoveMyInterest: (label: string) => void;
  patience: number;
  setPatience: (next: number) => void;
  findStatus: string;
  onFind: () => void;
  /** Dropped into an existing surface rather than centred on its own screen. */
  bare?: boolean;
}) => {
  const [showAll, setShowAll] = useState(false);
  const [draft, setDraft] = useState("");

  // Suggestions first, then everything else, so the useful ones survive the trim.
  const ordered = [
    ...interests.suggested,
    ...interests.all.filter((one) => !interests.suggested.some((s) => s.id === one.id)),
  ];
  const head = ordered.slice(0, SHOWN);
  const visible = showAll
    ? ordered
    : [...head, ...ordered.filter((one) => selected.includes(one.id) && !head.includes(one))];

  const toggle = (id: number) =>
    setSelected(selected.includes(id) ? selected.filter((one) => one !== id) : [...selected, id]);

  const addMine = () => {
    onAddMyInterest(draft);
    setDraft("");
  };

  const body = (
    <>
        <div>
          <h2 className="section-label">What are you into?</h2>
          <div id="interestTiles" className="flex flex-wrap gap-2">
            {visible.map((interest) => {
              const on = selected.includes(interest.id);
              return (
                <button
                  key={interest.id}
                  type="button"
                  data-interest-id={interest.id}
                  data-testid="interest"
                  aria-pressed={on}
                  onClick={() => toggle(interest.id)}
                  className={on ? "btn-primary" : "btn"}
                >
                  {interest.label}
                </button>
              );
            })}
          </div>
          {ordered.length > SHOWN && (
            <div className="mt-2.5">
              <button
                id="showOthers"
                type="button"
                className="btn-ghost"
                onClick={() => setShowAll(!showAll)}
              >
                {showAll ? "Show less" : "Show more"}
              </button>
            </div>
          )}

          {/* Yours, kept in this browser and sent nowhere. Matching works on a shared
              vocabulary -- both sides picking from the same list is what makes "you both like
              Music" true -- so a tag only you have cannot pair you with anybody. It is here to
              say what you are into when the list has not got it. */}
          <div className="mt-4">
            <div className="mb-2 flex flex-wrap gap-2">
              {myInterests.map((label) => (
                <span
                  key={label}
                  data-testid="myInterest"
                  className="flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm"
                  style={{
                    borderColor: "var(--color-line)",
                    backgroundColor: "var(--color-surface-2)",
                    color: "var(--color-muted)",
                  }}
                >
                  {label}
                  <button
                    type="button"
                    aria-label={`Remove ${label}`}
                    onClick={() => onRemoveMyInterest(label)}
                    className="cursor-pointer opacity-60 hover:opacity-100"
                  >
                    ✕
                  </button>
                </span>
              ))}
            </div>
            <div className="flex items-center gap-2">
              <input
                id="myInterestInput"
                type="text"
                className="field max-w-[240px] py-1.5 text-sm"
                placeholder="Something else you are into"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") addMine();
                }}
              />
              <button
                id="addMyInterest"
                type="button"
                className="btn-ghost py-1.5 text-sm"
                onClick={addMine}
              >
                Add
              </button>
            </div>
            <p className="mt-1.5 text-[12px]" style={{ color: "var(--color-faint)" }}>
              Only you see these. They stay in this browser and are not used for matching.
            </p>
          </div>
        </div>

        <div className="mt-6">
          <h2 className="section-label">How long will you wait?</h2>
          <div className="flex flex-wrap gap-2">
            {PATIENCE.map((option) => (
              <button
                key={option.seconds}
                type="button"
                aria-pressed={patience === option.seconds}
                onClick={() => setPatience(option.seconds)}
                className={patience === option.seconds ? "btn-primary" : "btn"}
              >
                {option.label}
              </button>
            ))}
          </div>
          <p className="mt-2.5 text-[13px]" style={{ color: "var(--color-muted)" }}>
            {patience === 0
              ? "We will only match you with somebody who actually shares an interest, however long that takes."
              : `If nobody who shares your interests turns up in ${patience} seconds, we will just find you anyone.`}
          </p>
        </div>

        <div className="mt-6 flex flex-wrap items-center gap-3">
          <button
            id="findSomeone"
            type="button"
            className="btn-primary"
            disabled={selected.length === 0}
            onClick={onFind}
          >
            Find someone
          </button>
          <span id="findStatus" className="text-[13px]" style={{ color: "var(--color-muted)" }}>
            {findStatus}
          </span>
        </div>
    </>
  );

  return bare ? (
    body
  ) : (
    <div className="grid min-h-0 flex-1 place-items-center overflow-y-auto p-6">
      <div className="panel w-full max-w-[620px] p-7">{body}</div>
    </div>
  );
};
