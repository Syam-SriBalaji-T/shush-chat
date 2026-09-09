import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Shush",
  description: "Talk to someone new. No sign-up, no email, no form.",
};

/**
 * Reads the remembered theme before the first paint.
 *
 * Inline and blocking on purpose: anything later means the page renders in the default theme
 * and then snaps to the chosen one, which is worse than the millisecond this costs.
 */
const themeScript = `
try {
  document.documentElement.dataset.theme = localStorage.getItem("shush.theme") || "dark";
} catch (ignored) {
  document.documentElement.dataset.theme = "dark";
}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" data-theme="dark" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body className="font-sans text-[15px] leading-relaxed">{children}</body>
    </html>
  );
}
