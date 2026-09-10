"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import type { Conversation, Friend, FriendRequest, Session } from "@/lib/types";
import { Avatar } from "./Avatar";

export const Sidebar = ({
  session,
  friends,
  requests,
  conversations,
  openConversationId,
  chatOnScreen,
  onOpenFriend,
  onOpenConversation,
  onFindSomeone,
  onRefresh,
}: {
  session: Session;
  friends: Friend[];
  requests: FriendRequest[];
  conversations: Conversation[];
  openConversationId: string | null;
  chatOnScreen: boolean;
  onOpenFriend: (friend: Friend) => void;
  onOpenConversation: (conversation: Conversation) => void;
  onFindSomeone: () => void;
  onRefresh: () => Promise<void>;
}) => {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [saveStatus, setSaveStatus] = useState("");

  const save = async () => {
    const response = await api.signup(email, password);
    if (!response.ok) {
      setSaveStatus(((await response.json()) as { message: string }).message);
      return;
    }
    setSaveStatus("Saved. Nothing reset — same name, same friends.");
  };

  return (
    <aside
      id="sidebar"
      className="flex min-h-0 flex-col gap-5 overflow-y-auto border-r p-4"
      style={{ borderColor: "var(--color-line-soft)" }}
    >
      <div>
        <h2 className="section-label flex items-center gap-2">
          Requests
          {requests.length > 0 && (
            <span
              data-testid="requestCount"
              className="rounded-full px-1.5 py-px text-[10px] font-bold tracking-normal text-white"
              style={{ background: "linear-gradient(135deg, var(--color-brand), var(--color-brand-2))" }}
            >
              {requests.length}
            </span>
          )}
        </h2>
        <ul id="requests" className="m-0 flex list-none flex-col gap-1 p-0">
          {requests.map((request) => (
            <li
              key={request.id}
              data-testid="request"
              className="flex flex-col gap-2 rounded-xl border p-3"
              style={{ borderColor: "var(--color-line)", backgroundColor: "var(--color-surface-2)" }}
            >
              <p className="m-0 text-[13px]">Someone would like to keep you.</p>
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  className="btn-primary px-3 py-1.5 text-[13px]"
                  onClick={async () => {
                    await api.acceptRequest(request.id);
                    await onRefresh();
                  }}
                >
                  Accept
                </button>
                <button
                  type="button"
                  className="btn-ghost px-3 py-1.5 text-[13px]"
                  onClick={async () => {
                    await api.declineRequest(request.id);
                    await onRefresh();
                  }}
                >
                  Decline
                </button>
              </div>
            </li>
          ))}
        </ul>
        {requests.length === 0 && (
          <p id="noRequests" className="mt-0.5 text-[13px]" style={{ color: "var(--color-faint)" }}>
            Nobody has asked to keep you yet.
          </p>
        )}
      </div>

      <div>
        <h2 className="section-label">Friends</h2>
        <ul id="friends" className="m-0 flex list-none flex-col gap-1 p-0">
          {friends.map((friend) => {
            // Hidden only while that conversation is actually on screen. "The open one" is not
            // the same as "the last one opened" -- after going home the thread is still
            // selected, and hiding the badge on that basis hides the count you came back for.
            const onScreenNow = chatOnScreen && friend.conversationId === openConversationId;
            return (
              <li key={friend.userId}>
                <button
                  type="button"
                  data-testid="friend"
                  aria-current={friend.conversationId === openConversationId}
                  onClick={() => onOpenFriend(friend)}
                  className="flex w-full items-center gap-2.5 rounded-xl border border-transparent p-2 text-left transition hover:border-[var(--color-line-soft)] hover:bg-[var(--color-surface-2)] aria-[current=true]:border-[var(--color-brand)] aria-[current=true]:bg-[var(--color-surface-2)]"
                >
                  <Avatar id={friend.userId} name={friend.displayName} online={friend.online} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">
                      {friend.displayName ?? "Someone"}
                    </span>
                    <span className="block text-xs" style={{ color: "var(--color-faint)" }}>
                      {friend.online ? "Online" : "Offline"}
                    </span>
                  </span>
                  {friend.unreadCount > 0 && !onScreenNow && (
                    <span
                      data-testid="unread"
                      className="grid h-5 min-w-5 flex-none place-items-center rounded-full px-1.5 text-[11px] font-bold text-white"
                      style={{
                        background:
                          "linear-gradient(135deg, var(--color-brand), var(--color-brand-2))",
                      }}
                    >
                      {friend.unreadCount > 99 ? "99+" : friend.unreadCount}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
        {friends.length === 0 && (
          <p id="noFriends" className="mt-0.5 text-[13px]" style={{ color: "var(--color-faint)" }}>
            Nobody yet. Keep someone you enjoyed talking to.
          </p>
        )}
      </div>

      <div>
        <h2 className="section-label">Chats</h2>
        <ul id="chats" className="m-0 flex list-none flex-col gap-1 p-0">
          {conversations.map((conversation) => {
            const onScreenNow = chatOnScreen && conversation.id === openConversationId;
            return (
              <li key={conversation.id}>
                <button
                  type="button"
                  data-testid="chat"
                  data-conversation-id={conversation.id}
                  aria-current={conversation.id === openConversationId}
                  onClick={() => onOpenConversation(conversation)}
                  className="flex w-full items-center gap-2.5 rounded-xl border border-transparent p-2 text-left transition hover:border-[var(--color-line-soft)] hover:bg-[var(--color-surface-2)] aria-[current=true]:border-[var(--color-brand)] aria-[current=true]:bg-[var(--color-surface-2)]"
                >
                  <Avatar id={conversation.peerId} name={conversation.peerName} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">
                      {conversation.peerName ?? "Someone"}
                    </span>
                    <span
                      className="block truncate text-xs"
                      style={{ color: "var(--color-faint)" }}
                    >
                      {conversation.lastMessage
                        ? `${conversation.lastFromMe ? "You: " : ""}${conversation.lastMessage}`
                        : conversation.kind === "friend"
                          ? "Friend"
                          : "Nothing said yet"}
                    </span>
                  </span>
                  {conversation.unreadCount > 0 && !onScreenNow && (
                    <span
                      data-testid="chatUnread"
                      className="grid h-5 min-w-5 flex-none place-items-center rounded-full px-1.5 text-[11px] font-bold text-white"
                      style={{
                        background:
                          "linear-gradient(135deg, var(--color-brand), var(--color-brand-2))",
                      }}
                    >
                      {conversation.unreadCount > 99 ? "99+" : conversation.unreadCount}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
        {conversations.length === 0 && (
          <p id="noChats" className="mt-0.5 text-[13px]" style={{ color: "var(--color-faint)" }}>
            Nothing yet. Every conversation you have shows up here, friend or stranger.
          </p>
        )}
      </div>

      <button id="newChat" type="button" className="btn-primary w-full" onClick={onFindSomeone}>
        Find someone new
      </button>

      {session.user.anonymous && (
        /* Pushed to the bottom: the least urgent thing here and the most permanent, so it
           should not sit between the friends list and the button people came for. */
        <div
          id="saveBox"
          className="mt-auto border-t pt-4"
          style={{ borderColor: "var(--color-line-soft)" }}
        >
          <h2 className="section-label">Save your account</h2>
          <p className="mb-2.5 text-[13px]" style={{ color: "var(--color-faint)" }}>
            Only this browser connects you to this account. Clear it and your friends are gone.
          </p>
          <div className="flex flex-col gap-2">
            <input
              id="email"
              type="email"
              className="field"
              placeholder="you@example.com"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <input
              id="password"
              type="password"
              className="field"
              placeholder="Password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <button id="saveAccount" type="button" className="btn" onClick={save}>
              Save
            </button>
            {saveStatus && (
              <span className="text-[13px]" style={{ color: "var(--color-faint)" }}>
                {saveStatus}
              </span>
            )}
          </div>
        </div>
      )}
    </aside>
  );
};
