"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, setBearer } from "./api";
import { socketUrl } from "./config";
import { newId } from "./ids";
import { dayKey, dayLabel } from "./time";
import type { ChatItem, Delivery, Friend, FriendRequest, Interest, Message, Session } from "./types";

const DEVICE_TOKEN = "shush.deviceToken";

const remember = (token: string) => {
  try {
    localStorage.setItem(DEVICE_TOKEN, token);
  } catch {
    // Private browsing. The account still exists; this browser just will not remember it.
  }
};

const remembered = () => {
  try {
    return localStorage.getItem(DEVICE_TOKEN);
  } catch {
    return null;
  }
};

/** Delivery only ever moves forward: a redelivered frame must not turn a read tick grey. */
const RANK: Record<Delivery, number> = { pending: 0, sent: 1, delivered: 2, read: 3 };
const furthest = (a: Delivery, b: Delivery) => (RANK[b] > RANK[a] ? b : a);

export type Peer = { userId: string | null; name: string | null; heading: string; sub: string };

export const useShush = () => {
  const [session, setSession] = useState<Session | null>(null);
  const [view, setView] = useState<"setup" | "chat">("setup");
  const [friends, setFriends] = useState<Friend[]>([]);
  const [requests, setRequests] = useState<FriendRequest[]>([]);
  const [interests, setInterests] = useState<{ suggested: Interest[]; all: Interest[] }>({
    suggested: [],
    all: [],
  });
  const [selected, setSelected] = useState<number[]>([]);
  const [patience, setPatience] = useState(5);
  const [nodeId, setNodeId] = useState<string | null>(null);
  const [findStatus, setFindStatus] = useState("");
  const [typing, setTyping] = useState(false);
  const [items, setItems] = useState<ChatItem[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [peer, setPeer] = useState<Peer>({ userId: null, name: null, heading: "", sub: "" });
  const [isFriendConversation, setIsFriendConversation] = useState(false);

  const socket = useRef<WebSocket | null>(null);
  const seen = useRef(new Set<number>());
  const lastDay = useRef<string | null>(null);
  const peerReadSeq = useRef(0);
  const conversationRef = useRef<string | null>(null);
  const viewRef = useRef<"setup" | "chat">("setup");
  const typingSentAt = useRef(0);
  const hintTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const friendsRef = useRef<Friend[]>([]);

  useEffect(() => {
    conversationRef.current = conversationId;
  }, [conversationId]);
  useEffect(() => {
    viewRef.current = view;
  }, [view]);
  useEffect(() => {
    friendsRef.current = friends;
  }, [friends]);

  const send = useCallback((frame: Record<string, unknown>) => {
    socket.current?.send(JSON.stringify(frame));
  }, []);

  const reloadFriends = useCallback(async () => {
    try {
      setFriends(await api.friends());
    } catch {
      // A failed refresh leaves the list as it was, which is better than emptying it.
    }
  }, []);

  const reloadRequests = useCallback(async () => {
    try {
      setRequests(await api.friendRequests());
    } catch {
      /* as above */
    }
  }, []);

  /** Adds a day separator when the calendar day changes, then the message itself. */
  const appendMessage = useCallback((message: Message, delivery: Delivery) => {
    setItems((current) => {
      const next = [...current];
      const key = dayKey(message.createdAt);
      if (key !== lastDay.current) {
        lastDay.current = key;
        next.push({ kind: "day", id: `day-${key}`, label: dayLabel(message.createdAt) });
      }
      next.push({ kind: "message", message, delivery });
      return next;
    });
  }, []);

  const appendEvent = useCallback((text: string) => {
    setItems((current) => [...current, { kind: "event", id: newId(), text }]);
  }, []);

  const openConversation = useCallback(
    (id: string, next: Peer, friendConversation: boolean) => {
      seen.current = new Set();
      lastDay.current = null;
      peerReadSeq.current = 0;
      setItems([]);
      setConversationId(id);
      conversationRef.current = id;
      setPeer(next);
      setIsFriendConversation(friendConversation);
      setView("chat");
      viewRef.current = "chat";
      setFindStatus("");
      setTyping(false);
      if (hintTimer.current) clearTimeout(hintTimer.current);
    },
    [],
  );

  /* ---------- frames ---------- */

  const onFrame = useCallback(
    (frame: Record<string, unknown>) => {
      const type = String(frame.type);

      if (type === "hello") {
        setNodeId(String(frame.nodeId));
        return;
      }

      if (type === "matched") {
        const shared = (frame.sharedInterestIds as number[] | null) ?? [];
        const labels = shared
          .map((id) => interests.all.find((i) => i.id === id)?.label)
          .filter(Boolean);
        openConversation(
          String(frame.conversationId),
          {
            userId: String(frame.withUserId),
            name: null,
            heading: frame.randomMatch
              ? "A random match — nobody sharing your interests was around"
              : `You both like ${labels.join(" and ")}`,
            sub: "A stranger",
          },
          false,
        );
        void reloadFriends();
        return;
      }

      if (type === "ack") {
        const clientMsgId = String(frame.clientMsgId);
        const reached: Delivery = frame.status === "delivered" ? "delivered" : "sent";
        setItems((current) =>
          current.map((item) =>
            item.kind === "message" && item.message.clientMsgId === clientMsgId
              ? {
                  ...item,
                  delivery: furthest(item.delivery, reached),
                  message: {
                    ...item.message,
                    seq: (frame.seq as number | null) ?? item.message.seq,
                  },
                }
              : item,
          ),
        );
        return;
      }

      if (type === "message") {
        const message = frame as unknown as Message;
        const mine = message.senderId === session?.user.id;
        const onScreen =
          viewRef.current === "chat" && message.conversationId === conversationRef.current;

        if (!onScreen) {
          // Waiting for you rather than lost: the server keeps the count and the history load
          // draws it when that thread is opened.
          void reloadFriends();
          return;
        }

        if (mine) {
          // Reconciles the optimistic bubble rather than drawing a second copy of it.
          seen.current.add(message.seq as number);
          setItems((current) =>
            current.map((item) =>
              item.kind === "message" && item.message.clientMsgId === message.clientMsgId
                ? {
                    kind: "message",
                    message,
                    delivery: furthest(
                      item.delivery,
                      (message.seq ?? 0) <= peerReadSeq.current ? "read" : "delivered",
                    ),
                  }
                : item,
            ),
          );
          return;
        }

        if (message.seq !== null && seen.current.has(message.seq)) {
          return;
        }
        if (message.seq !== null) seen.current.add(message.seq);
        appendMessage(message, "delivered");
        send({ type: "read", conversationId: message.conversationId, seq: message.seq });
        return;
      }

      if (type === "typing") {
        setTyping(true);
        setTimeout(() => setTyping(false), 4000);
        return;
      }

      if (type === "read") {
        const seq = Number(frame.seq);
        peerReadSeq.current = Math.max(peerReadSeq.current, seq);
        setItems((current) =>
          current.map((item) =>
            item.kind === "message" &&
            item.message.senderId === session?.user.id &&
            (item.message.seq ?? Number.MAX_SAFE_INTEGER) <= peerReadSeq.current
              ? { ...item, delivery: furthest(item.delivery, "read") }
              : item,
          ),
        );
        return;
      }

      if (type === "presence") {
        appendEvent(
          frame.online
            ? "They are back."
            : "They have gone offline. Anything you send will reach them when they return.",
        );
        void reloadFriends();
        return;
      }

      if (type === "left") {
        appendEvent("They have left. This conversation is over.");
        return;
      }

      if (type === "friendRequested") {
        void reloadRequests();
        return;
      }

      if (type === "friendRequestAccepted") {
        appendEvent("They kept you. They are in your friends list now.");
        void reloadFriends();
        return;
      }

      if (type === "error") {
        appendEvent(`Something went wrong: ${String(frame.message)}`);
      }
    },
    [appendEvent, appendMessage, interests.all, openConversation, reloadFriends, reloadRequests, send, session],
  );

  const frameHandler = useRef(onFrame);
  useEffect(() => {
    frameHandler.current = onFrame;
  }, [onFrame]);

  /* ---------- sign in, once ---------- */

  const started = useRef(false);

  const start = useCallback(async () => {
    if (started.current) return;
    started.current = true;

    const token = remembered();
    let next: Session | null = null;
    if (token) {
      next = await api.resumeDevice(token).catch(() => null);
    }
    if (!next) {
      next = await api.anonymous();
    }
    setBearer(next.jwt);
    if (next.token) remember(next.token);
    setSession(next);

    const catalogue = await api.interests();
    setInterests({ suggested: catalogue.suggested, all: catalogue.all });
    setSelected(catalogue.fromHistory ? catalogue.suggested.map((i) => i.id) : []);

    await new Promise<void>((resolve) => {
      const ws = new WebSocket(socketUrl(next.jwt));
      ws.addEventListener("message", (event) =>
        frameHandler.current(JSON.parse(event.data as string)),
      );
      ws.addEventListener("open", () => resolve());
      socket.current = ws;
    });

    // Both before anything can be pushed, so a request that was already waiting is on screen
    // from the moment you sign in rather than only after the next one happens to arrive.
    await Promise.all([reloadFriends(), reloadRequests()]);
  }, [reloadFriends, reloadRequests]);

  useEffect(() => {
    void start();
    return () => socket.current?.close();
  }, [start]);

  /* ---------- actions ---------- */

  const findSomeone = useCallback(async () => {
    setFindStatus("Looking…");
    await api.saveInterests(selected);
    send({ type: "find", interestIds: selected, patience });
    if (hintTimer.current) clearTimeout(hintTimer.current);
    // Matching skips anyone already a friend, so with a few friends and nobody else waiting it
    // looks exactly like a broken matcher. Say so rather than spin forever.
    hintTimer.current = setTimeout(() => {
      setFindStatus(
        friendsRef.current.length
          ? "Still looking. People you are already friends with are skipped — you can message them from the list, or remove one from their profile."
          : "Still looking. Nobody sharing your interests is here right now.",
      );
    }, 12_000);
  }, [patience, selected, send]);

  const openFriend = useCallback(
    async (friend: Friend) => {
      openConversation(
        friend.conversationId,
        {
          userId: friend.userId,
          name: friend.displayName,
          heading: friend.displayName ?? "Someone",
          sub: friend.online ? "Online" : "Offline",
        },
        true,
      );

      // How far they have read, before any history is drawn -- otherwise the whole backlog
      // renders grey and only goes blue if they happen to read something new.
      const view = await api.conversation(friend.conversationId).catch(() => null);
      peerReadSeq.current = Math.max(0, ...(view?.others ?? []).map((o) => o.readCursorSeq ?? 0), 0);

      const history = await api.history(friend.conversationId).catch(() => null);
      if (!history) {
        appendEvent("That conversation could not be opened.");
        return;
      }
      // Oldest first already: the service walks the index backwards to build the page and
      // re-sorts ascending before returning it.
      history.messages.forEach((message) => {
        if (message.seq !== null) seen.current.add(message.seq);
        const mine = message.senderId === session?.user.id;
        appendMessage(
          message,
          mine && (message.seq ?? 0) <= peerReadSeq.current ? "read" : "delivered",
        );
      });
      if (!history.messages.length) {
        appendEvent("Nothing here yet. Say something.");
      }
      const newest = history.messages[history.messages.length - 1];
      if (newest) {
        send({ type: "read", conversationId: friend.conversationId, seq: newest.seq });
      }
      await reloadFriends();
    },
    [appendEvent, appendMessage, openConversation, reloadFriends, send, session],
  );

  const sendMessage = useCallback(
    (body: string) => {
      const text = body.trim();
      if (!text || !conversationId) return;
      const clientMsgId = newId();
      // On screen immediately with no seq: that is what a single tick means.
      appendMessage(
        {
          conversationId,
          senderId: session!.user.id,
          seq: null,
          kind: "text",
          body: text,
          mediaKey: null,
          clientMsgId,
          createdAt: Date.now(),
        },
        "pending",
      );
      send({ type: "send", conversationId, clientMsgId, kind: "text", body: text });
    },
    [appendMessage, conversationId, send, session],
  );

  const sendImage = useCallback(
    async (file: File) => {
      if (!conversationId) return;
      const issued = await api.uploadUrl(conversationId, file.type, file.size);
      if (!issued.ok) {
        appendEvent(`That image was refused: ${await issued.text()}`);
        return;
      }
      const upload = (await issued.json()) as { key: string; uploadUrl: string };
      // Reported rather than swallowed: this PUT goes to object storage, not to the API, so
      // when it fails the server has nothing to log and the image just never appears.
      try {
        const stored = await fetch(upload.uploadUrl, {
          method: "PUT",
          headers: { "Content-Type": file.type },
          body: file,
        });
        if (!stored.ok) {
          appendEvent(`That image could not be stored (${stored.status}).`);
          return;
        }
      } catch {
        appendEvent("Could not reach image storage from this browser.");
        return;
      }
      send({
        type: "send",
        conversationId,
        clientMsgId: newId(),
        kind: "image",
        mediaKey: upload.key,
      });
    },
    [appendEvent, conversationId, send],
  );

  const notifyTyping = useCallback(() => {
    const now = Date.now();
    // Throttled here as well as at the server. One of these per keystroke is how this feature
    // takes a chat service down.
    if (conversationId && now - typingSentAt.current > 3000) {
      typingSentAt.current = now;
      send({ type: "typing", conversationId });
    }
  }, [conversationId, send]);

  const leave = useCallback(() => {
    if (!conversationId) return;
    send({ type: "leave", conversationId });
    appendEvent("You left.");
  }, [appendEvent, conversationId, send]);

  const askToKeep = useCallback(async () => {
    if (!conversationId) return;
    const response = await api.askToKeep(conversationId);
    appendEvent(
      response.ok
        ? "Asked to keep them. You will hear back only if they say yes."
        : "You have already asked.",
    );
  }, [appendEvent, conversationId]);

  const shuffleName = useCallback(async () => {
    const response = await api.shuffleName();
    if (response.status === 429) return "One at a time.";
    const user = (await response.json()) as { displayName: string };
    setSession((current) =>
      current ? { ...current, user: { ...current.user, displayName: user.displayName } } : current,
    );
    return "";
  }, []);

  const removeFriend = useCallback(
    async (userId: string) => {
      const response = await api.unfriend(userId);
      if (!response.ok) return;
      await reloadFriends();
      if (peer.userId === userId) {
        setView("setup");
        setConversationId(null);
      }
    },
    [peer.userId, reloadFriends],
  );

  const goHome = useCallback(() => {
    setView("setup");
    setFindStatus("");
  }, []);

  const currentFriend = useMemo(
    () => friends.find((friend) => friend.userId === peer.userId) ?? null,
    [friends, peer.userId],
  );

  return {
    session,
    view,
    friends,
    requests,
    interests,
    selected,
    setSelected,
    patience,
    setPatience,
    nodeId,
    findStatus,
    typing,
    items,
    conversationId,
    peer,
    currentFriend,
    isFriendConversation,
    findSomeone,
    openFriend,
    sendMessage,
    sendImage,
    notifyTyping,
    leave,
    askToKeep,
    shuffleName,
    removeFriend,
    goHome,
    reloadFriends,
    reloadRequests,
  };
};
