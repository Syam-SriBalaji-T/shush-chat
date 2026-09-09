export const Brand = () => (
  <span className="flex items-center gap-2.5 text-lg font-bold tracking-tight">
    <span
      className="grid h-6.5 w-6.5 place-items-center rounded-[9px] text-sm font-extrabold text-white"
      style={{
        background: "linear-gradient(135deg, var(--color-brand), var(--color-cyan))",
        boxShadow: "0 4px 12px rgb(109 77 251 / 0.4)",
        width: 26,
        height: 26,
      }}
    >
      S
    </span>
    <span
      style={{
        backgroundImage: "linear-gradient(120deg, var(--color-body), var(--color-muted))",
        WebkitBackgroundClip: "text",
        backgroundClip: "text",
        color: "transparent",
      }}
    >
      Shush
    </span>
  </span>
);
