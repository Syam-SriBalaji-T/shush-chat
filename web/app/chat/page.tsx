"use client";

import { useState } from "react";
import Link from "next/link";
import { Avatar } from "@/components/Avatar";
import { Brand } from "@/components/Brand";
import { ChatPanel } from "@/components/ChatPanel";
import { ProfileDialog, type ProfileTarget } from "@/components/ProfileDialog";
import { SetupPanel } from "@/components/SetupPanel";
import { Sidebar } from "@/components/Sidebar";
import { ThemeToggle } from "@/components/ThemeToggle";
import { useShush } from "@/lib/useShush";

/**
 * Client-rendered, and it has to be: this page is a live websocket, a session token held in
 * this browser, and a conversation that only exists once both are up. There is nothing here a
 * server could usefully render ahead of time. The landing page at / is the server component.
 */
export default function Chat() {
  const shush = useShush();
  const [profile, setProfile] = useState<ProfileTarget | null>(null);

  const signedIn = Boolean(shush.session);

  return (
    <div className="grid h-full grid-rows-[auto_1fr]">
      <header
        className="relative z-10 flex items-center gap-3.5 border-b px-5 py-3.5 backdrop-blur-xl"
        style={{
          borderColor: "var(--color-line-soft)",
          backgroundColor: "color-mix(in srgb, var(--color-surface) 70%, transparent)",
        }}
      >
        {/* The logo goes back to the start screen. It only changes what is on screen: walking
            out of a stranger conversation by navigating away would be a silent disappearance
            for the other person, and Leave is still the button that ends it. */}
        <button
          id="home"
          type="button"
          title="Back to the start"
          onClick={shush.goHome}
          className="-ml-2 cursor-pointer rounded-[10px] px-2 py-1"
        >
          <Brand />
        </button>

        {shush.session && (
          <button
            id="displayName"
            type="button"
            title="Your profile"
            onClick={() =>
              setProfile({
                userId: shush.session!.user.id,
                name: shush.session!.user.displayName,
                sub: "This is you. Nobody sees anything else.",
                mine: true,
                friend: false,
              })
            }
            className="flex cursor-pointer items-center gap-2 rounded-full border px-3 py-1.5 text-xs"
            style={{
              borderColor: "var(--color-line)",
              backgroundColor: "var(--color-surface-2)",
              color: "var(--color-muted)",
            }}
          >
            <Avatar id={shush.session.user.id} name={shush.session.user.displayName} size={18} />
            {shush.session.user.displayName}
          </button>
        )}

        <span className="flex-1" />

        {shush.nodeId && (
          /* Diagnostic, and worth surfacing: it makes "any node serves any user" visible. */
          <span
            id="nodeId"
            className="rounded-full border px-3 py-1.5 text-xs whitespace-nowrap"
            style={{
              borderColor: "var(--color-line)",
              backgroundColor: "var(--color-surface-2)",
              color: "var(--color-muted)",
            }}
          >
            served by {shush.nodeId}
          </span>
        )}
        <ThemeToggle />
      </header>

      <main
        className="grid min-h-0"
        style={{ gridTemplateColumns: signedIn ? "272px 1fr" : "1fr" }}
      >
        {shush.session && (
          <Sidebar
            session={shush.session}
            friends={shush.friends}
            requests={shush.requests}
            openConversationId={shush.conversationId}
            chatOnScreen={shush.view === "chat"}
            onOpenFriend={shush.openFriend}
            onFindSomeone={shush.goHome}
            onRefresh={async () => {
              await shush.reloadRequests();
              await shush.reloadFriends();
            }}
          />
        )}

        <section className="flex min-h-0 flex-col">
          {!signedIn && (
            <div className="grid min-h-0 flex-1 place-items-center p-6">
              <p style={{ color: "var(--color-muted)" }}>
                Getting you a name…{" "}
                <Link href="/" className="underline">
                  go back
                </Link>
              </p>
            </div>
          )}

          {signedIn && shush.view === "setup" && (
            <SetupPanel
              interests={shush.interests}
              selected={shush.selected}
              setSelected={shush.setSelected}
              patience={shush.patience}
              setPatience={shush.setPatience}
              findStatus={shush.findStatus}
              onFind={shush.findSomeone}
            />
          )}

          {signedIn && shush.view === "chat" && (
            <ChatPanel
              peer={shush.peer}
              items={shush.items}
              meId={shush.session?.user.id}
              typing={shush.typing}
              isFriendConversation={shush.isFriendConversation}
              onOpenPeer={() =>
                setProfile({
                  userId: shush.peer.userId,
                  name: shush.currentFriend?.displayName ?? shush.peer.name ?? "A stranger",
                  sub: shush.currentFriend
                    ? shush.currentFriend.online
                      ? "Online"
                      : "Offline"
                    : "You have not kept this person",
                  mine: false,
                  friend: Boolean(shush.currentFriend),
                })
              }
              onSend={shush.sendMessage}
              onImage={shush.sendImage}
              onTyping={shush.notifyTyping}
              onAskToKeep={shush.askToKeep}
              onLeave={shush.leave}
            />
          )}
        </section>
      </main>

      <ProfileDialog
        target={profile}
        onClose={() => setProfile(null)}
        onShuffle={shush.shuffleName}
        onRemoveFriend={shush.removeFriend}
      />
    </div>
  );
}
