export const clockTime = (value: string | number) =>
  new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

const midnight = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();

/**
 * "Today", "Yesterday", then a weekday, then a real date.
 *
 * Compares calendar days rather than elapsed hours on purpose: 23:50 and 00:10 are twenty
 * minutes apart and belong under different headings.
 */
export const dayLabel = (value: string | number) => {
  const then = new Date(value);
  const days = Math.round((midnight(new Date()) - midnight(then)) / 86_400_000);
  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  return then.toLocaleDateString(
    [],
    days < 7 ? { weekday: "long" } : { day: "numeric", month: "long", year: "numeric" },
  );
};

export const dayKey = (value: string | number) => new Date(value).toDateString();
