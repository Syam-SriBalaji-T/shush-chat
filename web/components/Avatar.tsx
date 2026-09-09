/** Derived from the id, so the same person is the same colour on both screens with nothing stored. */
const hueOf = (id: string | null) => {
  let hash = 0;
  for (const character of id ?? "") {
    hash = (hash * 31 + character.charCodeAt(0)) % 360;
  }
  return hash;
};

export const Avatar = ({
  id,
  name,
  size = 34,
  online,
  className = "",
}: {
  id: string | null;
  name?: string | null;
  size?: number;
  online?: boolean;
  className?: string;
}) => {
  const hue = hueOf(id);
  return (
    <span
      className={`relative grid flex-none place-items-center font-bold text-white ${className}`}
      style={{
        width: size,
        height: size,
        fontSize: size * 0.38,
        borderRadius: size * 0.32,
        background: `linear-gradient(135deg, hsl(${hue} 70% 55%), hsl(${(hue + 42) % 360} 70% 45%))`,
      }}
    >
      {(name ?? "?").trim().charAt(0).toUpperCase() || "?"}
      {online !== undefined && (
        <span
          data-online={online}
          className="absolute -right-0.5 -bottom-0.5 h-[11px] w-[11px] rounded-full border-2"
          style={{
            borderColor: "var(--color-ink)",
            backgroundColor: online ? "var(--color-online)" : "var(--color-faint)",
          }}
        />
      )}
    </span>
  );
};
