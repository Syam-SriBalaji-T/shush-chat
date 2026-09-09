/**
 * Where the API is.
 *
 * <p>Empty by default, which means "same origin". In the deployed stack nginx serves this app
 * at / and proxies /api and /ws to the API, so the browser only ever sees one origin and no
 * CORS is involved at all. NEXT_PUBLIC_API_BASE exists for `next dev`, which runs on its own
 * port and therefore genuinely is cross-origin -- and that is the case the cors_origins table
 * is for.
 */
export const API_BASE = (process.env.NEXT_PUBLIC_API_BASE ?? "").replace(/\/$/, "");

export const apiUrl = (path: string) => `${API_BASE}${path}`;

export const socketUrl = (token: string) => {
  const base = API_BASE || (typeof window === "undefined" ? "" : window.location.origin);
  const url = new URL(base);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/ws/chat";
  url.search = `?token=${encodeURIComponent(token)}`;
  return url.toString();
};
