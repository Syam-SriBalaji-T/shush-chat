export type Interest = { id: number; label: string };

export type User = { id: string; displayName: string; anonymous: boolean };

export type Session = { token?: string; jwt: string; user: User };

export type Friend = {
  userId: string;
  displayName: string | null;
  conversationId: string;
  online: boolean;
  lastSeenAt: string | null;
  unreadCount: number;
};

export type FriendRequest = {
  id: string;
  conversationId: string;
  fromUserId: string;
  toUserId: string;
  status: string;
  expiresAt: string;
};

export type Message = {
  id?: string;
  conversationId: string;
  senderId: string;
  seq: number | null;
  kind: "text" | "image";
  body: string | null;
  mediaKey: string | null;
  clientMsgId: string | null;
  createdAt: string | number;
};

/** How far a message has got, in the order it gets there. */
export type Delivery = "pending" | "sent" | "delivered" | "read";

export type ChatItem =
  | { kind: "message"; message: Message; delivery: Delivery }
  | { kind: "event"; id: string; text: string }
  | { kind: "day"; id: string; label: string };

/** Frames the server sends. Only the ones this client acts on are named. */
export type ServerFrame = {
  type: string;
  [key: string]: unknown;
};
