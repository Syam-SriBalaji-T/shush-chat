/**
 * A v4 UUID without requiring a secure context.
 *
 * crypto.randomUUID exists only on https or localhost, so on any plain-HTTP deployment -- a
 * LAN demo, a staging box, a browser in a container -- it is simply undefined and every send
 * throws. crypto.getRandomValues has no such restriction.
 */
export const newId = (): string => {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};
