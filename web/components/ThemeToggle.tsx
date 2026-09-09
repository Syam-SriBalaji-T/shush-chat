"use client";

import { useEffect, useState } from "react";

export const ThemeToggle = () => {
  const [theme, setTheme] = useState<"dark" | "light">("dark");

  useEffect(() => {
    const current = document.documentElement.dataset.theme;
    setTheme(current === "light" ? "light" : "dark");
  }, []);

  const toggle = () => {
    const next = theme === "dark" ? "light" : "dark";
    setTheme(next);
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem("shush.theme", next);
    } catch {
      // Private browsing. The theme simply will not persist, which is not worth failing over.
    }
  };

  return (
    <button id="themeToggle" type="button" className="btn-ghost text-sm" onClick={toggle}>
      {theme === "dark" ? "Light" : "Dark"}
    </button>
  );
};
